package com.fileproc.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fileproc.common.BizException;
import com.fileproc.common.TenantContext;
import com.fileproc.template.entity.CompanyTemplate;
import com.fileproc.template.entity.CompanyTemplateModule;
import com.fileproc.template.entity.CompanyTemplatePlaceholder;
import com.fileproc.template.mapper.CompanyTemplateMapper;
import com.fileproc.template.mapper.CompanyTemplateModuleMapper;
import com.fileproc.template.mapper.CompanyTemplatePlaceholderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 企业子模板占位符状态 Service
 * <p>
 * 负责：
 * 1. 初始化占位符状态（反向生成时）
 * 2. 查询占位符状态列表
 * 3. 更新占位符确认状态
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyTemplatePlaceholderService {

    private final CompanyTemplatePlaceholderMapper placeholderMapper;
    private final CompanyTemplateModuleMapper moduleMapper;
    private final CompanyTemplateMapper companyTemplateMapper;

    /**
     * 初始化子模板的占位符状态
     * <p>
     * 反向生成时调用，为每个占位符创建初始状态记录（status=uncertain）
     * </p>
     *
     * @param companyTemplateId 子模板ID
     * @param items             待确认的占位符列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void initPlaceholders(String companyTemplateId, List<com.fileproc.report.service.ReverseTemplateEngine.PendingConfirmItem> items) {
        // 先删除该子模板已有的占位符记录（防止重复初始化）
        placeholderMapper.delete(
                new LambdaQueryWrapper<CompanyTemplatePlaceholder>()
                        .eq(CompanyTemplatePlaceholder::getCompanyTemplateId, companyTemplateId)
        );

        // 批量插入新的占位符状态记录
        for (com.fileproc.report.service.ReverseTemplateEngine.PendingConfirmItem item : items) {
            CompanyTemplatePlaceholder ph = new CompanyTemplatePlaceholder();
            ph.setId(UUID.randomUUID().toString());
            ph.setCompanyTemplateId(companyTemplateId);
            ph.setPlaceholderName(item.getPlaceholderName());
            ph.setStatus("uncertain");
            ph.setExpectedValue(item.getExpectedValue());
            ph.setActualValue(item.getActualValue());
            ph.setReason(item.getReason());
            ph.setPositionJson(item.getPositionJson());
            ph.setCreatedAt(LocalDateTime.now());
            ph.setUpdatedAt(LocalDateTime.now());
            placeholderMapper.insert(ph);
        }

        log.info("[CompanyTemplatePlaceholderService] 占位符状态已初始化: templateId={}, count={}",
                companyTemplateId, items.size());
    }

    /**
     * 查询子模板的所有占位符状态
     */
    public List<CompanyTemplatePlaceholder> listByTemplateId(String companyTemplateId) {
        return placeholderMapper.selectByTemplateId(companyTemplateId);
    }

    /**
     * 查询子模板中待确认的占位符列表
     */
    public List<CompanyTemplatePlaceholder> listUncertainByTemplateId(String companyTemplateId) {
        return placeholderMapper.selectByTemplateIdAndStatus(companyTemplateId, "uncertain");
    }

    /**
     * 批量更新占位符确认状态
     * <p>
     * 用户在前端确认后调用，更新状态为 confirmed 或 ignored
     * </p>
     *
     * @param companyTemplateId 子模板ID
     * @param confirmItems      确认项列表（placeholderName + confirmedType）
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmPlaceholders(String companyTemplateId,
                                     List<com.fileproc.report.service.ReverseTemplateEngine.PendingConfirmItem> confirmItems) {
        for (com.fileproc.report.service.ReverseTemplateEngine.PendingConfirmItem item : confirmItems) {
            CompanyTemplatePlaceholder ph = placeholderMapper.selectByTemplateIdAndName(
                    companyTemplateId, item.getPlaceholderName());
            if (ph == null) {
                log.warn("[CompanyTemplatePlaceholderService] 占位符不存在: templateId={}, name={}",
                        companyTemplateId, item.getPlaceholderName());
                continue;
            }

            // 处理 confirmed 和 confirmedType 字段
            String confirmedType = item.getConfirmedType();
            if (item.isConfirmed()) {
                // confirmed=true 时，检查 confirmedType
                if ("ignore".equals(confirmedType)) {
                    // 标记为忽略
                    ph.setStatus("ignored");
                    ph.setConfirmedType(null);
                } else {
                    // 正常确认，记录类型
                    ph.setStatus("confirmed");
                    ph.setConfirmedType(confirmedType);
                }
            } else {
                // confirmed=false，保持 uncertain 或设为 ignored（根据业务需求）
                ph.setStatus("ignored");
                ph.setConfirmedType(null);
            }
            ph.setUpdatedAt(LocalDateTime.now());
            placeholderMapper.updateById(ph);
        }

        log.info("[CompanyTemplatePlaceholderService] 占位符确认完成: templateId={}, count={}",
                companyTemplateId, confirmItems.size());
    }

    /**
     * 检查是否还有未确认的占位符
     *
     * @return 未确认的占位符数量
     */
    public int countUncertain(String companyTemplateId) {
        return placeholderMapper.countByTemplateIdAndStatus(companyTemplateId, "uncertain");
    }

    /**
     * 删除子模板的所有占位符状态记录
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByTemplateId(String companyTemplateId) {
        placeholderMapper.delete(
                new LambdaQueryWrapper<CompanyTemplatePlaceholder>()
                        .eq(CompanyTemplatePlaceholder::getCompanyTemplateId, companyTemplateId)
        );
        log.info("[CompanyTemplatePlaceholderService] 占位符状态已删除: templateId={}", companyTemplateId);
    }

    /**
     * 保存占位符记录（用于反向生成时批量保存）
     */
    @Transactional(rollbackFor = Exception.class)
    public void savePlaceholder(CompanyTemplatePlaceholder placeholder) {
        placeholderMapper.insert(placeholder);
    }

    // ========== 新增方法：模块化管理 ==========

    /**
     * 根据模块ID查询占位符列表
     * <p>
     * 会校验模块是否存在
     * </p>
     */
    public List<CompanyTemplatePlaceholder> listByModuleId(String moduleId) {
        // 校验模块是否存在
        CompanyTemplateModule module = moduleMapper.selectById(moduleId);
        if (module == null) {
            throw BizException.notFound("模块");
        }
        return placeholderMapper.selectByModuleId(moduleId);
    }

    /**
     * 根据模块ID查询占位符列表（带模板ID校验）
     * <p>
     * 同时校验模块是否存在且属于指定模板
     * </p>
     */
    public List<CompanyTemplatePlaceholder> listByModuleIdAndTemplateId(String moduleId, String templateId) {
        // 校验模块是否存在且属于该模板
        CompanyTemplateModule module = moduleMapper.selectById(moduleId);
        if (module == null) {
            throw BizException.notFound("模块");
        }
        if (!templateId.equals(module.getCompanyTemplateId())) {
            throw BizException.forbidden("该模块不属于指定子模板");
        }
        return placeholderMapper.selectByModuleId(moduleId);
    }

    /**
     * 更新占位符元数据（名称、类型、数据源等）
     *
     * @param placeholderId 占位符ID
     * @param updateRequest 更新内容
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyTemplatePlaceholder updateMetadata(String placeholderId, PlaceholderUpdateRequest updateRequest) {
        CompanyTemplatePlaceholder ph = placeholderMapper.selectById(placeholderId);
        if (ph == null) throw BizException.notFound("占位符");

        if (updateRequest.name() != null) ph.setName(updateRequest.name());
        if (updateRequest.type() != null) ph.setType(updateRequest.type());
        if (updateRequest.dataSource() != null) ph.setDataSource(updateRequest.dataSource());
        if (updateRequest.sourceSheet() != null) ph.setSourceSheet(updateRequest.sourceSheet());
        if (updateRequest.sourceField() != null) ph.setSourceField(updateRequest.sourceField());
        if (updateRequest.description() != null) ph.setDescription(updateRequest.description());
        if (updateRequest.sort() != null) ph.setSort(updateRequest.sort());

        ph.setUpdatedAt(LocalDateTime.now());
        placeholderMapper.updateById(ph);

        log.info("[CompanyTemplatePlaceholderService] 占位符已更新: id={}", placeholderId);
        return ph;
    }

    /**
     * 更新占位符元数据（带模板ID校验）
     *
     * @param placeholderId 占位符ID
     * @param templateId 子模板ID（用于校验归属）
     * @param updateRequest 更新内容
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyTemplatePlaceholder updateMetadata(String placeholderId, String templateId, PlaceholderUpdateRequest updateRequest) {
        CompanyTemplatePlaceholder ph = placeholderMapper.selectById(placeholderId);
        if (ph == null) throw BizException.notFound("占位符");

        // 校验占位符是否属于指定模板
        if (!templateId.equals(ph.getCompanyTemplateId())) {
            throw BizException.forbidden("该占位符不属于指定子模板");
        }

        if (updateRequest.name() != null) ph.setName(updateRequest.name());
        if (updateRequest.type() != null) ph.setType(updateRequest.type());
        if (updateRequest.dataSource() != null) ph.setDataSource(updateRequest.dataSource());
        if (updateRequest.sourceSheet() != null) ph.setSourceSheet(updateRequest.sourceSheet());
        if (updateRequest.sourceField() != null) ph.setSourceField(updateRequest.sourceField());
        if (updateRequest.description() != null) ph.setDescription(updateRequest.description());
        if (updateRequest.sort() != null) ph.setSort(updateRequest.sort());

        ph.setUpdatedAt(LocalDateTime.now());
        placeholderMapper.updateById(ph);

        log.info("[CompanyTemplatePlaceholderService] 占位符已更新: id={}, templateId={}", placeholderId, templateId);
        return ph;
    }

    /**
     * 批量同步占位符到其他子模板
     * <p>
     * 按 module.code + placeholder_name 匹配，匹配不到则跳过
     * </p>
     *
     * @param sourceTemplateId 源子模板ID
     * @param targetTemplateIds 目标子模板ID列表
     * @param placeholderIds   要同步的占位符ID列表（空表示同步全部）
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult syncPlaceholders(String sourceTemplateId, List<String> targetTemplateIds, List<String> placeholderIds) {
        // 1. 校验源模板
        CompanyTemplate sourceTemplate = companyTemplateMapper.selectById(sourceTemplateId);
        if (sourceTemplate == null) throw BizException.notFound("源子模板");
        checkTenant(sourceTemplate);

        // 2. 校验目标模板
        List<CompanyTemplate> targetTemplates = new ArrayList<>();
        for (String targetId : targetTemplateIds) {
            CompanyTemplate target = companyTemplateMapper.selectById(targetId);
            if (target == null) {
                log.warn("[CompanyTemplatePlaceholderService] 目标子模板不存在: {}", targetId);
                continue;
            }
            checkTenant(target);
            targetTemplates.add(target);
        }

        if (targetTemplates.isEmpty()) {
            return new SyncResult(0, 0, 0, "没有有效的目标子模板");
        }

        // 3. 获取源模板的占位符（包含模块信息）
        List<Map<String, Object>> sourcePlaceholdersWithModule = placeholderMapper.selectWithModuleByTemplateId(sourceTemplateId);

        // 4. 过滤指定的占位符
        if (placeholderIds != null && !placeholderIds.isEmpty()) {
            sourcePlaceholdersWithModule = sourcePlaceholdersWithModule.stream()
                    .filter(p -> placeholderIds.contains(p.get("id")))
                    .toList();
        }

        int totalMatched = 0;
        int totalUpdated = 0;
        int totalSkipped = 0;

        // 5. 遍历目标模板进行同步
        for (CompanyTemplate targetTemplate : targetTemplates) {
            SyncStats stats = syncToTarget(sourcePlaceholdersWithModule, targetTemplate);
            totalMatched += stats.matched();
            totalUpdated += stats.updated();
            totalSkipped += stats.skipped();
        }

        log.info("[CompanyTemplatePlaceholderService] 占位符同步完成: source={}, targets={}, matched={}, updated={}, skipped={}",
                sourceTemplateId, targetTemplates.size(), totalMatched, totalUpdated, totalSkipped);

        return new SyncResult(totalMatched, totalUpdated, totalSkipped, null);
    }

    /**
     * 同步到单个目标模板（使用批量更新）
     */
    private SyncStats syncToTarget(List<Map<String, Object>> sourcePlaceholders, CompanyTemplate targetTemplate) {
        int matched = 0;
        int skipped = 0;

        // 获取目标模板的所有模块
        List<CompanyTemplateModule> targetModules = moduleMapper.selectByTemplateId(targetTemplate.getId());
        Map<String, CompanyTemplateModule> moduleCodeMap = targetModules.stream()
                .collect(Collectors.toMap(CompanyTemplateModule::getCode, m -> m));

        // 收集需要更新的占位符列表
        List<CompanyTemplatePlaceholder> toUpdate = new ArrayList<>();

        for (Map<String, Object> source : sourcePlaceholders) {
            String moduleCode = (String) source.get("module_code");
            String placeholderName = (String) source.get("placeholder_name");

            // 查找目标模板中相同code的模块
            CompanyTemplateModule targetModule = moduleCodeMap.get(moduleCode);
            if (targetModule == null) {
                log.debug("[CompanyTemplatePlaceholderService] 目标模板缺少模块: target={}, moduleCode={}",
                        targetTemplate.getId(), moduleCode);
                skipped++;
                continue;
            }

            // 在目标模块下查找相同名称的占位符
            CompanyTemplatePlaceholder targetPh = placeholderMapper.selectByTemplateIdAndModuleCodeAndName(
                    targetTemplate.getId(), moduleCode, placeholderName);

            if (targetPh == null) {
                log.debug("[CompanyTemplatePlaceholderService] 目标模板缺少占位符: target={}, moduleCode={}, name={}",
                        targetTemplate.getId(), moduleCode, placeholderName);
                skipped++;
                continue;
            }

            matched++;

            // 更新元数据（不立即执行，先收集）
            targetPh.setName((String) source.get("name"));
            targetPh.setType((String) source.get("type"));
            targetPh.setDataSource((String) source.get("data_source"));
            targetPh.setSourceSheet((String) source.get("source_sheet"));
            targetPh.setSourceField((String) source.get("source_field"));
            targetPh.setDescription((String) source.get("description"));
            targetPh.setSort((Integer) source.get("sort"));
            targetPh.setUpdatedAt(LocalDateTime.now());

            toUpdate.add(targetPh);
        }

        // 批量更新
        if (!toUpdate.isEmpty()) {
            placeholderMapper.batchUpdateMetadata(toUpdate);
        }

        return new SyncStats(matched, toUpdate.size(), skipped);
    }

    private void checkTenant(CompanyTemplate template) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.equals(template.getTenantId())) {
            throw BizException.forbidden("无权访问该子模板");
        }
    }

    // ========== DTO ==========

    /**
     * 占位符更新请求
     */
    public record PlaceholderUpdateRequest(
            String name,
            String type,
            String dataSource,
            String sourceSheet,
            String sourceField,
            String description,
            Integer sort
    ) {}

    /**
     * 同步结果
     */
    public record SyncResult(int matched, int updated, int skipped, String message) {}

    private record SyncStats(int matched, int updated, int skipped) {}
}
