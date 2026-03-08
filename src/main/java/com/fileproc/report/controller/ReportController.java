package com.fileproc.report.controller;

import com.fileproc.common.PageResult;
import com.fileproc.common.R;
import com.fileproc.common.util.FileUtil;
import com.fileproc.report.entity.Report;
import com.fileproc.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 报告管理接口
 * GET  /reports                   - 分页查询
 * POST /reports/generate          - 生成报告（异步，立即返回pending）
 * POST /reports/update            - 更新报告（异步，立即返回pending）
 * GET  /reports/{id}/status       - 轮询报告生成状态
 * POST /reports/{id}/archive      - 归档
 * POST /reports/upload            - 手动上传历史报告
 * DELETE /reports/{id}            - 删除
 * POST /reports/parse-modules     - 解析报告模块
 * GET  /reports/download/{id}     - 受鉴权文件下载
 */
@Tag(name = "报告管理")
@Validated
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "分页查询报告列表")
    @PreAuthorize("hasAuthority('report:list')")
    @GetMapping
    public R<PageResult<Report>> list(
            @RequestParam(required = false) String companyId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "1") int page,
            @Max(value = 100, message = "每页最多100条") @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(reportService.pageList(page, pageSize, companyId, status, year));
    }

    @Operation(summary = "生成报告")
    @PreAuthorize("hasAuthority('report:generate')")
    @PostMapping("/generate")
    public R<Report> generate(@Valid @RequestBody GenerateReq req) {
        return R.ok(reportService.generateReport(req.getCompanyId(), req.getYear(), req.getName(), req.getCompanyTemplateId()));
    }

    @Operation(summary = "更新报告")
    @PreAuthorize("hasAuthority('report:edit')")
    @PostMapping("/update")
    public R<Report> update(@Valid @RequestBody UpdateReq req) {
        return R.ok(reportService.updateReport(req.getReportId(), req.getCompanyTemplateId()));
    }

    @Operation(summary = "轮询报告生成状态",
            description = "前端在调用 generate/update 后轮询此接口，直到 generationStatus 为 success 或 failed")
    @PreAuthorize("hasAuthority('report:list')")
    @GetMapping("/{id}/status")
    public R<Map<String, Object>> getStatus(@PathVariable String id) {
        return R.ok(reportService.getGenerationStatus(id));
    }

    @Operation(summary = "归档报告")
    @PreAuthorize("hasAuthority('report:edit')")
    @PostMapping("/{id}/archive")
    public R<Report> archive(@PathVariable String id) {
        return R.ok(reportService.archiveReport(id));
    }

    @Operation(summary = "手动上传历史报告")
    @PreAuthorize("hasAuthority('report:create')")
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public R<Report> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam String companyId,
            @Min(value = 2000, message = "年度不能早于2000年")
            @Max(value = 2100, message = "年度不能晚于2100年")
            @RequestParam int year,
            @RequestParam(required = false) String name) {
        return R.ok(reportService.uploadReport(file, companyId, year, name));
    }

    @Operation(summary = "删除报告")
    @PreAuthorize("hasAuthority('report:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        reportService.deleteReport(id);
        return R.ok();
    }

    @Operation(summary = "解析报告模块")
    @PreAuthorize("hasAuthority('report:list')")
    @PostMapping("/parse-modules")
    public R<Map<String, Object>> parseModules(@Valid @RequestBody ParseModulesReq req) {
        return R.ok(reportService.parseModules(req.getReportId()));
    }

    @Operation(summary = "下载报告文件（受鉴权，含租户归属校验）")
    @PreAuthorize("hasAuthority('report:list')")
    @GetMapping("/download/{id}")
    public void download(@PathVariable String id, HttpServletResponse response) throws IOException {
        FileUtil.DownloadInfo info = reportService.getFileForDownload(id);
        Path filePath = info.getPath();
        String fileName = URLEncoder.encode(info.getName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + fileName);
        response.setContentLengthLong(Files.size(filePath));
        try (InputStream in = Files.newInputStream(filePath);
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    @Data
    static class GenerateReq {
        @NotBlank(message = "companyId不能为空")
        @Size(max = 36)
        private String companyId;
        @NotNull(message = "年度不能为空")
        private Integer year;
        @Size(max = 100)
        private String name;
        /** 指定使用的企业子模板ID（可选，不传则自动取最新激活子模板） */
        @Size(max = 36)
        private String companyTemplateId;
    }

    @Data
    static class UpdateReq {
        @NotBlank(message = "reportId不能为空")
        @Size(max = 36)
        private String reportId;
        /** 更新时指定使用的企业子模板ID（可选） */
        @Size(max = 36)
        private String companyTemplateId;
    }

    @Data
    static class ParseModulesReq {
        @NotBlank(message = "reportId不能为空")
        @Size(max = 36)
        private String reportId;
    }
}
