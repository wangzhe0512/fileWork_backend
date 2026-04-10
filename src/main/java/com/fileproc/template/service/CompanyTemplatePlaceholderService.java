package com.fileproc.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fileproc.common.BizException;
import com.fileproc.common.TenantContext;
import com.fileproc.registry.entity.PlaceholderRegistry;
import com.fileproc.registry.mapper.PlaceholderRegistryMapper;
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
    private final PlaceholderRegistryMapper placeholderRegistryMapper;

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
            // 使用displayName作为name，与模板中的占位符格式保持一致
            ph.setName(item.getPlaceholderName());
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

    // ========== 新增方法：绑定状态 ==========

    /**
     * 查询子模板占位符（按 placeholderName 聚合），附带绑定状态、positionCount、registryLevel
     * <p>
     * - positionCount：该占位符在文档中出现的总次数（同 templateId 下同名记录数）
     * - registryLevel：关联注册表的级别（system/company/custom，查不到则为 custom）
     * - bindingStatus：sourceSheet 和 sourceField 同时不为空则为 bound
     * </p>
     *
     * @param templateId 子模板ID
     * @param companyId  企业ID（用于查注册表级别，可为 null）
     * @return 按 placeholderName 聚合后的占位符列表
     */
    public List<PlaceholderGroupVO> listWithBindingStatus(String templateId, String companyId) {
        List<CompanyTemplatePlaceholder> list = placeholderMapper.selectByTemplateId(templateId);

        // 按 placeholderName 分组
        Map<String, List<CompanyTemplatePlaceholder>> grouped = list.stream()
                .collect(Collectors.groupingBy(CompanyTemplatePlaceholder::getPlaceholderName));

        // 查注册表级别（缓存）
        Map<String, String> levelCache = new HashMap<>();
        // 查注册表原始 phType（缓存，与 levelCache 共享同一次查询结果）
        Map<String, String> phTypeCache = new HashMap<>();
        // 查注册表条目ID（缓存，与 levelCache 共享同一次查询结果）
        Map<String, String> registryItemIdCache = new HashMap<>();
        // 查注册表可读展示名（缓存，与 levelCache 共享同一次查询结果）
        Map<String, String> displayNameCache = new HashMap<>();
        // 查注册表标准名（缓存，与 levelCache 共享同一次查询结果）
        Map<String, String> standardNameCache = new HashMap<>();

        return grouped.entrySet().stream().map(entry -> {
            String placeholderName = entry.getKey();
            List<CompanyTemplatePlaceholder> positions = entry.getValue();
            CompanyTemplatePlaceholder first = positions.get(0);

            // positionCount = 同名记录数
            int positionCount = positions.size();

            // bindingStatus：只要有一条记录绑定了就算 bound
            boolean isBound = positions.stream().anyMatch(ph ->
                    ph.getSourceSheet() != null && !ph.getSourceSheet().isBlank()
                            && ph.getSourceField() != null && !ph.getSourceField().isBlank());
            String bindingStatus = isBound ? "bound" : "unbound";

            // status（确认状态）：有 confirmed 则为 confirmed，否则有 ignored 则为 ignored，否则为 uncertain
            String status = positions.stream()
                    .map(CompanyTemplatePlaceholder::getStatus)
                    .filter(s -> s != null && !s.isBlank())
                    .reduce("uncertain", (a, b) -> {
                        if ("confirmed".equals(a) || "confirmed".equals(b)) return "confirmed";
                        if ("ignored".equals(a) || "ignored".equals(b)) return "ignored";
                        return "uncertain";
                    });

            // registryLevel + phType（共享同一次注册表查询，零额外开销）
            String registryLevel = levelCache.computeIfAbsent(placeholderName, name -> {
                try {
                    PlaceholderRegistry reg = placeholderRegistryMapper.selectEffectiveByName(
                            name, companyId != null ? companyId : "");
                    if (reg != null) {
                        phTypeCache.put(name, reg.getPhType());
                        registryItemIdCache.put(name, reg.getId());
                        displayNameCache.put(name, reg.getDisplayName());
                        // 缓存标准名
                        standardNameCache.put(name, reg.getPlaceholderName());
                        return reg.getLevel();
                    }
                    return "custom";
                } catch (Exception e) {
                    return "custom";
                }
            });
            String phType = phTypeCache.get(placeholderName);
            String registryItemId = registryItemIdCache.get(placeholderName);
            String cachedDisplayName = displayNameCache.get(placeholderName);
            String resolvedName = (cachedDisplayName != null && !cachedDisplayName.isBlank())
                    ? cachedDisplayName : first.getName();
            // 获取标准名
            String standardName = standardNameCache.get(placeholderName);

            return new PlaceholderGroupVO(
                    first.getId(),
                    first.getCompanyTemplateId(),
                    first.getModuleId(),
                    resolvedName,           // name = 展示名
                    standardName,           // standardName = 标准名
                    resolvedName,           // placeholderName = 占位符标记名（与展示名一致）
                    first.getType(),
                    first.getDataSource(),
                    first.getSourceSheet(),
                    first.getSourceField(),
                    first.getDescription(),
                    first.getSort(),
                    bindingStatus,
                    status,
                    positionCount,
                    registryLevel,
                    phType,
                    registryItemId,
                    positions
            );
        }).collect(Collectors.toList());
    }

    /**
     * 专用绑定/解绑方法：仅更新 sourceSheet 和 sourceField 两个字段
     * <p>
     * 解绑时两个字段均传 null 即可
     * </p>
     *
     * @param phId        占位符ID
     * @param templateId  子模板ID（用于归属校验）
     * @param sourceSheet 来源Sheet（null 表示清空）
     * @param sourceField 来源字段（null 表示清空）
     * @return 更新后的占位符记录
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyTemplatePlaceholder updateBinding(String phId, String templateId,
                                                     String sourceSheet, String sourceField) {
        CompanyTemplatePlaceholder ph = placeholderMapper.selectById(phId);
        if (ph == null) throw BizException.notFound("占位符");
        if (!templateId.equals(ph.getCompanyTemplateId())) {
            throw BizException.forbidden("该占位符不属于指定子模板");
        }

        // 允许显式传 null 清空（解绑）
        ph.setSourceSheet(sourceSheet);
        ph.setSourceField(sourceField);
        ph.setUpdatedAt(LocalDateTime.now());
        placeholderMapper.updateById(ph);

        log.info("[CompanyTemplatePlaceholderService] 占位符绑定已更新: id={}, sourceSheet={}, sourceField={}",
                phId, sourceSheet, sourceField);
        return ph;
    }

    /**
     * 从占位符库（注册表）中选已有规则，添加到指定子模板
     * <p>
     * 步骤：
     * 1. 查询注册表条目是否存在
     * 2. 检查同一 templateId 下 placeholder_name 是否已存在（存在则抛 400）
     * 3. 新建 company_template_placeholder 记录，sourceSheet/sourceField 从注册表复制
     * </p>
     *
     * @param templateId  子模板ID
     * @param registryId  注册表条目ID
     * @param moduleId    所属模块ID（可为 null）
     * @return 新建的占位符实例
     */
    @Transactional(rollbackFor = Exception.class)
    public CompanyTemplatePlaceholder addFromRegistry(String templateId, String registryId, String moduleId) {
        // 1. 查注册表条目
        PlaceholderRegistry reg = placeholderRegistryMapper.selectActiveById(registryId);
        if (reg == null) throw BizException.notFound("注册表条目");

        // 2. 校验重复
        CompanyTemplatePlaceholder existing = placeholderMapper.selectByTemplateIdAndName(templateId, reg.getPlaceholderName());
        if (existing != null) {
            throw BizException.of(400, "该占位符已存在于当前子模板，不能重复添加：" + reg.getPlaceholderName());
        }

        // 3. 新建占位符实例
        CompanyTemplatePlaceholder ph = new CompanyTemplatePlaceholder();
        ph.setId(UUID.randomUUID().toString());
        ph.setCompanyTemplateId(templateId);
        ph.setModuleId(moduleId);
        ph.setPlaceholderName(reg.getPlaceholderName());
        ph.setName(reg.getDisplayName() != null ? reg.getDisplayName() : reg.getPlaceholderName());
        ph.setType(mapPhType(reg.getPhType()));
        ph.setDataSource(reg.getDataSource());
        ph.setSourceSheet(reg.getSheetName());
        ph.setSourceField(reg.getCellAddress());
        ph.setStatus("confirmed");
        ph.setCreatedAt(LocalDateTime.now());
        ph.setUpdatedAt(LocalDateTime.now());
        placeholderMapper.insert(ph);

        log.info("[CompanyTemplatePlaceholderService] 从注册表添加占位符: templateId={}, registryId={}, name={}",
                templateId, registryId, reg.getPlaceholderName());
        return ph;
    }

    /**
     * 全新新建：同时创建企业级注册表规则 + 子模板占位符实例
     * <p>
     * 与 createRegistryAndBind 的区别：本方法不依赖已存在的占位符记录，
     * 直接从零创建一条注册表规则和一条占位符实例。
     * </p>
     *
     * @param templateId 子模板ID
     * @param request    新建请求
     * @return 新建的注册表条目 + 占位符实例
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createNewWithRegistry(String templateId, CreateNewWithRegistryRequest request) {
        // 1. 校验占位符名称在该子模板下不重复
        CompanyTemplatePlaceholder existing = placeholderMapper.selectByTemplateIdAndName(templateId, request.placeholderName());
        if (existing != null) {
            throw BizException.of(400, "该占位符已存在于当前子模板，不能重复添加：" + request.placeholderName());
        }

        // 2. 构造企业级注册表条目
        PlaceholderRegistry entry = new PlaceholderRegistry();
        entry.setLevel("company");
        entry.setCompanyId(request.companyId());
        entry.setPlaceholderName(request.placeholderName());
        entry.setDisplayName(request.displayName() != null ? request.displayName() : request.placeholderName());
        entry.setPhType(request.phType());
        entry.setDataSource(request.dataSource());
        entry.setSheetName(request.sheetName());
        entry.setCellAddress(request.cellAddress());
        entry.setEnabled(1);

        // 3. 保存注册表条目（内部已做重复校验）
        entry.setId(null);
        entry.setTenantId(TenantContext.getTenantId());
        entry.setCreatedAt(LocalDateTime.now());
        entry.setUpdatedAt(LocalDateTime.now());
        entry.setDeleted(0);
        placeholderRegistryMapper.insert(entry);

        // 4. 新建占位符实例
        CompanyTemplatePlaceholder ph = new CompanyTemplatePlaceholder();
        ph.setId(UUID.randomUUID().toString());
        ph.setCompanyTemplateId(templateId);
        ph.setModuleId(request.moduleId());
        ph.setPlaceholderName(request.placeholderName());
        ph.setName(entry.getDisplayName());
        ph.setType(mapPhType(request.phType()));
        ph.setDataSource(request.dataSource());
        ph.setSourceSheet(request.sheetName());
        ph.setSourceField(request.cellAddress());
        ph.setStatus("confirmed");
        ph.setCreatedAt(LocalDateTime.now());
        ph.setUpdatedAt(LocalDateTime.now());
        placeholderMapper.insert(ph);

        log.info("[CompanyTemplatePlaceholderService] 新建占位符+注册表规则: templateId={}, name={}",
                templateId, request.placeholderName());
        return Map.of("registryEntry", entry, "placeholder", ph);
    }

    /**
     * 将注册表 phType 映射为子模板占位符 type 字段
     */
    private String mapPhType(String phType) {
        if (phType == null) return "text";
        return switch (phType) {
            case "TABLE_CLEAR", "TABLE_CLEAR_FULL", "TABLE_ROW_TEMPLATE" -> "table";
            case "LONG_TEXT" -> "text";
            case "BVD" -> "text";
            default -> "text";
        };
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
     * 占位符绑定请求
     */
    public record BindingRequest(
            String sourceSheet,
            String sourceField
    ) {}

    /**
     * 从注册表添加请求（指定注册表条目ID）
     */
    public record AddFromRegistryRequest(
            /** 注册表条目ID（必填） */
            String registryId,
            /** 所属模块ID（可为 null） */
            String moduleId
    ) {}

    /**
     * 全新新建占位符请求（创建注册表规则 + 占位符实例）
     */
    public record CreateNewWithRegistryRequest(
            /** 企业ID（必填） */
            String companyId,
            /** 占位符标准名，如 ${myField}（必填） */
            String placeholderName,
            /** 展示名（可选，默认等于 placeholderName） */
            String displayName,
            /** 占位符类型：DATA_CELL/TABLE_CLEAR/...（必填） */
            String phType,
            /** 数据来源：list / bvd（必填） */
            String dataSource,
            /** 来源 Sheet 名（必填） */
            String sheetName,
            /** 单元格坐标，如 B1（TABLE_CLEAR 类型可为空） */
            String cellAddress,
            /** 所属模块ID（可为 null） */
            String moduleId
    ) {}

    /**
     * 占位符分组 VO（按 placeholderName 聚合）
     * <p>
     * 用于占位符管理面板卡片展示，每个卡片代表一个占位符名称：
     * - positionCount：该占位符在文档中出现的总次数
     * - registryLevel：system / company / custom
     * - bindingStatus：bound / unbound
     * - positions：该占位符的所有位置记录列表
     * </p>
     */
    public static class PlaceholderGroupVO {
        private final String id;
        private final String companyTemplateId;
        private final String moduleId;
        /** 占位符展示名（用于Word模板内容，如"企业名称"） */
        private final String name;
        /** 占位符标准名（用于识别，如"清单模板-数据表-B1"） */
        private final String standardName;
        /** 占位符标记名（用于文档中实际显示的占位符，如"企业名称"，与name一致） */
        private final String placeholderName;
        private final String type;
        private final String dataSource;
        private final String sourceSheet;
        private final String sourceField;
        private final String description;
        private final Integer sort;
        private final String bindingStatus;
        /** 确认状态：confirmed(已确认) / uncertain(待确认) / ignored(忽略) */
        private final String status;
        private final int positionCount;
        private final String registryLevel;
        /** 注册表原始占位符类型，如 TABLE_ROW_TEMPLATE / TABLE_CLEAR / LONG_TEXT 等，查不到则为 null */
        private final String phType;
        /** 注册表条目主键ID，查不到则为 null */
        private final String registryItemId;
        private final List<CompanyTemplatePlaceholder> positions;

        public PlaceholderGroupVO(String id, String companyTemplateId, String moduleId,
                                   String name, String standardName, String placeholderName, String type,
                                   String dataSource, String sourceSheet, String sourceField,
                                   String description, Integer sort,
                                   String bindingStatus, String status,
                                   int positionCount, String registryLevel,
                                   String phType,
                                   String registryItemId,
                                   List<CompanyTemplatePlaceholder> positions) {
            this.id = id;
            this.companyTemplateId = companyTemplateId;
            this.moduleId = moduleId;
            this.name = name; // 展示名
            this.standardName = standardName; // 标准名
            this.placeholderName = placeholderName; // 占位符标记名（与name一致）
            this.type = type;
            this.dataSource = dataSource;
            this.sourceSheet = sourceSheet;
            this.sourceField = sourceField;
            this.description = description;
            this.sort = sort;
            this.bindingStatus = bindingStatus;
            this.status = status;
            this.positionCount = positionCount;
            this.registryLevel = registryLevel;
            this.phType = phType;
            this.registryItemId = registryItemId;
            this.positions = positions;
        }

        // Getters
        public String getId() { return id; }
        public String getCompanyTemplateId() { return companyTemplateId; }
        public String getModuleId() { return moduleId; }
        /** 获取占位符展示名（用于Word模板） */
        public String getName() { return name; }
        /** 获取占位符标准名（用于识别） */
        public String getStandardName() { return standardName; }
        /** 获取占位符标记名（用于文档中实际显示的占位符，与name一致） */
        public String getPlaceholderName() { return placeholderName; }
        public String getType() { return type; }
        public String getDataSource() { return dataSource; }
        public String getSourceSheet() { return sourceSheet; }
        public String getSourceField() { return sourceField; }
        public String getDescription() { return description; }
        public Integer getSort() { return sort; }
        public String getBindingStatus() { return bindingStatus; }
        public String getStatus() { return status; }
        public int getPositionCount() { return positionCount; }
        public String getRegistryLevel() { return registryLevel; }
        public String getPhType() { return phType; }
        public String getRegistryItemId() { return registryItemId; }
        public List<CompanyTemplatePlaceholder> getPositions() { return positions; }
    }

    /**
     * 占位符绑定状态 VO（单条记录，保留兼容）
     * <p>
     * 扩展 CompanyTemplatePlaceholder，追加以下字段：
     * - bindingStatus：bound / unbound
     * - positionCount：该占位符在文档中插入的总次数
     * - registryLevel：system / company / custom（查不到注册表时为 custom）
     * </p>
     */
    public static class PlaceholderBindingVO extends CompanyTemplatePlaceholder {
        private final String bindingStatus;
        private final int positionCount;
        private final String registryLevel;

        public PlaceholderBindingVO(CompanyTemplatePlaceholder ph, String bindingStatus,
                                    int positionCount, String registryLevel) {
            this.setId(ph.getId());
            this.setCompanyTemplateId(ph.getCompanyTemplateId());
            this.setModuleId(ph.getModuleId());
            this.setName(ph.getName());
            this.setType(ph.getType());
            this.setDataSource(ph.getDataSource());
            this.setSourceSheet(ph.getSourceSheet());
            this.setSourceField(ph.getSourceField());
            this.setDescription(ph.getDescription());
            this.setSort(ph.getSort());
            this.setPlaceholderName(ph.getPlaceholderName());
            this.setStatus(ph.getStatus());
            this.setConfirmedType(ph.getConfirmedType());
            this.setPositionJson(ph.getPositionJson());
            this.setExpectedValue(ph.getExpectedValue());
            this.setActualValue(ph.getActualValue());
            this.setReason(ph.getReason());
            this.setConfidence(ph.getConfidence());
            this.setCreatedAt(ph.getCreatedAt());
            this.setUpdatedAt(ph.getUpdatedAt());
            this.bindingStatus = bindingStatus;
            this.positionCount = positionCount;
            this.registryLevel = registryLevel;
        }

        /** 兼容旧构造（positionCount=1, registryLevel=custom） */
        public PlaceholderBindingVO(CompanyTemplatePlaceholder ph, String bindingStatus) {
            this(ph, bindingStatus, 1, "custom");
        }

        public String getBindingStatus() { return bindingStatus; }
        public int getPositionCount() { return positionCount; }
        public String getRegistryLevel() { return registryLevel; }
    }

    /**
     * 同步结果
     */
    public record SyncResult(int matched, int updated, int skipped, String message) {}

    private record SyncStats(int matched, int updated, int skipped) {}
}
