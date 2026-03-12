package com.fileproc.llm.service;

import com.fileproc.common.BizException;
import com.fileproc.common.TenantContext;
import com.fileproc.llm.client.OllamaClient;
import com.fileproc.llm.config.OllamaProperties;
import com.fileproc.report.service.ReverseTemplateEngine;
import com.fileproc.template.entity.CompanyTemplate;
import com.fileproc.template.entity.CompanyTemplatePlaceholder;
import com.fileproc.template.entity.SystemPlaceholder;
import com.fileproc.template.service.CompanyTemplateModuleService;
import com.fileproc.template.service.CompanyTemplatePlaceholderService;
import com.fileproc.template.service.CompanyTemplateService;
import com.fileproc.template.entity.CompanyTemplateModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 大模型反向生成编排器
 * <p>
 * 职责：
 * 1. 在每次请求前检查 Ollama 健康状态
 * 2. Ollama 可用 → 路由到 LlmReverseEngine（大模型驱动，异步执行）
 * 3. Ollama 不可用 → 降级到旧 ReverseTemplateEngine（字符串匹配，同步执行）
 * 4. 任务完成后，将模块和占位符写入数据库，更新 Redis 任务状态
 * <p>
 * 任务状态存入 Redis，key = "llm:task:{taskId}"，过期时间 2 小时
 * 状态值：processing / done / failed
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmReverseOrchestrator {

    private final OllamaClient ollamaClient;
    private final OllamaProperties properties;
    private final LlmReverseEngine llmReverseEngine;
    private final ReverseTemplateEngine fallbackEngine;
    private final CompanyTemplateService companyTemplateService;
    private final CompanyTemplatePlaceholderService placeholderService;
    private final CompanyTemplateModuleService moduleService;
    private final StringRedisTemplate redisTemplate;

    /** Redis 任务状态 key 前缀 */
    private static final String TASK_KEY_PREFIX = "llm:task:";
    /** Redis 任务状态过期时间（小时） */
    private static final long TASK_EXPIRE_HOURS = 2L;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    /**
     * 检查 Ollama 是否可用（用于接口层提前判断引擎类型）
     */
    public boolean isLlmAvailable() {
        return ollamaClient.isHealthy();
    }

    /**
     * 异步执行反向生成主流程（大模型引擎）
     * <p>
     * 前置：调用方已创建好 companyTemplate 记录和临时 histPath 文件，
     * 并向 Redis 写入了 processing 状态。
     * 此方法在 llmExecutor 线程池中运行，完成后更新 Redis 状态。
     * </p>
     *
     * @param taskId          任务ID（用于 Redis 状态更新）
     * @param companyTemplate 已创建的子模板记录
     * @param histPath        历史报告Word临时文件路径（相对 uploadDir）
     * @param listExcelPath   清单Excel绝对路径
     * @param bvdExcelPath    BVD数据Excel绝对路径
     * @param outAbsPath      输出子模板Word绝对路径
     * @param tmpDir          临时目录（任务完成后清理）
     * @param tenantId        租户ID（异步线程中需要手动注入上下文）
     * @param systemPlaceholders 系统占位符列表（降级时使用）
     */
    @Async("llmExecutor")
    public void executeAsync(String taskId,
                              CompanyTemplate companyTemplate,
                              String histPath,
                              String listExcelPath,
                              String bvdExcelPath,
                              String outAbsPath,
                              String tmpDir,
                              String tenantId,
                              List<SystemPlaceholder> systemPlaceholders) {
        // 异步线程需要手动设置租户上下文
        TenantContext.setTenantId(tenantId);

        log.info("[LlmReverseOrchestrator] 开始反向生成: taskId={}, templateId={}", taskId, companyTemplate.getId());

        try {
            ReverseTemplateEngine.ReverseResult result;
            boolean usedLlm;

            // 再次检查 Ollama 健康状态（异步任务开始时可能距离健康检查已过一段时间）
            if (properties.isEnabled() && ollamaClient.isHealthy()) {
                log.info("[LlmReverseOrchestrator] 使用大模型引擎: taskId={}", taskId);
                result = llmReverseEngine.reverse(
                        toAbsPath(histPath), listExcelPath, bvdExcelPath, outAbsPath);
                usedLlm = true;
            } else {
                log.info("[LlmReverseOrchestrator] Ollama 不可用，降级到旧引擎: taskId={}", taskId);
                result = fallbackEngine.reverse(
                        toAbsPath(histPath), listExcelPath, bvdExcelPath,
                        systemPlaceholders, outAbsPath);
                usedLlm = false;
            }

            // 更新子模板文件大小
            try {
                long fileSize = Files.size(Paths.get(outAbsPath));
                companyTemplateService.updateFileSize(companyTemplate.getId(), fileSize);
            } catch (IOException ignored) {}

            // 初始化模块和占位符记录
            initModulesAndPlaceholders(companyTemplate.getId(), result, systemPlaceholders);

            // 统计低置信度数量
            int lowConfidenceCount = result.getPendingConfirmList() != null
                    ? result.getPendingConfirmList().size() : 0;

            // 更新 Redis 任务状态为完成
            String taskValue = buildTaskDoneValue(companyTemplate.getId(),
                    result.getMatchedCount(), lowConfidenceCount, usedLlm);
            redisTemplate.opsForValue().set(TASK_KEY_PREFIX + taskId, taskValue, TASK_EXPIRE_HOURS, TimeUnit.HOURS);

            log.info("[LlmReverseOrchestrator] 反向生成完成: taskId={}, matched={}, lowConfidence={}, usedLlm={}",
                    taskId, result.getMatchedCount(), lowConfidenceCount, usedLlm);

        } catch (Exception e) {
            log.error("[LlmReverseOrchestrator] 反向生成失败: taskId={}, error={}", taskId, e.getMessage(), e);
            redisTemplate.opsForValue().set(TASK_KEY_PREFIX + taskId,
                    "failed:" + e.getMessage(), TASK_EXPIRE_HOURS, TimeUnit.HOURS);
        } finally {
            // 清理临时文件
            cleanTempDir(tmpDir);
            TenantContext.clear();
        }
    }

    /**
     * 查询任务状态（供轮询接口使用）
     *
     * @param taskId 任务ID
     * @return 任务状态字符串，null 表示任务不存在或已过期
     */
    public String getTaskStatus(String taskId) {
        return redisTemplate.opsForValue().get(TASK_KEY_PREFIX + taskId);
    }

    /**
     * 将任务状态设为 processing（由 Controller 在提交异步任务前调用）
     */
    public void markProcessing(String taskId, String companyTemplateId) {
        String value = "processing:" + companyTemplateId;
        redisTemplate.opsForValue().set(TASK_KEY_PREFIX + taskId, value, TASK_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    // ========== 内部方法 ==========

    /**
     * 初始化模块和占位符记录（复用旧逻辑，适配大模型引擎结果）
     */
    private void initModulesAndPlaceholders(String templateId,
                                              ReverseTemplateEngine.ReverseResult result,
                                              List<SystemPlaceholder> systemPlaceholders) {
        List<ReverseTemplateEngine.MatchedPlaceholder> matchedList = result.getAllMatchedPlaceholders();
        if (matchedList == null || matchedList.isEmpty()) {
            log.info("[LlmReverseOrchestrator] 没有匹配到任何占位符，跳过模块初始化: templateId={}", templateId);
            return;
        }

        // 构建系统占位符名称到对象的映射（降级引擎时使用）
        Map<String, SystemPlaceholder> systemPhMap = systemPlaceholders != null
                ? systemPlaceholders.stream().collect(Collectors.toMap(SystemPlaceholder::getName, ph -> ph, (a, b) -> a))
                : Collections.emptyMap();

        // 提取并创建模块
        List<String> placeholderNames = matchedList.stream()
                .map(ReverseTemplateEngine.MatchedPlaceholder::getPlaceholderName)
                .distinct().toList();

        List<ReverseTemplateEngine.ModuleInfo> moduleInfos = ReverseTemplateEngine.extractModules(placeholderNames);

        Map<String, String> codeToModuleId = new HashMap<>();
        int moduleSort = 0;
        for (ReverseTemplateEngine.ModuleInfo info : moduleInfos) {
            CompanyTemplateModule module = moduleService.getOrCreate(
                    templateId, info.getCode(), info.getName(), moduleSort++);
            codeToModuleId.put(info.getCode(), module.getId());
        }

        // 创建占位符记录
        Set<String> processedPhNames = new HashSet<>();
        int phSort = 0;

        for (ReverseTemplateEngine.MatchedPlaceholder matched : matchedList) {
            String phName = matched.getPlaceholderName();
            if (processedPhNames.contains(phName)) continue;
            processedPhNames.add(phName);

            String moduleCode = matched.getModuleCode();
            String moduleId = codeToModuleId.get(moduleCode);
            if (moduleId == null) {
                log.warn("[LlmReverseOrchestrator] 模块未找到: code={}, placeholder={}", moduleCode, phName);
                continue;
            }

            SystemPlaceholder systemPh = systemPhMap.get(phName);

            CompanyTemplatePlaceholder ph = new CompanyTemplatePlaceholder();
            ph.setId(UUID.randomUUID().toString());
            ph.setCompanyTemplateId(templateId);
            ph.setModuleId(moduleId);
            ph.setPlaceholderName(phName);
            // 大模型已生成语义化名称（phName 格式为 list-sheetName-fieldAddr，取最后段）
            ph.setName(resolveDisplayName(phName, systemPh, matched));
            ph.setStatus(matched.getStatus());
            ph.setExpectedValue(matched.getExpectedValue());
            ph.setActualValue(matched.getActualValue());
            ph.setReason("uncertain".equals(matched.getStatus()) ? matched.getLocation() : null);
            ph.setPositionJson(matched.getPositionJson());
            ph.setSort(phSort++);
            ph.setCreatedAt(LocalDateTime.now());
            ph.setUpdatedAt(LocalDateTime.now());

            if (systemPh != null) {
                ph.setType(systemPh.getType());
                ph.setDataSource(systemPh.getDataSource());
                ph.setSourceSheet(systemPh.getSourceSheet());
                ph.setSourceField(systemPh.getSourceField());
                ph.setDescription(systemPh.getDescription());
            } else {
                // 大模型引擎：从 placeholderName 解析 dataSource/sheetName/fieldAddr
                String[] parts = phName.split("-", 3);
                ph.setDataSource(parts.length >= 1 ? parts[0] : null);
                ph.setSourceSheet(parts.length >= 2 ? parts[1] : matched.getModuleName());
                ph.setSourceField(parts.length >= 3 ? parts[2] : null);
                ph.setType(guessType(matched));
            }

            // 从 PendingConfirmList 中找到对应的 confidence（低置信度项才有）
            double confidence = resolveConfidence(phName, result.getPendingConfirmList(), matched.getStatus());
            ph.setConfidence((float) confidence);

            placeholderService.savePlaceholder(ph);
        }

        log.info("[LlmReverseOrchestrator] 模块和占位符已初始化: templateId={}, modules={}, placeholders={}",
                templateId, moduleInfos.size(), processedPhNames.size());
    }

    /**
     * 解析显示名称：优先使用大模型给出的业务名（从 moduleName 推断），其次系统占位符 displayName，最后用 phName 最后一段
     */
    private String resolveDisplayName(String phName, SystemPlaceholder systemPh,
                                        ReverseTemplateEngine.MatchedPlaceholder matched) {
        if (systemPh != null && systemPh.getDisplayName() != null) {
            return systemPh.getDisplayName();
        }
        // 大模型识别的 moduleName 实际存储了 displayName（见 LlmReverseEngine.buildReverseResult）
        if (matched.getModuleName() != null && !matched.getModuleName().isBlank()
                && !"未知模块".equals(matched.getModuleName())) {
            return matched.getModuleName();
        }
        // fallback：取 phName 最后一段
        String[] parts = phName.split("-");
        return parts[parts.length - 1];
    }

    private String guessType(ReverseTemplateEngine.MatchedPlaceholder matched) {
        // 根据 location 或 positionJson 简单推断
        String posJson = matched.getPositionJson();
        if (posJson != null && posJson.contains("\"elementType\":\"table\"")) return "table";
        return "text";
    }

    /**
     * 从 pendingConfirmList 中查找指定占位符的置信度
     * 高置信度（confirmed）的默认返回 0.9
     */
    private double resolveConfidence(String phName,
                                      List<ReverseTemplateEngine.PendingConfirmItem> pendingList,
                                      String status) {
        if ("confirmed".equals(status)) return 0.9; // 高置信度的默认值
        if (pendingList != null) {
            for (ReverseTemplateEngine.PendingConfirmItem item : pendingList) {
                if (phName.equals(item.getPlaceholderName())) {
                    // reason 格式："低置信度(0.65)，建议复查"
                    String reason = item.getReason();
                    if (reason != null) {
                        try {
                            int start = reason.indexOf('(');
                            int end = reason.indexOf(')');
                            if (start >= 0 && end > start) {
                                return Double.parseDouble(reason.substring(start + 1, end));
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                    return 0.5; // 低置信度默认值
                }
            }
        }
        return 0.5;
    }

    private String buildTaskDoneValue(String templateId, int matchedCount, int lowConfidenceCount, boolean usedLlm) {
        return String.format("done:%s:matched=%d:lowConfidence=%d:engine=%s",
                templateId, matchedCount, lowConfidenceCount, usedLlm ? "llm" : "fallback");
    }

    private String toAbsPath(String relPath) {
        return Paths.get(uploadDir).resolve(relPath).normalize().toAbsolutePath().toString();
    }

    private void cleanTempDir(String tmpDir) {
        if (tmpDir == null || tmpDir.isBlank()) return;
        try {
            java.io.File dir = Paths.get(uploadDir, tmpDir).toFile();
            if (dir.exists()) {
                for (java.io.File f : Objects.requireNonNull(dir.listFiles())) f.delete();
                dir.delete();
            }
        } catch (Exception e) {
            log.warn("[LlmReverseOrchestrator] 清理临时目录失败: tmpDir={}, error={}", tmpDir, e.getMessage());
        }
    }
}
