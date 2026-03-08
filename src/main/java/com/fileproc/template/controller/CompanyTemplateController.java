package com.fileproc.template.controller;

import com.fileproc.common.BizException;
import com.fileproc.common.PageResult;
import com.fileproc.common.R;
import com.fileproc.common.TenantContext;
import com.fileproc.common.util.FileUtil;
import com.fileproc.datafile.entity.DataFile;
import com.fileproc.datafile.mapper.DataFileMapper;
import com.fileproc.report.service.ReverseTemplateEngine;
import com.fileproc.template.entity.CompanyTemplate;
import com.fileproc.template.entity.SystemPlaceholder;
import com.fileproc.template.entity.SystemTemplate;
import com.fileproc.template.service.CompanyTemplatePlaceholderService;
import com.fileproc.template.service.CompanyTemplateService;
import com.fileproc.template.service.SystemTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 企业子模板管理接口
 * <p>
 * （context-path=/api，以下为相对路径）
 * POST /company-template/reverse-generate              — 反向生成企业子模板
 * GET  /company-template                               — 企业子模板列表（分页）
 * GET  /company-template/active                        — 企业激活子模板列表
 * GET  /company-template/{id}                          — 子模板详情
 * GET  /company-template/{id}/download                 — 下载子模板文件
 * GET  /company-template/{id}/content                  — 获取子模板文件流（直接下载/inline预览）
 * GET  /company-template/{id}/content-url              — 获取子模板可访问URL（供 OnlyOffice 使用）
 * PUT  /company-template/{id}/content                  — 保存在线编辑后的子模板
 * POST /company-template/{id}/confirm-placeholders     — 确认待确认占位符
 * PUT  /company-template/{id}/archive                  — 归档子模板
 * DELETE /company-template/{id}                        — 删除子模板
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/company-template")
@RequiredArgsConstructor
public class CompanyTemplateController {

