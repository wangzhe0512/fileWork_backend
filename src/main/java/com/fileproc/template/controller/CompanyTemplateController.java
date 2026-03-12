package com.fileproc.template.controller;

import com.fileproc.llm.service.LlmReverseOrchestrator;
import com.fileproc.common.PageResult;
import com.fileproc.common.R;
import com.fileproc.common.TenantContext;
import com.fileproc.common.util.FileUtil;
import com.fileproc.datafile.entity.DataFile;
import com.fileproc.datafile.mapper.DataFileMapper;
import com.fileproc.report.service.ReverseTemplateEngine;
import com.fileproc.template.entity.CompanyTemplate;
import com.fileproc.template.entity.CompanyTemplateModule;
import com.fileproc.template.entity.CompanyTemplatePlaceholder;
import com.fileproc.template.entity.SystemPlaceholder;
import com.fileproc.template.entity.SystemTemplate;
import com.fileproc.template.service.CompanyTemplateModuleService;
import com.fileproc.template.service.CompanyTemplatePlaceholderService;
import com.fileproc.template.service.CompanyTemplateService;
import com.fileproc.template.service.SystemTemplateService;
import lombok.Data;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final CompanyTemplateModuleService moduleService;
    private final SystemTemplateService systemTemplateService;
    private final ReverseTemplateEngine reverseTemplateEngine;
    private final LlmReverseOrchestrator llmReverseOrchestrator;
    private final DataFileMapper dataFileMapper;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    /**
     * 反向生成企业子模板（大模型驱动，异步处理）
     * <p>
     * 输入：历史报告Word + 年度（自动查询对应清单Excel + BVD Excel）
     * 输出（立即返回202）：companyTemplateId + taskId，前端通过 GET /{id}/reverse-status 轮询进度
     * <p>
     * 引擎选择：
     * - Ollama 可用（llm.ollama.enabled=true 且服务健康）→ 大模型语义解析引擎
     * - Ollama 不可用 → 旧字符串匹配引擎（同步降级，返回200）
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

        // 获取系统标准模板和占位符规则（降级引擎时使用）
        SystemTemplate systemTemplate = systemTemplateService.getActiveWithPaths();
        List<SystemPlaceholder> placeholders = systemTemplateService.listPlaceholders(systemTemplate.getId());

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

        // 提前创建子模板记录（状态为 active，文件大小待更新）
        String templateName = name != null ? name : (year + "年子模板");
        CompanyTemplate companyTemplate = companyTemplateService.saveReverseResult(
                tenantId, companyId, systemTemplate.getId(),
                templateName, year, sourceReportId, outRelPath, 0L
        );

        // 检查大模型是否可用
        boolean llmAvailable = llmReverseOrchestrator.isLlmAvailable();

        if (llmAvailable) {
            // 大模型引擎：异步处理，立即返回202
            String taskId = UUID.randomUUID().toString();
            llmReverseOrchestrator.markProcessing(taskId, companyTemplate.getId());

            String finalListPath = uploadDir + "/" + listPath;
            String finalBvdPath = uploadDir + "/" + bvdPath;

            llmReverseOrchestrator.executeAsync(
                    taskId, companyTemplate, histPath,
                    finalListPath, finalBvdPath, outAbsPath,
                    tmpDir, tenantId, placeholders
            );

            log.info("[CompanyTemplateController] 大模型反向生成任务已提交: templateId={}, taskId={}",
                    companyTemplate.getId(), taskId);

            return R.ok("反向生成任务已提交，请轮询状态", Map.of(
                    "companyTemplateId", companyTemplate.getId(),
                    "taskId", taskId,
                    "async", true,
                    "engine", "llm"
            ));
        } else {
            // 新引擎降级：同步处理（不再依赖 SystemPlaceholder 规则列表）
            ReverseTemplateEngine.ReverseResult result = reverseTemplateEngine.reverse(
                    toAbsPath(histPath),
                    uploadDir + "/" + listPath,
                    uploadDir + "/" + bvdPath,
                    outAbsPath
            );

            // 更新文件大小
            try {
                long fileSize = Files.size(Paths.get(outAbsPath));
                companyTemplateService.updateFileSize(companyTemplate.getId(), fileSize);
            } catch (IOException ignored) {}

            initModulesAndPlaceholders(companyTemplate.getId(), result);
            cleanTempDir(tmpDir);

            log.info("[CompanyTemplateController] 旧引擎反向生成完成: templateId={}, matched={}",
                    companyTemplate.getId(), result.getMatchedCount());

            return R.ok("反向生成完成", Map.of(
                    "template", companyTemplate,
                    "matchedCount", result.getMatchedCount(),
                    "pendingConfirmList", result.getPendingConfirmList(),
                    "unmatchedLongTextEntries", result.getUnmatchedLongTextEntries(),
                    "async", false,
                    "engine", "fallback"
            ));
        }
    }

    /**
     * 查询反向生成任务状态（供前端轮询）
     * <p>
     * 状态值：
     * - processing:{templateId}    — 任务进行中
     * - done:{templateId}:matched=N:lowConfidence=M:engine=llm|fallback — 完成
     * - failed:{errMsg}            — 失败
     * - null                       — 任务不存在或已过期（2小时）
     * </p>
     */
    @GetMapping("/{id}/reverse-status")
    public R<Map<String, Object>> getReverseStatus(@PathVariable String id,
                                                    @RequestParam("taskId") String taskId) {
        String status = llmReverseOrchestrator.getTaskStatus(taskId);

        if (status == null) {
            return R.ok(Map.of("status", "not_found", "templateId", id));
        }

        if (status.startsWith("processing:")) {
            return R.ok(Map.of("status", "processing", "templateId", id));
        }

        if (status.startsWith("done:")) {
            // 解析 done 状态的附带信息
            Map<String, Object> result = new HashMap<>();
            result.put("status", "done");
            result.put("templateId", id);
            // 从状态字符串中提取 matched/lowConfidence/engine
            for (String part : status.split(":")) {
                if (part.startsWith("matched=")) result.put("matchedCount", Integer.parseInt(part.substring(8)));
                if (part.startsWith("lowConfidence=")) result.put("lowConfidenceCount", Integer.parseInt(part.substring(14)));
                if (part.startsWith("engine=")) result.put("engine", part.substring(7));
            }
            return R.ok(result);
        }

        if (status.startsWith("failed:")) {
            return R.fail(500, "反向生成失败：" + status.substring(7));
        }

        return R.ok(Map.of("status", status, "templateId", id));
    }

    /**
     * 初始化模块和占位符记录（新引擎版本）
     * <p>
     * 不再依赖 SystemPlaceholder 规则列表，直接从 MatchedPlaceholder 自身字段
     * （moduleCode / moduleName / dataSource / sourceSheet / sourceField）填充占位符实体。
     * </p>
     */
    private void initModulesAndPlaceholders(String templateId,
                                             ReverseTemplateEngine.ReverseResult result) {
        List<ReverseTemplateEngine.MatchedPlaceholder> matchedList = result.getAllMatchedPlaceholders();
        if (matchedList == null || matchedList.isEmpty()) {
            log.info("[CompanyTemplateController] 没有匹配到任何占位符，跳过模块初始化: templateId={}", templateId);
            return;
        }

        // 1. 从 matchedList 直接提取模块信息（code -> name），保持出现顺序
        Map<String, String> moduleCodeToName = new LinkedHashMap<>();
        for (ReverseTemplateEngine.MatchedPlaceholder matched : matchedList) {
            moduleCodeToName.put(matched.getModuleCode(), matched.getModuleName());
        }

        // 2. 创建模块记录并建立 code → moduleId 的映射
        Map<String, String> codeToModuleId = new HashMap<>();
        int sort = 0;
        for (Map.Entry<String, String> entry : moduleCodeToName.entrySet()) {
            CompanyTemplateModule module = moduleService.getOrCreate(
                    templateId, entry.getKey(), entry.getValue(), sort++);
            codeToModuleId.put(entry.getKey(), module.getId());
        }

        // 3. 创建占位符记录（按占位符名称去重，同一占位符多次出现只保留一条）
        List<CompanyTemplatePlaceholder> placeholders = new ArrayList<>();
        Set<String> processedPhNames = new HashSet<>();
        int phSort = 0;

        for (ReverseTemplateEngine.MatchedPlaceholder matched : matchedList) {
            String phName = matched.getPlaceholderName();
            if (processedPhNames.contains(phName)) continue;
            processedPhNames.add(phName);

            String moduleCode = matched.getModuleCode();
            String moduleId = codeToModuleId.get(moduleCode);
            if (moduleId == null) {
                log.warn("[CompanyTemplateController] 模块未找到: code={}, placeholder={}", moduleCode, phName);
                continue;
            }

            CompanyTemplatePlaceholder ph = new CompanyTemplatePlaceholder();
            ph.setId(UUID.randomUUID().toString());
            ph.setCompanyTemplateId(templateId);
            ph.setModuleId(moduleId);
            ph.setPlaceholderName(phName);
            ph.setName(phName); // 直接用占位符名作为显示名（已语义化，如"企业名称"）
            ph.setStatus(matched.getStatus()); // "confirmed" 或 "uncertain"
            ph.setExpectedValue(matched.getExpectedValue());
            ph.setActualValue(matched.getActualValue());
            ph.setReason("uncertain".equals(matched.getStatus()) ? "短纯数字值，需人工确认" : null);
            ph.setPositionJson(matched.getPositionJson());
            ph.setSort(phSort++);
            ph.setCreatedAt(LocalDateTime.now());
            ph.setUpdatedAt(LocalDateTime.now());
            // 直接从 matched 获取来源信息，不再查 systemPhMap
            ph.setType("text"); // 默认 text 类型（chart/image 本期不处理）
            ph.setDataSource(matched.getDataSource());
            ph.setSourceSheet(matched.getSourceSheet());
            ph.setSourceField(matched.getSourceField());

            placeholders.add(ph);
        }

        // 4. 批量保存占位符
        for (CompanyTemplatePlaceholder ph : placeholders) {
            placeholderService.savePlaceholder(ph);
        }

        log.info("[CompanyTemplateController] 模块和占位符已初始化: templateId={}, modules={}, placeholders={}",
                templateId, moduleCodeToName.size(), placeholders.size());
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
     * 兼容性处理：如果type为空，使用confirmedType作为fallback
     * </p>
     */
    @GetMapping("/{id}/placeholders")
    public R<Map<String, Object>> getPlaceholders(@PathVariable String id) {
        CompanyTemplate template = companyTemplateService.getById(id);
        List<com.fileproc.template.entity.CompanyTemplatePlaceholder> list = placeholderService.listByTemplateId(id);

        // 兼容性处理：type为空时使用confirmedType
        list.forEach(this::fillTypeIfEmpty);

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

    // ========== 企业子模板模块管理接口 ==========

    /**
     * 获取子模板的模块列表
     * <p>
     * 返回按sort和created_at排序的模块列表
     * </p>
     */
    @GetMapping("/{templateId}/modules")
    public R<List<CompanyTemplateModule>> listModules(@PathVariable String templateId) {
        // 校验权限
        companyTemplateService.getById(templateId);
        List<CompanyTemplateModule> modules = moduleService.listByTemplateId(templateId);
        return R.ok(modules);
    }

    /**
     * 获取模块的占位符列表
     * <p>
     * 返回指定模块下的所有占位符，按sort和created_at排序
     * 兼容性处理：如果type为空，使用confirmedType作为fallback
     * </p>
     */
    @GetMapping("/{templateId}/modules/{moduleId}/placeholders")
    public R<List<CompanyTemplatePlaceholder>> listPlaceholdersByModule(
            @PathVariable String templateId,
            @PathVariable String moduleId) {
        // 校验权限并验证模块归属
        companyTemplateService.getById(templateId);
        List<CompanyTemplatePlaceholder> placeholders = placeholderService.listByModuleIdAndTemplateId(moduleId, templateId);
        
        // 兼容性处理：type为空时使用confirmedType
        placeholders.forEach(this::fillTypeIfEmpty);
        
        return R.ok(placeholders);
    }

    /**
     * 更新占位符信息
     * <p>
     * 支持更新：name(显示名称)、type(类型)、dataSource(数据源)、
     * sourceSheet(来源Sheet)、sourceField(来源字段)、description(说明)、sort(排序)
     * </p>
     */
    @PutMapping("/{templateId}/placeholders/{placeholderId}")
    public R<CompanyTemplatePlaceholder> updatePlaceholder(
            @PathVariable String templateId,
            @PathVariable String placeholderId,
            @RequestBody CompanyTemplatePlaceholderService.PlaceholderUpdateRequest request) {
        // 校验权限并验证占位符归属
        companyTemplateService.getById(templateId);
        CompanyTemplatePlaceholder updated = placeholderService.updateMetadata(placeholderId, templateId, request);
        return R.ok("占位符已更新", updated);
    }

    /**
     * 批量同步占位符到其他子模板
     * <p>
     * 将源子模板的占位符元数据同步到同一企业下的其他子模板。
     * 按 module.code + placeholder_name 匹配，匹配不到则跳过不报错。
     * </p>
     *
     * @param request 同步请求：{sourceTemplateId, targetTemplateIds, placeholderIds}
     */
    @PostMapping("/sync-placeholders")
    public R<CompanyTemplatePlaceholderService.SyncResult> syncPlaceholders(
            @RequestBody SyncPlaceholdersRequest request) {
        CompanyTemplatePlaceholderService.SyncResult result = placeholderService.syncPlaceholders(
                request.getSourceTemplateId(),
                request.getTargetTemplateIds(),
                request.getPlaceholderIds()
        );
        return R.ok("同步完成", result);
    }

    // ========== DTO ==========

    @Data
    public static class SyncPlaceholdersRequest {
        private String sourceTemplateId;
        private List<String> targetTemplateIds;
        private List<String> placeholderIds;
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

    /**
     * 兼容性处理：如果type为空，使用confirmedType作为fallback
     * <p>
     * 历史数据可能存在type为空的情况，此时使用confirmedType作为显示类型
     * </p>
     */
    private void fillTypeIfEmpty(CompanyTemplatePlaceholder placeholder) {
        if (placeholder.getType() == null || placeholder.getType().isBlank()) {
            String confirmedType = placeholder.getConfirmedType();
            if (confirmedType != null && !confirmedType.isBlank()) {
                placeholder.setType(confirmedType);
            }
        }
    }
}
