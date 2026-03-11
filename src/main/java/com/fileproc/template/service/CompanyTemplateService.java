package com.fileproc.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fileproc.common.BizException;
import com.fileproc.common.PageResult;
import com.fileproc.common.TenantContext;
import com.fileproc.common.enums.ReportStatus;
import com.fileproc.common.util.FileUtil;
import com.fileproc.datafile.entity.DataFile;
import com.fileproc.datafile.mapper.DataFileMapper;
import com.fileproc.report.entity.Report;
import com.fileproc.report.mapper.ReportMapper;
import com.fileproc.report.service.ReportGenerateEngine;
import com.fileproc.template.entity.CompanyTemplate;
import com.fileproc.template.entity.Placeholder;
import com.fileproc.template.entity.SystemPlaceholder;
import com.fileproc.template.mapper.CompanyTemplateMapper;
import com.fileproc.template.mapper.SystemPlaceholderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 企业子模板管理 Service
 * <p>
 * 负责：
 * 1. 子模板列表查询（分页）
 * 2. 子模板下载
 * 3. 子模板在线编辑内容读写
 * 4. 子模板归档/删除
 * （反向生成逻辑在 ReverseTemplateEngine 和 CompanyTemplateController 协调）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyTemplateService {

    private final CompanyTemplateMapper companyTemplateMapper;
    private final CompanyTemplatePlaceholderService placeholderService;
    private final SystemPlaceholderMapper systemPlaceholderMapper;
    private final DataFileMapper dataFileMapper;
    private final ReportMapper reportMapper;
    private final ReportGenerateEngine reportGenerateEngine;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    // ========== 查询 ==========

    /**
     * 分页查询企业子模板列表
     */
    public PageResult<CompanyTemplate> pageList(int page, int pageSize, String companyId, String status) {
        String tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<CompanyTemplate> wrapper = new LambdaQueryWrapper<CompanyTemplate>()
                .eq(CompanyTemplate::getTenantId, tenantId)
                .orderByDesc(CompanyTemplate::getCreatedAt);
        if (companyId != null) wrapper.eq(CompanyTemplate::getCompanyId, companyId);
        if (status != null) wrapper.eq(CompanyTemplate::getStatus, status);
        IPage<CompanyTemplate> result = companyTemplateMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return PageResult.of(result);
    }

    /**
     * 查询企业所有激活状态子模板
     */
    public List<CompanyTemplate> listActive(String companyId) {
        String tenantId = TenantContext.getTenantId();
        return companyTemplateMapper.selectActiveByCompany(companyId, tenantId);
    }

    /**
     * 根据ID查询子模板（含 filePath）
     */
    public CompanyTemplate getByIdWithFilePath(String id) {
        CompanyTemplate template = companyTemplateMapper.selectByIdWithFilePath(id);
        if (template == null) throw BizException.notFound("子模板");
        checkTenant(template);
        return template;
    }

    /**
     * 根据ID查询子模板（不含 filePath，供展示用）
     */
    public CompanyTemplate getById(String id) {
        CompanyTemplate template = companyTemplateMapper.selectById(id);
        if (template == null) throw BizException.notFound("子模板");
        checkTenant(template);
        return template;
    }

    // ========== 下载 ==========

    /**
     * 下载企业子模板文件
     */
    public FileUtil.DownloadInfo download(String id) {
        CompanyTemplate template = getByIdWithFilePath(id);
        String filePath = template.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            throw BizException.of(400, "子模板文件尚未生成");
        }
        Path path = Paths.get(uploadDir, filePath).normalize().toAbsolutePath();
        if (!Files.exists(path)) {
            throw BizException.of(400, "子模板文件不存在，请重新生成");
        }
        return new FileUtil.DownloadInfo(path, template.getName() + ".docx");
    }

    // ========== 在线编辑 ==========

    /**
     * 获取子模板内容（用于在线编辑，返回文件字节流供前端渲染）
     * 前端可使用 docx 预览库（如 docx-preview）渲染 Word 内容
     */
    public Resource getContentResource(String id) {
        CompanyTemplate template = getByIdWithFilePath(id);
        Path path = Paths.get(uploadDir, template.getFilePath()).normalize().toAbsolutePath();
        if (!Files.exists(path)) {
            throw BizException.of(400, "子模板文件不存在");
        }
        return new FileSystemResource(path);
    }

    /**
     * 保存在线编辑后上传的子模板文件（覆盖更新，保留旧版本记录）
     * 用户通过前端编辑后上传 docx 文件，系统替换存储并更新记录
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyTemplate saveEditedContent(String id, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.of(400, "上传文件不能为空");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".docx")) {
            throw BizException.of(400, "仅支持 .docx 格式");
        }

        CompanyTemplate template = getByIdWithFilePath(id);

        // 生成新文件路径（追加 _edited 后缀区分版本）
        String tenantId = TenantContext.getTenantId();
        String companyId = template.getCompanyId();
        String relDir = "company-templates/" + tenantId + "/" + companyId + "/";
        String newFileName = UUID.randomUUID() + ".docx";

        Path baseDir = Paths.get(uploadDir).normalize().toAbsolutePath();
        Path fullDir = baseDir.resolve(relDir).normalize();
        if (!fullDir.startsWith(baseDir)) throw BizException.of("非法路径");

        try {
            Files.createDirectories(fullDir);
            file.transferTo(fullDir.resolve(newFileName));
        } catch (IOException e) {
            throw BizException.of("文件保存失败：" + e.getMessage());
        }

        String newRelPath = relDir + newFileName;
        long fileSize;
        try {
            fileSize = Files.size(fullDir.resolve(newFileName));
        } catch (IOException e) {
            fileSize = 0L;
        }

        // 更新数据库记录
        template.setFilePath(newRelPath);
        template.setFileSize(FileUtil.formatSize(fileSize));
        template.setUpdatedAt(LocalDateTime.now());
        companyTemplateMapper.updateById(template);

        log.info("[CompanyTemplateService] 子模板在线编辑已保存: id={}, path={}", id, newRelPath);

        // 返回不含 filePath 的对象
        template.setFilePath(null);
        return template;
    }

    // ========== 归档/删除 ==========

    /**
     * 归档子模板
     * <p>
     * 流程：
     * 1. 使用该子模板 + 对应年度数据生成最终报告
     * 2. 将该模板 is_current 设为 false
     * 3. 将该模板 status 设为 archived
     * 4. 物理删除子模板文件
     * 5. 逻辑删除子模板记录和占位符状态记录
     * </p>
     *
     * @param id 子模板ID
     * @return 生成的报告ID
     */
    @Transactional(rollbackFor = Exception.class)
    public String archive(String id) {
        CompanyTemplate template = getByIdWithFilePath(id);
        if ("archived".equals(template.getStatus())) {
            throw BizException.of(400, "子模板已归档");
        }

        // 1. 使用该子模板 + 对应年度数据生成最终报告
        String reportId = generateFinalReport(template);

        // 2. 更新模板状态：is_current=false, status=archived
        template.setIsCurrent(false);
        template.setStatus("archived");
        template.setUpdatedAt(LocalDateTime.now());
        companyTemplateMapper.updateById(template);
        log.info("[CompanyTemplateService] 子模板状态已更新为归档: templateId={}", id);

        // 3. 物理删除子模板文件
        deleteTemplateFile(template.getFilePath());

        // 4. 删除占位符状态记录
        placeholderService.deleteByTemplateId(id);

        // 5. 逻辑删除子模板记录
        companyTemplateMapper.deleteById(id);

        log.info("[CompanyTemplateService] 子模板归档完成: templateId={}, reportId={}", id, reportId);
        return reportId;
    }

    /**
     * 使用子模板生成最终报告
     */
    private String generateFinalReport(CompanyTemplate template) {
        // 获取系统占位符规则
        List<SystemPlaceholder> systemPlaceholders =
                systemPlaceholderMapper.selectByTemplateId(template.getSystemTemplateId());
        List<Placeholder> placeholders = systemPlaceholders.stream()
                .map(this::toPlaceholder)
                .toList();

        // 获取该年度的数据文件
        List<DataFile> dataFiles = dataFileMapper.selectWithFilePathByCompanyAndYear(
                template.getCompanyId(), template.getYear());

        // 构造输出路径
        String tenantId = template.getTenantId();
        String companyId = template.getCompanyId();
        String dateDir = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy"));
        String relativeDir = "reports/" + tenantId + "/" + companyId + "/" + dateDir + "/";
        String reportId = UUID.randomUUID().toString();
        String fileName = reportId + ".docx";
        String absoluteOutputPath = uploadDir + "/" + relativeDir + fileName;

        try {
            Files.createDirectories(Paths.get(absoluteOutputPath).getParent());

            // 调用生成引擎
            reportGenerateEngine.generate(
                    resolveAbsPath(template.getFilePath()),
                    placeholders,
                    dataFiles.stream()
                            .peek(df -> df.setFilePath(resolveAbsPath(df.getFilePath())))
                            .toList(),
                    absoluteOutputPath
            );

            long fileSize = Files.size(Paths.get(absoluteOutputPath));

            // 创建报告记录（HISTORY状态）
            Report report = new Report();
            report.setId(reportId);
            report.setTenantId(tenantId);
            report.setCompanyId(companyId);
            report.setName(template.getYear() + "年度报告");
            report.setYear(template.getYear());
            report.setStatus(ReportStatus.HISTORY.getCode());
            report.setGenerationStatus(ReportStatus.SUCCESS.getCode());
            report.setTemplateId(template.getId());
            report.setFilePath(relativeDir + fileName);
            report.setFileSize(formatSize(fileSize));
            report.setIsManualUpload(false);
            report.setCreatedAt(LocalDateTime.now());
            reportMapper.insert(report);

            return reportId;
        } catch (Exception e) {
            log.error("[CompanyTemplateService] 生成最终报告失败: templateId={}, error={}",
                    template.getId(), e.getMessage(), e);
            throw BizException.of("生成最终报告失败：" + e.getMessage());
        }
    }

    /**
     * 物理删除子模板文件
     */
    private void deleteTemplateFile(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        try {
            Path path = Paths.get(uploadDir, filePath).normalize().toAbsolutePath();
            if (path.startsWith(Paths.get(uploadDir).normalize()) && Files.exists(path)) {
                Files.delete(path);
                log.info("[CompanyTemplateService] 子模板文件已删除: {}", path);
            }
        } catch (IOException e) {
            log.warn("[CompanyTemplateService] 删除子模板文件失败: {}", e.getMessage());
        }
    }

    // ========== 私有辅助方法 ==========

    /**
     * 将 SystemPlaceholder 转换为 Placeholder
     */
    private Placeholder toPlaceholder(SystemPlaceholder sp) {
        Placeholder ph = new Placeholder();
        ph.setId(sp.getId());
        ph.setName(sp.getName());
        ph.setType(sp.getType());
        ph.setDataSource(sp.getDataSource());
        ph.setSourceSheet(sp.getSourceSheet());
        ph.setSourceField(sp.getSourceField());
        ph.setChartType(sp.getChartType());
        ph.setDescription(sp.getDescription() != null ? sp.getDescription() : "");
        return ph;
    }

    private String resolveAbsPath(String relativePath) {
        return Paths.get(uploadDir).resolve(relativePath).normalize().toString();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /**
     * 逻辑删除子模板
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        CompanyTemplate template = getById(id);
        companyTemplateMapper.deleteById(template.getId());
    }

    // ========== 包内方法（供 ReverseTemplateEngine 调用保存结果）==========

    /**
     * 保存反向生成的子模板记录
     * <p>
     * 新子模板设为 active 且 isCurrent=true，不自动归档旧模板（允许多个 active，前端手动切换）
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyTemplate saveReverseResult(String tenantId, String companyId, String systemTemplateId,
                                              String name, int year, String sourceReportId,
                                              String filePath, long fileSizeBytes) {
        LocalDateTime now = LocalDateTime.now();

        CompanyTemplate template = new CompanyTemplate();
        template.setId(UUID.randomUUID().toString());
        template.setTenantId(tenantId);
        template.setCompanyId(companyId);
        template.setSystemTemplateId(systemTemplateId);
        template.setName(name);
        template.setYear(year);
        template.setSourceReportId(sourceReportId);
        template.setFilePath(filePath);
        template.setFileSize(FileUtil.formatSize(fileSizeBytes));
        template.setStatus("active");
        template.setIsCurrent(true);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template.setDeleted(0);
        companyTemplateMapper.insert(template);

        log.info("[CompanyTemplateService] 企业子模板已保存: id={}, company={}, year={}, isCurrent=true",
                template.getId(), companyId, year);

        // 返回不含 filePath
        template.setFilePath(null);
        return template;
    }

    /**
     * 设为当前使用版本
     * <p>
     * 将指定子模板设为 isCurrent=true，同时将该企业同一年度的其他子模板 isCurrent 设为 false
     * 注意：此方法只修改 isCurrent 字段，不修改 status 字段（归档操作独立进行）
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyTemplate setActive(String id) {
        CompanyTemplate template = getByIdWithFilePath(id);

        // 检查模板状态，已归档的模板不能设为当前使用
        if ("archived".equals(template.getStatus())) {
            throw BizException.of(400, "已归档的子模板不能设为当前使用");
        }

        // 如果已经是当前使用，直接返回
        if (Boolean.TRUE.equals(template.getIsCurrent())) {
            log.info("[CompanyTemplateService] 子模板已是当前使用版本: id={}", id);
            template.setFilePath(null);
            return template;
        }

        String tenantId = TenantContext.getTenantId();
        String companyId = template.getCompanyId();
        Integer year = template.getYear();

        LocalDateTime now = LocalDateTime.now();

        // 1. 将该企业同一年度的其他子模板 is_current 设为 false
        int clearedCount = companyTemplateMapper.clearCurrentByCompanyAndYear(companyId, tenantId, year);
        if (clearedCount > 0) {
            log.info("[CompanyTemplateService] 同年度其他子模板已取消当前使用: company={}, year={}, count={}",
                    companyId, year, clearedCount);
        }

        // 2. 将当前子模板设为 isCurrent=true
        template.setIsCurrent(true);
        template.setUpdatedAt(now);
        companyTemplateMapper.updateById(template);

        log.info("[CompanyTemplateService] 子模板已设为当前使用版本: id={}, company={}, year={}",
                id, companyId, year);

        // 返回不含 filePath
        template.setFilePath(null);
        return template;
    }

    // ========== 私有方法 ==========

    private void checkTenant(CompanyTemplate template) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.equals(template.getTenantId())) {
            throw BizException.forbidden("无权访问该子模板");
        }
    }
}
