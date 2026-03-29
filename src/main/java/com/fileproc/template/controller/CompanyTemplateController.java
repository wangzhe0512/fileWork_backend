package com.fileproc.template.controller;

import com.fileproc.llm.service.LlmReverseOrchestrator;
import com.fileproc.common.BizException;
import com.fileproc.common.PageResult;
import com.fileproc.common.R;
import com.fileproc.common.TenantContext;
import com.fileproc.common.util.FileUtil;
import com.fileproc.template.mapper.CompanyTemplatePlaceholderMapper;
import com.fileproc.auth.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.util.Date;
import com.fileproc.datafile.entity.DataFile;
import com.fileproc.datafile.mapper.DataFileMapper;
import com.fileproc.registry.entity.PlaceholderRegistry;
import com.fileproc.registry.service.PlaceholderRegistryService;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;



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
    private final CompanyTemplatePlaceholderMapper placeholderMapper;
    private final JwtUtil jwtUtil;
    private final PlaceholderRegistryService placeholderRegistryService;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${jwt.user.secret}")
    private String jwtSecret;

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
            // 新引擎降级：同步处理（优先使用企业级+系统级动态注册表，回退静态列表）
            ReverseTemplateEngine.ReverseResult result = reverseTemplateEngine.reverse(
                    toAbsPath(histPath),
                    uploadDir + "/" + listPath,
                    uploadDir + "/" + bvdPath,
                    outAbsPath,
                    companyId
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
     * 生成带有临时 Token 的公开下载地址，OnlyOffice 可直接访问无需登录。
     * Token 有效期 5 分钟，仅允许访问指定模板。
     * 格式：{ "url": "...", "fileName": "...", "fileType": "docx" }
     * </p>
     */
    @GetMapping("/{id}/content-url")
    public R<Map<String, String>> getContentUrl(
            @PathVariable String id,
            jakarta.servlet.http.HttpServletRequest request) {
        CompanyTemplate template = companyTemplateService.getById(id);
        if (template == null) throw BizException.notFound("子模板");

        // 生成临时 Token（5分钟有效期）
        String tempToken = generateTempToken(id);

        // 构造公开下载接口 URL
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
                + contextPath + "/company-template/" + id + "/public-download?temp_token=" + tempToken;

        String fileName = template.getName() != null ? template.getName() + ".docx" : id + ".docx";

        return R.ok(Map.of(
                "url", downloadUrl,
                "fileName", fileName,
                "fileType", "docx"
        ));
    }

    /**
     * 公开下载接口（供 OnlyOffice 使用，通过临时 Token 免登录访问）
     * <p>
     * 临时 Token 有效期 5 分钟，通过 /{id}/content-url 接口获取。
     * </p>
     */
    @GetMapping("/{id}/public-download")
    public ResponseEntity<Resource> publicDownload(
            @PathVariable String id,
            @RequestParam("temp_token") String tempToken) throws IOException {
        // 验证临时 Token
        if (!validateTempToken(tempToken, id)) {
            return ResponseEntity.status(403).body(null);
        }

        FileUtil.DownloadInfo info = companyTemplateService.download(id);
        String encodedName = URLEncoder.encode(info.getName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        byte[] bytes = Files.readAllBytes(info.getPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bytes.length)
                .body(new org.springframework.core.io.ByteArrayResource(bytes));
    }

    /**
     * 生成临时 Token（用于 OnlyOffice 免登录下载）
     * 有效期 5 分钟，只能访问指定模板
     */
    private String generateTempToken(String templateId) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("onlyoffice-temp")
                .claim("templateId", templateId)
                .claim("type", "temp-download")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 5 * 60 * 1000)) // 5分钟
                .signWith(key)
                .compact();
    }

    /**
     * 验证临时 Token
     */
    private boolean validateTempToken(String token, String expectedTemplateId) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String type = claims.get("type", String.class);
            String templateId = claims.get("templateId", String.class);

            return "temp-download".equals(type) && expectedTemplateId.equals(templateId);
        } catch (Exception e) {
            log.debug("临时Token验证失败: {}", e.getMessage());
            return false;
        }
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
     * OnlyOffice 回调接口（文档保存通知）
     * <p>
     * OnlyOffice 服务器在文档保存时主动调用此接口，Content-Type 为 application/json。
     * 使用原始字符串接收并手动解析，兼容 OnlyOffice 各版本的回调格式差异。
     * 参考：https://api.onlyoffice.com/editors/callback
     * </p>
     *
     * @param id 子模板ID
     * @param rawBody 原始请求体（JSON字符串）
     */
    @PostMapping(value = "/{id}/onlyoffice-callback", consumes = {"application/json", "text/plain", "*/*"})
    public Map<String, Object> onlyofficeCallback(
            @PathVariable String id,
            @RequestBody(required = false) String rawBody) {

        log.info("[OnlyOfficeCallback] 收到回调: templateId={}, body={}", id, rawBody);

        if (rawBody == null || rawBody.isBlank()) {
            log.warn("[OnlyOfficeCallback] 请求体为空: templateId={}", id);
            return Map.of("error", 0);
        }

        try {
            // 手动解析JSON，避免依赖 Content-Type 绑定
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(rawBody);

            int status = node.path("status").asInt(0);
            String downloadUrl = node.path("url").asText(null);

            log.info("[OnlyOfficeCallback] 解析结果: templateId={}, status={}, url={}", id, status, downloadUrl);

            // OnlyOffice 状态码：0=无变化, 1=编辑中, 2=准备保存, 3=保存中, 4=已关闭, 6=保存完成, 7=强制保存错误
            if ((status == 2 || status == 6) && downloadUrl != null && !downloadUrl.isBlank()) {
                downloadAndSaveFromOnlyOffice(id, downloadUrl);
                log.info("[OnlyOfficeCallback] 文档已保存: templateId={}", id);
            }

        } catch (Exception e) {
            log.error("[OnlyOfficeCallback] 处理回调失败: templateId={}", id, e);
            // OnlyOffice 要求返回 {"error":0} 才认为成功，即使处理失败也返回0避免重试风暴
            return Map.of("error", 0);
        }

        // 必须返回 {"error":0} 告知 OnlyOffice 回调已处理
        return Map.of("error", 0);
    }

    /**
     * 从 OnlyOffice 下载并保存文档
     */
    private void downloadAndSaveFromOnlyOffice(String templateId, String downloadUrl) {
        try {
            java.net.URL url = new java.net.URL(downloadUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            try (java.io.InputStream is = conn.getInputStream()) {
                // 保存到临时文件
                java.nio.file.Path tempFile = Files.createTempFile("onlyoffice_", ".docx");
                Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // 读取文件内容
                byte[] fileContent = Files.readAllBytes(tempFile);

                // 更新子模板内容
                companyTemplateService.updateContent(templateId, fileContent);

                // 清理临时文件
                Files.deleteIfExists(tempFile);

                log.info("[OnlyOfficeCallback] 文档已下载并保存: templateId={}, size={} bytes",
                        templateId, fileContent.length);
            }
        } catch (Exception e) {
            throw new RuntimeException("下载或保存文档失败", e);
        }
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

    // ========== 新增：占位符绑定状态接口 ==========

    /**
     * 查询子模板全量占位符及其绑定状态
     * <p>
     * 用于子模板在线编辑页面右侧面板展示占位符列表，每条记录附带：
     * - bindingStatus（bound/unbound）
     * - positionCount（在文档中插入的数量）
     * - registryLevel（system/company/custom）
     * </p>
     *
     * @param templateId 子模板ID
     * @param companyId  企业ID（用于查注册表级别，可选）
     */
    @GetMapping("/{templateId}/placeholders/binding-status")
    public R<List<CompanyTemplatePlaceholderService.PlaceholderBindingVO>> listPlaceholderBindingStatus(
            @PathVariable String templateId,
            @RequestParam(required = false) String companyId) {
        companyTemplateService.getById(templateId); // 校验子模板存在
        List<CompanyTemplatePlaceholderService.PlaceholderBindingVO> list =
                placeholderService.listWithBindingStatus(templateId, companyId);
        return R.ok(list);
    }

    /**
     * 快速绑定/解绑：仅更新占位符的 sourceSheet 和 sourceField
     * <p>
     * 解绑时两个字段均传 null 即可清空。
     * </p>
     */
    @PatchMapping("/{templateId}/placeholders/{phId}/bind")
    public R<CompanyTemplatePlaceholder> bindPlaceholder(
            @PathVariable String templateId,
            @PathVariable String phId,
            @RequestBody CompanyTemplatePlaceholderService.BindingRequest request) {
        companyTemplateService.getById(templateId); // 校验子模板存在
        CompanyTemplatePlaceholder updated = placeholderService.updateBinding(
                phId, templateId, request.sourceSheet(), request.sourceField());
        return R.ok("绑定已更新", updated);
    }

    /**
     * 原子操作：就地新建企业级注册表规则并绑定到当前占位符
     * <p>
     * 在一个事务内完成两步：
     * 1. 创建企业级注册表条目（PlaceholderRegistry，level=company）
     * 2. 将占位符的 sourceSheet/sourceField 绑定到该规则的数据源字段
     * <p>
     * placeholderName 自动从占位符记录读取，无需前端传入。
     * 若同名企业级规则已存在，返回 400，前端提示"已有规则，是否去编辑"。
     * </p>
     *
     * @param templateId 子模板ID
     * @param phId       占位符ID
     * @param request    新建注册表规则所需字段 + companyId
     */
    @PostMapping("/{templateId}/placeholders/{phId}/create-registry-and-bind")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> createRegistryAndBind(
            @PathVariable String templateId,
            @PathVariable String phId,
            @RequestBody CreateRegistryAndBindRequest request) {

        companyTemplateService.getById(templateId); // 校验子模板存在

        // 1. 查询占位符（获取 placeholderName）
        CompanyTemplatePlaceholder ph = placeholderMapper.selectById(phId);
        if (ph == null) throw BizException.notFound("占位符");
        if (!templateId.equals(ph.getCompanyTemplateId())) {
            throw BizException.forbidden("该占位符不属于指定子模板");
        }

        // 2. 构造企业级注册表条目（placeholderName 自动从占位符读取）
        PlaceholderRegistry entry = new PlaceholderRegistry();
        entry.setLevel("company");
        entry.setCompanyId(request.getCompanyId());
        entry.setPlaceholderName(ph.getPlaceholderName());
        entry.setDisplayName(request.getDisplayName() != null ? request.getDisplayName() : ph.getName());
        entry.setPhType(request.getPhType());
        entry.setDataSource(request.getDataSource());
        entry.setSheetName(request.getSheetName());
        entry.setCellAddress(request.getCellAddress());
        entry.setEnabled(1);

        // 3. 保存注册表条目（内部已做重复校验）
        PlaceholderRegistry savedEntry = placeholderRegistryService.saveEntry(entry);

        // 4. 绑定占位符的 sourceSheet/sourceField
        CompanyTemplatePlaceholder updatedPh = placeholderService.updateBinding(
                phId, templateId, request.getSheetName(), request.getCellAddress());

        log.info("[CompanyTemplateController] 注册表规则已创建并绑定: templateId={}, phId={}, registryId={}",
                templateId, phId, savedEntry.getId());

        return R.ok("注册表规则已创建并绑定", Map.of(
                "registryEntry", savedEntry,
                "placeholder", updatedPh
        ));
    }

    /**
     * 从占位符库选已有规则，添加到子模板
     * <p>
     * 场景：弹框中用户从现有注册表条目中选中某条，点击"添加"。
     * - 若该 placeholderName 已存在于此子模板，返回 400「不能重复添加」
     * - 新建的占位符实例会继承注册表规则的 sourceSheet/sourceField 绑定
     * </p>
     *
     * @param templateId 子模板ID
     * @param request    包含 registryId（必填）和 moduleId（可选）
     */
    @PostMapping("/{templateId}/placeholders/add-from-registry")
    public R<CompanyTemplatePlaceholder> addFromRegistry(
            @PathVariable String templateId,
            @RequestBody CompanyTemplatePlaceholderService.AddFromRegistryRequest request) {
        companyTemplateService.getById(templateId); // 校验子模板存在
        CompanyTemplatePlaceholder ph = placeholderService.addFromRegistry(
                templateId, request.registryId(), request.moduleId());
        return R.ok("占位符已添加", ph);
    }

    /**
     * 全新新建：同时创建企业级注册表规则 + 子模板占位符实例
     * <p>
     * 场景：弹框中用户在占位符库里找不到想要的，选择"新建"。
     * 与 create-registry-and-bind 的区别：
     * - create-registry-and-bind：先有占位符实例（由反向引擎生成），再给它创建注册表规则
     * - create-new-with-registry：从零开始，同时创建注册表规则和占位符实例
     * </p>
     *
     * @param templateId 子模板ID
     * @param request    新建参数（companyId/placeholderName/phType/dataSource/sheetName 等）
     */
    @PostMapping("/{templateId}/placeholders/create-new-with-registry")
    public R<Map<String, Object>> createNewWithRegistry(
            @PathVariable String templateId,
            @RequestBody CompanyTemplatePlaceholderService.CreateNewWithRegistryRequest request) {
        companyTemplateService.getById(templateId); // 校验子模板存在
        Map<String, Object> result = placeholderService.createNewWithRegistry(templateId, request);
        return R.ok("占位符及注册表规则已创建", result);
    }

    // ========== DTO ==========

    @Data
    public static class SyncPlaceholdersRequest {
        private String sourceTemplateId;
        private List<String> targetTemplateIds;
        private List<String> placeholderIds;
    }

    // ========== 新增：批量删除占位符接口 ==========

    /**
     * 批量删除子模板的占位符
     * <p>
     * 用于前端用户手动删除不需要的占位符
     * </p>
     *
     * @param id            子模板ID
     * @param deleteRequest 删除请求（包含占位符名称列表）
     * @return 删除结果
     */
    @PostMapping("/{id}/delete-placeholders")
    public R<Map<String, Object>> deletePlaceholders(
            @PathVariable String id,
            @RequestBody DeletePlaceholdersRequest deleteRequest) {

        // 校验子模板存在
        CompanyTemplate template = companyTemplateService.getById(id);
        if (template == null) {
            throw BizException.notFound("子模板");
        }

        List<String> names = deleteRequest.getPlaceholderNames();
        if (names == null || names.isEmpty()) {
            return R.ok("没有需要删除的占位符", Map.of(
                    "deletedCount", 0,
                    "totalRequested", 0
            ));
        }

        // 批量删除
        int deletedCount = 0;
        int notFoundCount = 0;
        List<String> notFoundNames = new ArrayList<>();

        for (String name : names) {
            CompanyTemplatePlaceholder ph = placeholderMapper.selectByTemplateIdAndName(id, name);
            if (ph != null) {
                placeholderMapper.deleteById(ph.getId());
                deletedCount++;
                log.info("[CompanyTemplateController] 占位符已删除: templateId={}, name={}", id, name);
            } else {
                notFoundCount++;
                notFoundNames.add(name);
                log.warn("[CompanyTemplateController] 占位符不存在: templateId={}, name={}", id, name);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("deletedCount", deletedCount);
        result.put("notFoundCount", notFoundCount);
        result.put("totalRequested", names.size());
        if (!notFoundNames.isEmpty()) {
            result.put("notFoundNames", notFoundNames);
        }

        log.info("[CompanyTemplateController] 批量删除占位符完成: templateId={}, deleted={}, notFound={}",
                id, deletedCount, notFoundCount);

        return R.ok("删除完成", result);
    }

    @Data
    public static class DeletePlaceholdersRequest {
        /** 要删除的占位符名称列表 */
        private List<String> placeholderNames;
    }

    /**
     * 就地新建企业级注册表规则并绑定请求体
     */
    @Data
    public static class CreateRegistryAndBindRequest {
        /** 企业ID（必填） */
        private String companyId;
        /** 展示名（可选，默认使用占位符的name） */
        private String displayName;
        /** 占位符类型：DATA_CELL/TABLE_CLEAR/TABLE_CLEAR_FULL/TABLE_ROW_TEMPLATE/LONG_TEXT/BVD（必填） */
        private String phType;
        /** 数据来源：list / bvd（必填） */
        private String dataSource;
        /** 来源 Sheet 名（必填） */
        private String sheetName;
        /** 单元格坐标，如 B1（TABLE_CLEAR 类型可为空） */
        private String cellAddress;
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

    // ========== 新增：占位符位置查询接口（供OnlyOffice跳转定位）==========

    /**
     * 查询占位符在文档中的位置信息
     * <p>
     * 用于前端点击"去编辑器查看"时，获取占位符在Word文档中的精确位置，
     * 通过OnlyOffice API跳转到对应位置
     * </p>
     *
     * @param id    子模板ID
     * @param names 占位符名称列表（逗号分隔），为空则返回所有占位符
     * @return 占位符位置信息列表
     */
    @GetMapping("/{id}/placeholder-positions")
    public R<List<PlaceholderPositionVO>> getPlaceholderPositions(
            @PathVariable String id,
            @RequestParam(required = false) String names) {

        // 校验子模板存在
        CompanyTemplate template = companyTemplateService.getById(id);
        if (template == null) {
            throw BizException.notFound("子模板");
        }

        List<CompanyTemplatePlaceholder> placeholders;
        if (names != null && !names.isBlank()) {
            List<String> nameList = Arrays.asList(names.split(","));
            placeholders = placeholderMapper.selectByTemplateIdAndNames(id, nameList);
        } else {
            placeholders = placeholderMapper.selectByTemplateId(id);
        }

        List<PlaceholderPositionVO> result = placeholders.stream()
                .filter(p -> p.getPositionJson() != null && !p.getPositionJson().isBlank())
                .map(p -> {
                    PlaceholderPositionVO vo = new PlaceholderPositionVO();
                    vo.setPlaceholderName(p.getPlaceholderName());
                    vo.setPosition(parsePositionJson(p.getPositionJson()));
                    vo.setStatus(p.getStatus());
                    return vo;
                })
                .collect(Collectors.toList());

        return R.ok(result);
    }

    /**
     * 解析位置JSON字符串为Map
     */
    private Map<String, Object> parsePositionJson(String positionJson) {
        try {
            // 简单手动解析JSON，避免引入额外依赖
            Map<String, Object> map = new HashMap<>();
            String json = positionJson.trim();
            if (json.startsWith("{") && json.endsWith("}")) {
                json = json.substring(1, json.length() - 1);
                String[] pairs = json.split(",");
                for (String pair : pairs) {
                    String[] kv = pair.split(":", 2);
                    if (kv.length == 2) {
                        String key = kv[0].trim().replace("\"", "");
                        String value = kv[1].trim();
                        // 尝试解析为数字，否则作为字符串
                        if (value.matches("-?\\d+")) {
                            map.put(key, Integer.parseInt(value));
                        } else if (value.startsWith("\"") && value.endsWith("\"")) {
                            map.put(key, value.substring(1, value.length() - 1));
                        } else {
                            map.put(key, value);
                        }
                    }
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("[CompanyTemplateController] 解析位置JSON失败: {}", positionJson, e);
            return new HashMap<>();
        }
    }

    // ========== DTO ==========

    /**
     * 占位符位置信息VO
     */
    @Data
    public static class PlaceholderPositionVO {
        /** 占位符名称 */
        private String placeholderName;
        /** 位置信息（paragraphIndex/runIndex/offset/elementType等） */
        private Map<String, Object> position;
        /** 状态：uncertain/confirmed/ignored */
        private String status;
    }

    /**
     * OnlyOffice 回调请求DTO
     * 参考：https://api.onlyoffice.com/editors/callback
     */
    @Data
    public static class OnlyOfficeCallback {
        /** 跟踪更改模式 */
        private String changesurl;
        /** 文档下载链接（保存时提供） */
        private String url;
        /** 历史信息 */
        private Object history;
        /** 用户列表 */
        private List<Map<String, Object>> users;
        /** 接收操作状态 */
        private Integer status;
        /** 上次保存的操作类型 */
        private Integer saved;
        /** 强制保存类型 */
        private Integer forcesavetype;
        /** 文档密钥 */
        private String key;
    }
}