    private final CompanyTemplateService companyTemplateService;
    private final CompanyTemplatePlaceholderService placeholderService;
    private final SystemTemplateService systemTemplateService;
    private final ReverseTemplateEngine reverseTemplateEngine;
    private final DataFileMapper dataFileMapper;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    /**
     * 反向生成企业子模板
     * <p>
     * 输入：历史报告Word + 年度（自动查询对应清单Excel + BVD Excel）
     * 输出：企业子模板（数据替换为占位符，保留排版）+ 待确认占位符列表
     * </p>
     */
    @PostMapping("/reverse-generate")
    public R<Map<String, Object>> reverseGenerate(
            @RequestPart("historicalReport") MultipartFile historicalReport,
            @RequestParam("companyId") String companyId,
            @RequestParam("year") int year,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "sourceReportId", required = false) String sourceReportId) {

        validateWordFile(historicalReport);

        String tenantId = TenantContext.getTenantId();

        // 获取系统标准模板和占位符规则
        SystemTemplate systemTemplate = systemTemplateService.getActiveWithPaths();
        List<SystemPlaceholder> placeholders = systemTemplateService.listPlaceholders(systemTemplate.getId());
        if (placeholders.isEmpty()) {
            throw BizException.of(400, "系统标准模板尚未解析占位符规则，请重新初始化");
        }

        // 根据年度自动查询清单模板和BVD数据
        List<DataFile> dataFiles = dataFileMapper.selectWithFilePathByCompanyAndYear(companyId, year);
        String listPath = null;
        String bvdPath = null;
        
        for (DataFile dataFile : dataFiles) {
            if ("list".equals(dataFile.getType())) {
                listPath = dataFile.getFilePath();
            } else if ("bvd".equals(dataFile.getType())) {
                bvdPath = dataFile.getFilePath();
            }
        }
        
        // 检查数据是否存在
        if (listPath == null || bvdPath == null) {
            throw BizException.of(400, "该年度清单模板或BVD数据缺失，请先到数据管理上传");
        }

        // 保存上传文件到临时目录
        String tmpDir = "tmp/" + UUID.randomUUID() + "/";
        String histPath = saveTempFile(historicalReport, tmpDir, "history.docx");

        // 构建输出路径
        String outDir = "company-templates/" + tenantId + "/" + companyId + "/";
        String outFileName = UUID.randomUUID() + ".docx";
        String outRelPath = outDir + outFileName;
        String outAbsPath = uploadDir + "/" + outRelPath;

        try {
            Files.createDirectories(Paths.get(outAbsPath).getParent());
        } catch (IOException e) {
            throw BizException.of("创建输出目录失败：" + e.getMessage());
        }

        // 执行反向生成
        ReverseTemplateEngine.ReverseResult result = reverseTemplateEngine.reverse(
                toAbsPath(histPath),
                uploadDir + "/" + listPath,
                uploadDir + "/" + bvdPath,
                placeholders,
                outAbsPath
        );

        // 保存子模板记录到数据库
        String templateName = name != null ? name : (year + "年子模板");
        long fileSize = 0L;
        try {
            fileSize = Files.size(Paths.get(outAbsPath));
        } catch (IOException ignored) {}

        CompanyTemplate companyTemplate = companyTemplateService.saveReverseResult(
                tenantId, companyId, systemTemplate.getId(),
                templateName, year, sourceReportId, outRelPath, fileSize
        );

        // 初始化占位符状态记录（用于后续确认流程）
        if (!result.getPendingConfirmList().isEmpty()) {
            placeholderService.initPlaceholders(companyTemplate.getId(), result.getPendingConfirmList());
        }

        // 清理临时文件
        cleanTempDir(tmpDir);

        return R.ok("反向生成完成", Map.of(
                "template", companyTemplate,
                "matchedCount", result.getMatchedCount(),
                "pendingConfirmList", result.getPendingConfirmList()
        ));
    }

    /**
     * 分页查询企业子模板列表
     */
    @GetMapping
    public R<PageResult<CompanyTemplate>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(value = "companyId", required = false) String companyId,
            @RequestParam(value = "status", required = false) String status) {
        return R.ok(companyTemplateService.pageList(page, pageSize, companyId, status));
    }

    /**
     * 查询企业激活状态的子模板列表（不分页，用于生成报告时选择）
     */
    @GetMapping("/active")
    public R<List<CompanyTemplate>> listActive(@RequestParam("companyId") String companyId) {
        return R.ok(companyTemplateService.listActive(companyId));
    }

    /**
     * 查询子模板详情
     */
    @GetMapping("/{id}")
    public R<CompanyTemplate> detail(@PathVariable String id) {
        return R.ok(companyTemplateService.getById(id));
    }

    /**
     * 下载子模板文件
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) throws IOException {
        FileUtil.DownloadInfo info = companyTemplateService.download(id);
        String encodedName = URLEncoder.encode(info.getName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        byte[] bytes = Files.readAllBytes(info.getPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bytes.length)
                .body(new org.springframework.core.io.ByteArrayResource(bytes));
    }

    /**
     * 获取子模板可访问 URL（供 OnlyOffice 等在线编辑器使用）
     * <p>
     * 返回受鉴权的下载接口地址，OnlyOffice 编辑器需携带 Authorization header 访问。
     * 格式：{ "url": "...", "fileName": "...", "fileType": "docx" }
     * </p>
     */
    @GetMapping("/{id}/content-url")
    public R<Map<String, String>> getContentUrl(
            @PathVariable String id,
            jakarta.servlet.http.HttpServletRequest request) {
        CompanyTemplate template = companyTemplateService.getById(id);
        if (template == null) throw BizException.notFound("子模板");

        // 构造下载接口完整 URL（受鉴权，OnlyOffice 需携带 JWT token 访问）
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        String contextPath = request.getContextPath();

        String portStr = "";
        if (!((scheme.equals("http") && serverPort == 80)
                || (scheme.equals("https") && serverPort == 443))) {
            portStr = ":" + serverPort;
        }

        String downloadUrl = scheme + "://" + serverName + portStr
                + contextPath + "/company-template/" + id + "/download";

        String fileName = template.getName() != null ? template.getName() + ".docx" : id + ".docx";

        return R.ok(Map.of(
                "url", downloadUrl,
                "fileName", fileName,
                "fileType", "docx"
        ));
    }

    /**
     * 获取子模板内容（返回 docx 文件流，供前端在线预览/编辑）
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> getContent(@PathVariable String id) throws IOException {
        Resource resource = companyTemplateService.getContentResource(id);
        CompanyTemplate template = companyTemplateService.getById(id);
        String encodedName = URLEncoder.encode(template.getName() + ".docx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    /**
     * 保存在线编辑后的子模板（上传修改后的 docx 文件）
     */
    @PutMapping("/{id}/content")
    public R<CompanyTemplate> saveContent(
            @PathVariable String id,
            @RequestPart("file") MultipartFile file) {
        validateWordFile(file);
        CompanyTemplate updated = companyTemplateService.saveEditedContent(id, file);
        return R.ok("子模板已更新", updated);
    }

    /**
     * 获取子模板的占位符状态列表
     * <p>
     * 返回所有占位符的状态（uncertain/confirmed/ignored）和位置信息
     * </p>
     */
    @GetMapping("/{id}/placeholders")
    public R<Map<String, Object>> getPlaceholders(@PathVariable String id) {
        CompanyTemplate template = companyTemplateService.getById(id);
        List<com.fileproc.template.entity.CompanyTemplatePlaceholder> list = placeholderService.listByTemplateId(id);

        // 按状态分组
        List<com.fileproc.template.entity.CompanyTemplatePlaceholder> uncertain = list.stream()
                .filter(p -> "uncertain".equals(p.getStatus()))
                .toList();
        List<com.fileproc.template.entity.CompanyTemplatePlaceholder> confirmed = list.stream()
                .filter(p -> "confirmed".equals(p.getStatus()))
                .toList();
        List<com.fileproc.template.entity.CompanyTemplatePlaceholder> ignored = list.stream()
                .filter(p -> "ignored".equals(p.getStatus()))
                .toList();

        return R.ok(Map.of(
                "templateId", id,
                "total", list.size(),
                "uncertainCount", uncertain.size(),
                "confirmedCount", confirmed.size(),
                "ignoredCount", ignored.size(),
                "uncertainList", uncertain,
                "confirmedList", confirmed,
                "ignoredList", ignored
        ));
    }

    /**
     * 确认反向生成时的待确认占位符列表
     * <p>
     * 用户确认后，系统：
     * 1. 更新占位符状态记录
     * 2. 应用确认的占位符替换到Word文件
     * 3. 检查是否还有未确认的占位符
     * </p>
     */
    @PostMapping("/{id}/confirm-placeholders")
    public R<Map<String, Object>> confirmPlaceholders(
            @PathVariable String id,
            @RequestBody List<ReverseTemplateEngine.PendingConfirmItem> confirmItems) {

        CompanyTemplate template = companyTemplateService.getByIdWithFilePath(id);
        if (template.getFilePath() == null) {
            throw BizException.of(400, "子模板文件不存在，无法确认占位符");
        }

        // 1. 更新占位符状态记录
        placeholderService.confirmPlaceholders(id, confirmItems);

        // 2. 应用确认的占位符替换到Word文件
        String absPath = uploadDir + "/" + template.getFilePath();
        int confirmedCount = reverseTemplateEngine.applyConfirmedPlaceholders(absPath, confirmItems);

        // 3. 检查是否还有未确认的占位符
        int uncertainCount = placeholderService.countUncertain(id);

        log.info("[CompanyTemplateController] 占位符确认完成: templateId={}, confirmed={}, remainingUncertain={}",
                id, confirmedCount, uncertainCount);

        return R.ok("占位符确认完成", Map.of(
                "confirmedCount", confirmedCount,
                "uncertainCount", uncertainCount,
                "allConfirmed", uncertainCount == 0
        ));
    }

    /**
     * 设为当前使用版本
     * <p>
     * 将该子模板设为 active，同时将该企业其他子模板设为 archived
     * </p>
     */
    @PutMapping("/{id}/set-active")
    public R<CompanyTemplate> setActive(@PathVariable String id) {
        CompanyTemplate template = companyTemplateService.setActive(id);
        return R.ok("已设为当前使用版本", template);
    }

    /**
     * 归档子模板
     * <p>
     * 流程：使用该子模板+对应年度数据生成最终报告 → 删除子模板 → 报告进入历史报告
     * </p>
     *
     * @return 生成的报告ID
     */
    @PutMapping("/{id}/archive")
    public R<Map<String, String>> archive(@PathVariable String id) {
        String reportId = companyTemplateService.archive(id);
        return R.ok("子模板已归档，最终报告已生成", Map.of("reportId", reportId));
    }

    /**
     * 删除子模板
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        companyTemplateService.delete(id);
        return R.ok();
    }

    // ========== 私有方法 ==========

    private void validateWordFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw BizException.of(400, "文件不能为空");
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".docx")) {
            throw BizException.of(400, "仅支持 .docx 格式");
        }
    }

    private void validateExcelFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw BizException.of(400, "文件不能为空");
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".xlsx")) {
            throw BizException.of(400, "仅支持 .xlsx 格式");
        }
    }

    private String saveTempFile(MultipartFile file, String relDir, String fileName) {
        Path baseDir = Paths.get(uploadDir).normalize().toAbsolutePath();
        Path fullDir = baseDir.resolve(relDir).normalize();
        if (!fullDir.startsWith(baseDir)) throw BizException.of("非法路径");
        try {
            Files.createDirectories(fullDir);
            file.transferTo(fullDir.resolve(fileName));
        } catch (IOException e) {
            throw BizException.of("临时文件保存失败：" + e.getMessage());
        }
        return relDir + fileName;
    }

    private String toAbsPath(String relativePath) {
        return uploadDir + "/" + relativePath;
    }

    private void cleanTempDir(String relDir) {
        try {
            Path dir = Paths.get(uploadDir, relDir).normalize().toAbsolutePath();
            if (Files.exists(dir)) {
                Files.walk(dir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
            }
        } catch (IOException e) {
            log.warn("[CompanyTemplateController] 清理临时目录失败: {}", e.getMessage());
        }
    }
}
