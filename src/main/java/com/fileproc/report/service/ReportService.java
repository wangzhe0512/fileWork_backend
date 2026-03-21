package com.fileproc.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fileproc.common.BizException;
import com.fileproc.common.PageResult;
import com.fileproc.common.TenantContext;
import com.fileproc.common.annotation.OperationLog;
import com.fileproc.common.enums.ReportStatus;
import com.fileproc.common.util.FileUtil;
import com.fileproc.datafile.entity.DataFile;
import com.fileproc.datafile.mapper.DataFileMapper;
import com.fileproc.report.entity.Report;
import com.fileproc.report.mapper.ReportMapper;
import com.fileproc.template.entity.ReportModule;
import com.fileproc.template.entity.Template;
import com.fileproc.template.mapper.CompanyTemplateMapper;
import com.fileproc.template.mapper.ModuleMapper;
import com.fileproc.template.mapper.PlaceholderMapper;
import com.fileproc.template.mapper.SystemPlaceholderMapper;
import com.fileproc.template.mapper.TemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 报告管理 Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportMapper reportMapper;
    private final DataFileMapper dataFileMapper;
    private final TemplateMapper templateMapper;
    private final PlaceholderMapper placeholderMapper;
    private final ModuleMapper moduleMapper;
    private final ReportGenerateEngine reportGenerateEngine;
    // 新架构：企业子模板 + 系统占位符
    private final CompanyTemplateMapper companyTemplateMapper;
    private final SystemPlaceholderMapper systemPlaceholderMapper;
    // 异步生成服务（独立Bean，@Async代理生效）
    private final ReportAsyncService reportAsyncService;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    /** 分页查询报告列表 */
    public PageResult<Report> pageList(int page, int pageSize, String companyId, String status, Integer year) {
        // P1：显式 tenantId 保底过滤
        String tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<Report>()
                .eq(Report::getTenantId, tenantId)
                .orderByDesc(Report::getCreatedAt);
        if (companyId != null) wrapper.eq(Report::getCompanyId, companyId);
        if (status != null) wrapper.eq(Report::getStatus, status);
        if (year != null) wrapper.eq(Report::getYear, year);
        IPage<Report> result = reportMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return PageResult.of(result);
    }

    /** 生成报告：校验重复 + 创建 editing 记录 + 异步生成文件 */
    @OperationLog(module = "报告管理", action = "生成报告")
    @Transactional(rollbackFor = Exception.class)
    public Report generateReport(String companyId, int year, String name) {
        return generateReport(companyId, year, name, null);
    }

    /**
     * 生成报告（新版本）：支持指定企业子模板ID
     * <p>
     * 立即返回 generationStatus=pending 的报告记录，后台异步执行文件生成。
     * 前端通过 GET /reports/{id}/status 轮询进度。
     * </p>
     *
     * @param companyId         企业ID
     * @param year              报告年份
     * @param name              报告名称
     * @param companyTemplateId 指定使用的子模板ID（null=自动取最新active子模板，降级到旧template表）
     */
    @OperationLog(module = "报告管理", action = "生成报告")
    @Transactional(rollbackFor = Exception.class)
    public Report generateReport(String companyId, int year, String name, String companyTemplateId) {
        String tenantId = TenantContext.getTenantId();

        // 校验是否已有归档（history）记录：归档后不可重复生成
        Long historyCount = reportMapper.selectCount(
                new LambdaQueryWrapper<Report>()
                        .eq(Report::getTenantId, tenantId)
                        .eq(Report::getCompanyId, companyId)
                        .eq(Report::getYear, year)
                        .eq(Report::getStatus, ReportStatus.HISTORY.getCode())
        );
        if (historyCount > 0) {
            throw BizException.of(400, "该年度已存在归档报告，不可重复生成");
        }

        // 若有 editing 记录（上次生成中/失败），利用 @TableLogic 的 delete 方法自动软删除
        reportMapper.delete(new LambdaQueryWrapper<Report>()
                .eq(Report::getTenantId, tenantId)
                .eq(Report::getCompanyId, companyId)
                .eq(Report::getYear, year)
                .eq(Report::getStatus, ReportStatus.EDITING.getCode())
        );

        // 先落库，状态为 pending，立即返回
        Report report = new Report();
        report.setId(UUID.randomUUID().toString());
        report.setTenantId(tenantId);
        report.setCompanyId(companyId);
        report.setName(name != null ? name : year + "年度报告");
        report.setYear(year);
        report.setStatus(ReportStatus.EDITING.getCode());
        report.setGenerationStatus(ReportStatus.PENDING.getCode());
        report.setIsManualUpload(false);
        report.setTemplateId(companyTemplateId);
        report.setCreatedAt(LocalDateTime.now());
        reportMapper.insert(report);

        // 提交异步生成任务（事务提交后执行，tenantId 显式传参）
        final String reportId = report.getId();
        final String finalTenantId = tenantId;
        final String finalTemplateId = companyTemplateId;
        // 使用 TransactionSynchronizationManager 确保事务提交后再触发异步任务
        org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        reportAsyncService.asyncGenerateFile(reportId, companyId, year, finalTenantId, finalTemplateId);
                    }
                });

        return report;
    }

    /** 更新报告（重新生成，从 DB 记录中取 companyId/year，防止前端篡改） */
    @OperationLog(module = "报告管理", action = "更新报告")
    @Transactional(rollbackFor = Exception.class)
    public Report updateReport(String reportId) {
        return updateReport(reportId, null);
    }

    /**
     * 更新报告（支持指定子模板ID）
     * <p>
     * 立即返回 generationStatus=pending 的报告记录，后台异步重新生成文件。
     * </p>
     *
     * @param reportId          报告ID
     * @param companyTemplateId 指定使用的子模板ID（null=使用报告原绑定模板或最新激活子模板）
     */
    @OperationLog(module = "报告管理", action = "更新报告")
    @Transactional(rollbackFor = Exception.class)
    public Report updateReport(String reportId, String companyTemplateId) {
        Report report = reportMapper.selectById(reportId);
        if (report == null) throw BizException.notFound("报告");

        String tenantId = TenantContext.getTenantId();
        // 优先使用传入的 companyTemplateId，其次使用报告原绑定的 templateId
        String templateId = companyTemplateId != null ? companyTemplateId : report.getTemplateId();

        // 重置为 pending 状态
        report.setGenerationStatus(ReportStatus.PENDING.getCode());
        report.setGenerationError(null);
        report.setUpdatedAt(LocalDateTime.now());
        reportMapper.updateById(report);

        // 提交异步生成任务（事务提交后执行）
        final String companyId = report.getCompanyId();
        final int year = report.getYear();
        final String finalTenantId = tenantId;
        final String finalTemplateId = templateId;
        org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        reportAsyncService.asyncGenerateFile(reportId, companyId, year, finalTenantId, finalTemplateId);
                    }
                });

        return report;
    }

    /** 归档报告：editing → history */
    @OperationLog(module = "报告管理", action = "归档报告")
    public Report archiveReport(String id) {
        Report report = reportMapper.selectById(id);
        if (report == null) throw BizException.notFound("报告");
        if (ReportStatus.HISTORY.getCode().equals(report.getStatus())) {
            throw BizException.of(400, "报告已归档");
        }
        report.setStatus(ReportStatus.HISTORY.getCode());
        report.setUpdatedAt(LocalDateTime.now());
        reportMapper.updateById(report);
        return report;
    }

    /** 允许上传的报告文件扩展名白名单 */
    private static final java.util.Set<String> ALLOWED_REPORT_EXT =
            java.util.Set.of(".doc", ".docx", ".pdf");

    /** 允许上传的Excel文件扩展名白名单 */
    private static final java.util.Set<String> ALLOWED_EXCEL_EXT =
            java.util.Set.of(".xlsx", ".xls");

    /** 手动上传历史报告（支持同时上传清单和BVD数据文件） */
    @OperationLog(module = "报告管理", action = "上传报告")
    public Report uploadReport(MultipartFile file, MultipartFile listFile, MultipartFile bvdFile,
                               String companyId, int year, String name) {
        String tenantId = TenantContext.getTenantId();
        String originalName = file.getOriginalFilename();
        String ext = (originalName != null && originalName.contains("."))
                ? originalName.substring(originalName.lastIndexOf('.')).toLowerCase() : "";

        // P1：文件类型白名单校验
        if (!ALLOWED_REPORT_EXT.contains(ext)) {
            throw BizException.of("不支持的报告文件类型，仅允许：" + ALLOWED_REPORT_EXT);
        }

        // 校验Excel文件类型（如果上传了）
        if (listFile != null && !listFile.isEmpty()) {
            String listExt = getFileExt(listFile.getOriginalFilename());
            if (!ALLOWED_EXCEL_EXT.contains(listExt)) {
                throw BizException.of("清单数据文件类型不正确，仅允许：" + ALLOWED_EXCEL_EXT);
            }
        }
        if (bvdFile != null && !bvdFile.isEmpty()) {
            String bvdExt = getFileExt(bvdFile.getOriginalFilename());
            if (!ALLOWED_EXCEL_EXT.contains(bvdExt)) {
                throw BizException.of("BVD数据文件类型不正确，仅允许：" + ALLOWED_EXCEL_EXT);
            }
        }

        String dateDir = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy"));
        String relativePath = "reports/" + tenantId + "/" + companyId + "/" + dateDir + "/";
        String fileName = UUID.randomUUID() + ext;

        // 路径穿越防护
        Path baseDir = Paths.get(uploadDir).normalize().toAbsolutePath();
        Path fullDir = baseDir.resolve(relativePath).normalize();
        if (!fullDir.startsWith(baseDir)) throw BizException.of("非法路径");

        try {
            Files.createDirectories(fullDir);
            file.transferTo(fullDir.resolve(fileName));

            // 保存清单数据文件（如果上传了）
            String listFilePath = null;
            if (listFile != null && !listFile.isEmpty()) {
                String listExt = getFileExt(listFile.getOriginalFilename());
                String listFileName = UUID.randomUUID() + listExt;
                listFile.transferTo(fullDir.resolve(listFileName));
                listFilePath = relativePath + listFileName;
            }

            // 保存BVD数据文件（如果上传了）
            String bvdFilePath = null;
            if (bvdFile != null && !bvdFile.isEmpty()) {
                String bvdExt = getFileExt(bvdFile.getOriginalFilename());
                String bvdFileName = UUID.randomUUID() + bvdExt;
                bvdFile.transferTo(fullDir.resolve(bvdFileName));
                bvdFilePath = relativePath + bvdFileName;
            }

            Report report = new Report();
            report.setId(UUID.randomUUID().toString());
            report.setTenantId(tenantId);
            report.setCompanyId(companyId);
            report.setName(name != null ? name : originalName);
            report.setYear(year);
            report.setStatus(ReportStatus.HISTORY.getCode());
            report.setIsManualUpload(true);
            report.setFilePath(relativePath + fileName);
            report.setListFilePath(listFilePath);
            report.setBvdFilePath(bvdFilePath);
            report.setFileSize(formatSize(file.getSize()));
            report.setCreatedAt(LocalDateTime.now());
            reportMapper.insert(report);
            return report;
        } catch (IOException e) {
            throw BizException.of("报告上传失败：" + e.getMessage());
        }
    }

    /** 获取文件扩展名 */
    private String getFileExt(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
    }

    /** 删除报告（含物理文件） */
    @OperationLog(module = "报告管理", action = "删除报告")
    @Transactional
    public void deleteReport(String id) {
        Report report = reportMapper.selectById(id);
        if (report == null) throw BizException.notFound("报告");
        // 先删除物理文件
        String relPath = reportMapper.selectFilePathById(id);
        if (relPath != null) {
            try {
                Path physical = Paths.get(uploadDir).resolve(relPath).normalize();
                if (physical.startsWith(Paths.get(uploadDir).normalize())) {
                    boolean deleted = Files.deleteIfExists(physical);
                    if (!deleted) log.warn("[ReportService] 报告物理文件不存在，跳过删除: {}", physical);
                }
            } catch (IOException e) {
                log.warn("[ReportService] 删除报告物理文件失败: {}", e.getMessage());
            }
        }
        reportMapper.deleteById(id);
    }

    /**
     * 查询报告生成状态（供前端轮询）
     *
     * @return { id, generationStatus, generationError, filePath }
     */
    public Map<String, Object> getGenerationStatus(String id) {
        Report report = reportMapper.selectById(id);
        if (report == null) throw BizException.notFound("报告");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", report.getId());
        result.put("generationStatus", report.getGenerationStatus());
        result.put("generationError", report.getGenerationError());
        // 生成成功时返回 filePath（前端可据此判断是否可下载）
        result.put("filePath", ReportStatus.SUCCESS.getCode().equals(report.getGenerationStatus())
                ? report.getFilePath() : null);
        return result;
    }

    /**
     * 受鉴权报告下载：校验归属当前租户，返回下载所需信息
     */
    public FileUtil.DownloadInfo getFileForDownload(String id) {
        Report report = reportMapper.selectById(id);
        if (report == null) throw BizException.notFound("报告");
        String tenantId = TenantContext.getTenantId();
        if (!tenantId.equals(report.getTenantId())) {
            throw BizException.forbidden("无权访问该报告");
        }
        String relPath = reportMapper.selectFilePathById(id);
        if (relPath == null) throw BizException.of("报告文件路径不存在");
        Path path = Paths.get(uploadDir).resolve(relPath).normalize();
        if (!path.startsWith(Paths.get(uploadDir).normalize())) {
            throw BizException.of("非法路径");
        }
        if (!Files.exists(path)) throw BizException.of("报告文件不存在");
        // P2-06：取真实文件扩展名，避免上传 PDF 后下载变成 .pdf.docx
        String originalExt = ".docx"; // 默认
        if (relPath.contains(".")) {
            originalExt = relPath.substring(relPath.lastIndexOf('.'));
        }
        String name = (report.getName() != null && !report.getName().isBlank())
                ? report.getName() + originalExt
                : path.getFileName().toString();
        return new FileUtil.DownloadInfo(path, name);
    }

    /**
     * 解析报告模块：读取上传的历史报告 Word，提取所有一级标题，
     * 与 report_module 表中配置的模块做 LCS 相似度匹配，返回匹配结果及置信度。
     */
    public Map<String, Object> parseModules(String reportId) {
        Report report = reportMapper.selectById(reportId);
        if (report == null) throw BizException.notFound("报告");

        // 获取报告文件的绝对路径
        String filePath = getReportFilePath(reportId);
        if (filePath == null || !Files.exists(Paths.get(filePath))) {
            return Map.of(
                    "reportId", reportId,
                    "matchedModules", List.of(),
                    "unmatchedHeadings", List.of(),
                    "message", "报告文件不存在，无法解析"
            );
        }

        // 提取 Word 文档一级标题
        List<String> headings = extractWordHeadings(filePath);

        // 查询所有模块
        List<ReportModule> modules = moduleMapper.selectList(
                new LambdaQueryWrapper<ReportModule>()
                        .eq(ReportModule::getCompanyId, report.getCompanyId())
                        .orderByAsc(ReportModule::getSort)
        );

        // LCS 相似度匹配
        List<Map<String, Object>> matchedModules = new ArrayList<>();
        Set<String> matchedHeadings = new LinkedHashSet<>();

        for (String heading : headings) {
            double bestConfidence = 0;
            ReportModule bestModule = null;

            for (ReportModule module : modules) {
                double confidence = calcSimilarity(heading, module.getName());
                if (confidence > bestConfidence) {
                    bestConfidence = confidence;
                    bestModule = module;
                }
            }

            if (bestModule != null && bestConfidence >= 0.4) {
                Map<String, Object> match = new LinkedHashMap<>();
                match.put("moduleId", bestModule.getId());
                match.put("moduleName", bestModule.getName());
                match.put("moduleCode", bestModule.getCode());
                match.put("headingTitle", heading);
                match.put("confidence", Math.round(bestConfidence * 100.0) / 100.0);
                matchedModules.add(match);
                matchedHeadings.add(heading);
            }
        }

        List<String> unmatchedHeadings = headings.stream()
                .filter(h -> !matchedHeadings.contains(h))
                .toList();

        return Map.of(
                "reportId", reportId,
                "matchedModules", matchedModules,
                "unmatchedHeadings", unmatchedHeadings
        );
    }

    /**
     * 用 Apache POI 提取 Word 文档的一级标题（Heading 1 样式的段落）
     * P0 修复：精确匹配标准标题样式名，避免 contains("1") 误匹配所有含"1"字符的样式
     */
    private List<String> extractWordHeadings(String absoluteFilePath) {
        List<String> headings = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(absoluteFilePath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                String style = paragraph.getStyle();
                // 精确匹配 Heading1 / 标题 1 等标准样式名
                boolean isH1 = style != null && (
                        "1".equals(style)
                        || "Heading1".equalsIgnoreCase(style)
                        || "heading 1".equalsIgnoreCase(style)
                        || "标题 1".equals(style)
                        || "标题1".equals(style)
                );
                if (isH1) {
                    String text = paragraph.getText();
                    if (text != null && !text.isBlank()) {
                        headings.add(text.trim());
                    }
                }
            }

            // 如果没有识别到样式，降级提取字号 >= 16pt 的段落作为标题候选
            if (headings.isEmpty()) {
                for (XWPFParagraph paragraph : doc.getParagraphs()) {
                    if (!paragraph.getRuns().isEmpty()) {
                        XWPFRun run = paragraph.getRuns().get(0);
                        int fontSize = run.getFontSize();
                        if (fontSize >= 16) {
                            String text = paragraph.getText();
                            if (text != null && !text.isBlank()) {
                                headings.add(text.trim());
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("[ReportService] 读取报告 Word 文件失败: {}", e.getMessage(), e);
        }
        return headings;
    }

    /**
     * 获取报告文件绝对路径（report 表 filePath 字段默认 select=false，需额外查询）
     */
    private String getReportFilePath(String reportId) {
        // 通过自定义 SQL 取出 filePath
        String relPath = reportMapper.selectFilePathById(reportId);
        if (relPath == null) return null;
        return resolveAbsolutePath(relPath);
    }

    /**
     * 将相对路径解析为绝对路径（相对于 uploadDir）
     */
    private String resolveAbsolutePath(String relativePath) {
        if (relativePath == null) return null;
        if (Paths.get(relativePath).isAbsolute()) return relativePath;
        return uploadDir + "/" + relativePath;
    }

    /**
     * LCS（最长公共子序列）比率相似度计算
     * 先做精确包含判断（置信度 1.0），再用 LCS 长度 / max(len1, len2) 计算
     */
    private double calcSimilarity(String a, String b) {
        if (a == null || b == null) return 0;
        String sa = a.trim();
        String sb = b.trim();
        if (sa.equalsIgnoreCase(sb)) return 1.0;
        if (sa.contains(sb) || sb.contains(sa)) return 0.9;

        int lcs = lcsLength(sa, sb);
        int maxLen = Math.max(sa.length(), sb.length());
        return maxLen == 0 ? 0 : (double) lcs / maxLen;
    }

    private int lcsLength(String a, String b) {
        int m = a.length(), n = b.length();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp[m][n];
    }

    private String formatSize(long bytes) {
        return FileUtil.formatSize(bytes);
    }
}
