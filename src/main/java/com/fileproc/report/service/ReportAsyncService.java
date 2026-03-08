package com.fileproc.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fileproc.common.enums.ReportStatus;
import com.fileproc.datafile.entity.DataFile;
import com.fileproc.datafile.mapper.DataFileMapper;
import com.fileproc.report.entity.Report;
import com.fileproc.report.mapper.ReportMapper;
import com.fileproc.template.entity.CompanyTemplate;
import com.fileproc.template.entity.Placeholder;
import com.fileproc.template.entity.SystemPlaceholder;
import com.fileproc.template.entity.Template;
import com.fileproc.template.mapper.CompanyTemplateMapper;
import com.fileproc.template.mapper.PlaceholderMapper;
import com.fileproc.template.mapper.SystemPlaceholderMapper;
import com.fileproc.template.mapper.TemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 报告异步生成服务
 * <p>
 * 必须独立为一个 Spring Bean，@Async 才能通过代理生效（不能在 ReportService 内自调用）。
 * tenantId 必须显式传参，因为 @Async 是新线程，ThreadLocal（TenantContext）不会继承。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportAsyncService {

    private final ReportMapper reportMapper;
    private final DataFileMapper dataFileMapper;
    private final TemplateMapper templateMapper;
    private final PlaceholderMapper placeholderMapper;
    private final CompanyTemplateMapper companyTemplateMapper;
    private final SystemPlaceholderMapper systemPlaceholderMapper;
    private final ReportGenerateEngine reportGenerateEngine;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    /**
     * 异步生成报告文件
     * <p>
     * 执行流程：
     * 1. 更新 generationStatus = processing
     * 2. 优先使用企业子模板（company_template），降级到旧 template 表
     * 3. 完成后更新 generationStatus = success/failed + filePath/fileSize
     * </p>
     *
     * @param reportId          报告ID
     * @param companyId         企业ID
     * @param year              报告年份
     * @param tenantId          租户ID（显式传入，不依赖 ThreadLocal）
     * @param companyTemplateId 指定的子模板ID（可为null，自动取最新激活子模板）
     */
    @Async("reportExecutor")
    public void asyncGenerateFile(String reportId, String companyId, int year,
                                   String tenantId, String companyTemplateId) {
        log.info("[ReportAsyncService] 开始异步生成报告: reportId={}, companyId={}, year={}", reportId, companyId, year);

        // 重新从DB查询最新report记录（主线程已commit，这里能读到）
        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            log.warn("[ReportAsyncService] 报告记录不存在，跳过生成: reportId={}", reportId);
            return;
        }

        // 1. 更新状态为 processing
        report.setGenerationStatus(ReportStatus.PROCESSING.getCode());
        report.setUpdatedAt(LocalDateTime.now());
        reportMapper.updateById(report);

        // 2. 优先走新架构（enterprise template）
        CompanyTemplate companyTemplate = null;
        if (companyTemplateId != null) {
            companyTemplate = companyTemplateMapper.selectByIdWithFilePath(companyTemplateId);
        }
        if (companyTemplate == null) {
            companyTemplate = companyTemplateMapper.selectLatestActiveByCompany(companyId, tenantId);
        }

        if (companyTemplate != null && companyTemplate.getFilePath() != null) {
            generateByCompanyTemplate(report, companyTemplate, companyId, year, tenantId);
        } else {
            // 3. 降级：旧 template 表
            generateByOldTemplate(report, companyId, year, tenantId);
        }
    }

    // ========== 私有方法 ==========

    private void generateByCompanyTemplate(Report report, CompanyTemplate companyTemplate,
                                            String companyId, int year, String tenantId) {
        List<SystemPlaceholder> systemPlaceholders =
                systemPlaceholderMapper.selectByTemplateId(companyTemplate.getSystemTemplateId());

        List<Placeholder> placeholders = systemPlaceholders.stream()
                .map(this::toPlaceholder)
                .toList();

        List<DataFile> dataFiles = dataFileMapper.selectWithFilePathByCompanyAndYear(companyId, year);

        String relativeDir = buildRelativeDir(tenantId, companyId);
        String fileName = report.getId() + ".docx";
        String absoluteOutputPath = uploadDir + "/" + relativeDir + fileName;

        try {
            Files.createDirectories(Paths.get(absoluteOutputPath).getParent());
            reportGenerateEngine.generate(
                    resolveAbsPath(companyTemplate.getFilePath()),
                    placeholders,
                    dataFiles.stream()
                            .peek(df -> df.setFilePath(resolveAbsPath(df.getFilePath())))
                            .toList(),
                    absoluteOutputPath
            );

            long fileSize = Files.size(Paths.get(absoluteOutputPath));
            report.setFilePath(relativeDir + fileName);
            report.setFileSize(formatSize(fileSize));
            report.setTemplateId(companyTemplate.getId());
            report.setGenerationStatus(ReportStatus.SUCCESS.getCode());
            report.setGenerationError(null);
            report.setUpdatedAt(LocalDateTime.now());
            reportMapper.updateById(report);

            log.info("[ReportAsyncService] 报告生成成功（新架构）: {}", absoluteOutputPath);
        } catch (Exception e) {
            log.error("[ReportAsyncService] 报告生成失败（新架构）: reportId={}, err={}", report.getId(), e.getMessage(), e);
            markFailed(report, e.getMessage());
        }
    }

    private void generateByOldTemplate(Report report, String companyId, int year, String tenantId) {
        Template template = templateMapper.selectActiveWithFilePath(companyId, year, tenantId);
        if (template == null || template.getFilePath() == null) {
            log.warn("[ReportAsyncService] 未找到企业 {} 年度 {} 的子模板，跳过生成", companyId, year);
            // 无模板时保持 pending 状态（不算失败）
            report.setGenerationStatus(ReportStatus.PENDING.getCode());
            report.setGenerationError("未找到可用模板");
            report.setUpdatedAt(LocalDateTime.now());
            reportMapper.updateById(report);
            return;
        }

        List<Placeholder> placeholders = placeholderMapper.selectList(
                new LambdaQueryWrapper<Placeholder>()
                        .eq(Placeholder::getCompanyId, companyId)
        );

        List<DataFile> dataFiles = dataFileMapper.selectWithFilePathByCompanyAndYear(companyId, year);

        String relativeDir = buildRelativeDir(tenantId, companyId);
        String fileName = report.getId() + ".docx";
        String absoluteOutputPath = uploadDir + "/" + relativeDir + fileName;

        try {
            Files.createDirectories(Paths.get(absoluteOutputPath).getParent());
            reportGenerateEngine.generate(
                    resolveAbsPath(template.getFilePath()),
                    placeholders,
                    dataFiles.stream()
                            .peek(df -> df.setFilePath(resolveAbsPath(df.getFilePath())))
                            .toList(),
                    absoluteOutputPath
            );

            long fileSize = Files.size(Paths.get(absoluteOutputPath));
            report.setFilePath(relativeDir + fileName);
            report.setFileSize(formatSize(fileSize));
            report.setGenerationStatus(ReportStatus.SUCCESS.getCode());
            report.setGenerationError(null);
            report.setUpdatedAt(LocalDateTime.now());
            reportMapper.updateById(report);

            log.info("[ReportAsyncService] 报告生成成功（旧架构）: {}", absoluteOutputPath);
        } catch (Exception e) {
            log.error("[ReportAsyncService] 报告生成失败（旧架构）: reportId={}, err={}", report.getId(), e.getMessage(), e);
            markFailed(report, e.getMessage());
        }
    }

    private void markFailed(Report report, String errMsg) {
        report.setGenerationStatus(ReportStatus.FAILED.getCode());
        report.setGenerationError(errMsg != null && errMsg.length() > 500
                ? errMsg.substring(0, 500) : errMsg);
        report.setUpdatedAt(LocalDateTime.now());
        reportMapper.updateById(report);
    }

    private String buildRelativeDir(String tenantId, String companyId) {
        String year = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy"));
        return "reports/" + tenantId + "/" + companyId + "/" + year + "/";
    }

    private String resolveAbsPath(String relativePath) {
        return Paths.get(uploadDir).resolve(relativePath).normalize().toString();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

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
}
