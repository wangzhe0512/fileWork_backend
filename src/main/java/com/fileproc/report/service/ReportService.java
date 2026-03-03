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
import com.fileproc.template.entity.Placeholder;
import com.fileproc.template.entity.ReportModule;
import com.fileproc.template.entity.Template;
import com.fileproc.template.mapper.ModuleMapper;
import com.fileproc.template.mapper.PlaceholderMapper;
import com.fileproc.template.mapper.TemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
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

    /** 生成报告：校验重复 + 创建 editing 记录 + 调用引擎生成文件 */
    @OperationLog(module = "报告管理", action = "生成报告")
    @Transactional(rollbackFor = Exception.class)
    public Report generateReport(String companyId, int year, String name) {
        String tenantId = TenantContext.getTenantId();

        // 校验是否已有 history 记录
        Long historyCount = reportMapper.selectCount(
                new LambdaQueryWrapper<Report>()
                        .eq(Report::getCompanyId, companyId)
                        .eq(Report::getYear, year)
                        .eq(Report::getStatus, ReportStatus.HISTORY.getCode())
        );
        if (historyCount > 0) {
            throw BizException.of(400, "该年度已存在归档报告，不可重复生成");
        }

        // 校验是否有 editing 记录
        Long editingCount = reportMapper.selectCount(
                new LambdaQueryWrapper<Report>()
                        .eq(Report::getCompanyId, companyId)
                        .eq(Report::getYear, year)
                        .eq(Report::getStatus, ReportStatus.EDITING.getCode())
        );
        if (editingCount > 0) {
            throw BizException.of(400, "该年度已有编辑中的报告，请先归档或删除");
        }

        Report report = new Report();
        report.setId(UUID.randomUUID().toString());
        report.setTenantId(tenantId);
        report.setCompanyId(companyId);
        report.setName(name != null ? name : year + "年度报告");
        report.setYear(year);
        report.setStatus(ReportStatus.EDITING.getCode());
        report.setIsManualUpload(false);
        report.setCreatedAt(LocalDateTime.now());
        reportMapper.insert(report);

        // 调用引擎生成 Word 文件（若模板存在则执行，否则仅保留记录）
        tryGenerateFile(report, companyId, year, tenantId);

        return report;
    }

    /**
     * 尝试调用引擎生成报告文件，更新 filePath/fileSize 到 DB。
     * 若模板不存在则跳过（不抛异常，保持报告记录可用）。
     */
    private void tryGenerateFile(Report report, String companyId, int year, String tenantId) {
        Template template = templateMapper.selectActiveWithFilePath(companyId, year, tenantId);
        if (template == null || template.getFilePath() == null) {
            log.warn("[ReportService] 未找到企业 {} 年度 {} 的 active 模板，跳过文件生成", companyId, year);
            return;
        }

        // 查询占位符
        List<Placeholder> placeholders = placeholderMapper.selectList(
                new LambdaQueryWrapper<Placeholder>()
                        .eq(Placeholder::getCompanyId, companyId)
        );

        // 查询带 filePath 的数据文件
        List<DataFile> dataFiles = dataFileMapper.selectWithFilePathByCompanyAndYear(companyId, year);

        // 构造输出路径
        String dateDir = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy"));
        String relativeDir = "reports/" + tenantId + "/" + companyId + "/" + dateDir + "/";
        String fileName = report.getId() + ".docx";
        String absoluteOutputPath = uploadDir + "/" + relativeDir + fileName;

        try {
            reportGenerateEngine.generate(
                    resolveAbsolutePath(template.getFilePath()),
                    placeholders,
                    dataFiles.stream()
                            .peek(df -> df.setFilePath(resolveAbsolutePath(df.getFilePath())))
                            .toList(),
                    absoluteOutputPath
            );

            // 更新 report 记录的文件信息
            long fileSize = Files.size(Paths.get(absoluteOutputPath));
            report.setFilePath(relativeDir + fileName);
            report.setFileSize(formatSize(fileSize));
            report.setGenerationStatus(ReportStatus.SUCCESS.getCode());
            report.setGenerationError(null);
            report.setUpdatedAt(LocalDateTime.now());
            reportMapper.updateById(report);

            log.info("[ReportService] 报告文件已生成: {}", absoluteOutputPath);
        } catch (Exception e) {
            log.error("[ReportService] 报告文件生成失败: {}", e.getMessage(), e);
            // 记录失败状态，让前端可感知并决策重试，不抛出异常保持接口可用
            String errMsg = e.getMessage();
            report.setGenerationStatus(ReportStatus.FAILED.getCode());
            report.setGenerationError(errMsg != null && errMsg.length() > 500
                    ? errMsg.substring(0, 500) : errMsg);
            reportMapper.updateById(report);
        }
    }

    /** 更新报告（替换为新版本，从 DB 记录中取 companyId/year，防止前端篡改） */
    @OperationLog(module = "报告管理", action = "更新报告")
    @Transactional(rollbackFor = Exception.class)
    public Report updateReport(String reportId) {
        Report report = reportMapper.selectById(reportId);
        if (report == null) throw BizException.notFound("报告");

        String tenantId = TenantContext.getTenantId();
        // 从报告自身取 companyId/year，不依赖前端传值
        tryGenerateFile(report, report.getCompanyId(), report.getYear(), tenantId);

        report.setUpdatedAt(LocalDateTime.now());
        reportMapper.updateById(report);
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

    /** 手动上传历史报告 */
    @OperationLog(module = "报告管理", action = "上传报告")
    public Report uploadReport(MultipartFile file, String companyId, int year, String name) {
        String tenantId = TenantContext.getTenantId();
        String originalName = file.getOriginalFilename();
        String ext = (originalName != null && originalName.contains("."))
                ? originalName.substring(originalName.lastIndexOf('.')).toLowerCase() : "";

        // P1：文件类型白名单校验
        if (!ALLOWED_REPORT_EXT.contains(ext)) {
            throw BizException.of("不支持的报告文件类型，仅允许：" + ALLOWED_REPORT_EXT);
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

            Report report = new Report();
            report.setId(UUID.randomUUID().toString());
            report.setTenantId(tenantId);
            report.setCompanyId(companyId);
            report.setName(name != null ? name : originalName);
            report.setYear(year);
            report.setStatus(ReportStatus.HISTORY.getCode());
            report.setIsManualUpload(true);
            report.setFilePath(relativePath + fileName);
            report.setFileSize(formatSize(file.getSize()));
            report.setCreatedAt(LocalDateTime.now());
            reportMapper.insert(report);
            return report;
        } catch (IOException e) {
            throw BizException.of("报告上传失败：" + e.getMessage());
        }
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
