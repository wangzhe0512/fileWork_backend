package com.fileproc.registry.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileproc.common.BizException;
import com.fileproc.common.TenantContext;
import com.fileproc.registry.entity.PlaceholderRegistry;
import com.fileproc.registry.mapper.PlaceholderRegistryMapper;
import com.fileproc.report.service.ReverseTemplateEngine;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 占位符注册表服务
 * <p>
 * 实现两级优先链：企业级（company）> 系统级（system），同一 placeholderName 企业级覆盖系统级。
 * {@link #getEffectiveRegistry(String)} 返回 {@link ReverseTemplateEngine.RegistryEntry} 列表，
 * 引擎内部无需感知来源。
 * DB 查询异常时由引擎层回退静态列表。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceholderRegistryService {

    private final PlaceholderRegistryMapper placeholderRegistryMapper;
    private final ObjectMapper objectMapper;

    // ========== 核心接口 ==========

    /**
     * 获取有效注册表（企业级优先，系统级兜底），返回引擎可直接使用的 RegistryEntry 列表。
     * <p>
     * 优先链：
     * 1. 查询系统级 + 企业级所有条目
     * 2. 同一 placeholderName，企业级覆盖系统级；企业级 enabled=0 则该规则被禁用
     * 3. 最终按 sort 排序
     * </p>
     *
     * @param companyId 企业ID（null 时仅返回系统级规则）
     * @return 有效的 RegistryEntry 列表
     */
    public List<ReverseTemplateEngine.RegistryEntry> getEffectiveRegistry(String companyId) {
        List<PlaceholderRegistry> allEntries;
        if (companyId == null || companyId.isEmpty()) {
            allEntries = placeholderRegistryMapper.selectSystemEntries();
        } else {
            allEntries = placeholderRegistryMapper.selectEffectiveEntries(companyId);
        }

        // 合并：同一 placeholderName，企业级覆盖系统级
        // key = placeholderName，value = 最终生效条目
        Map<String, PlaceholderRegistry> merged = new LinkedHashMap<>();
        // 先放系统级（保证顺序），再用企业级覆盖
        for (PlaceholderRegistry entry : allEntries) {
            if ("system".equals(entry.getLevel())) {
                merged.put(entry.getPlaceholderName(), entry);
            }
        }
        for (PlaceholderRegistry entry : allEntries) {
            if ("company".equals(entry.getLevel())) {
                if (entry.getEnabled() != null && entry.getEnabled() == 0) {
                    // 企业级显式禁用该规则
                    merged.remove(entry.getPlaceholderName());
                } else {
                    merged.put(entry.getPlaceholderName(), entry);
                }
            }
        }

        // 按 sort 排序后转换为 RegistryEntry
        return merged.values().stream()
                .sorted(Comparator.comparingInt(e -> e.getSort() != null ? e.getSort() : 0))
                .map(this::toRegistryEntry)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 查询系统级条目列表
     */
    public List<PlaceholderRegistry> listSystemEntries() {
        return placeholderRegistryMapper.selectSystemEntries();
    }

    /**
     * 查询企业级条目列表
     */
    public List<PlaceholderRegistry> listCompanyEntries(String companyId) {
        return placeholderRegistryMapper.selectCompanyEntries(companyId);
    }

    /**
     * 新建注册表条目
     */
    public PlaceholderRegistry saveEntry(PlaceholderRegistry entry) {
        validateEntry(entry);
        checkDuplicate(entry, null);

        entry.setId(null); // 让 MyBatis-Plus @TableId 生成 UUID
        if ("company".equals(entry.getLevel())) {
            entry.setTenantId(TenantContext.getTenantId());
        } else {
            entry.setTenantId(null);
            entry.setCompanyId(null);
        }
        entry.setEnabled(entry.getEnabled() != null ? entry.getEnabled() : 1);
        entry.setCreatedAt(LocalDateTime.now());
        entry.setUpdatedAt(LocalDateTime.now());
        entry.setDeleted(0);
        placeholderRegistryMapper.insert(entry);
        return entry;
    }

    /**
     * 更新注册表条目
     */
    public PlaceholderRegistry updateEntry(String id, PlaceholderRegistry update) {
        PlaceholderRegistry existing = placeholderRegistryMapper.selectById(id);
        if (existing == null) {
            throw BizException.notFound("注册表条目");
        }
        checkDuplicate(update, id);

        update.setId(id);
        update.setLevel(existing.getLevel());       // level 不允许修改
        update.setTenantId(existing.getTenantId()); // tenantId 不允许修改
        update.setUpdatedAt(LocalDateTime.now());
        placeholderRegistryMapper.updateById(update);
        return placeholderRegistryMapper.selectById(id);
    }

    /**
     * 删除注册表条目（软删除）
     */
    public void deleteEntry(String id) {
        PlaceholderRegistry existing = placeholderRegistryMapper.selectById(id);
        if (existing == null) {
            throw BizException.notFound("注册表条目");
        }
        LambdaUpdateWrapper<PlaceholderRegistry> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(PlaceholderRegistry::getId, id)
                .set(PlaceholderRegistry::getDeleted, 1)
                .set(PlaceholderRegistry::getUpdatedAt, LocalDateTime.now());
        placeholderRegistryMapper.update(null, wrapper);
    }

    /**
     * 基于系统级条目，为指定企业创建企业级覆盖条目
     * <p>
     * 复制系统级条目的所有字段，设 level=company + companyId，只允许传入需要覆盖的字段。
     * 若该企业已有同名企业级条目，抛出 400（调用方可提示"已有覆盖条目，请直接编辑"）。
     * </p>
     *
     * @param systemEntryId 系统级条目ID
     * @param companyId     企业ID
     * @param overrides     需要覆盖的字段（null 表示保持原值）
     * @return 新建的企业级覆盖条目
     */
    public PlaceholderRegistry overrideForCompany(String systemEntryId, String companyId,
                                                   PlaceholderRegistry overrides) {
        PlaceholderRegistry system = placeholderRegistryMapper.selectById(systemEntryId);
        if (system == null) {
            throw BizException.notFound("系统级注册表条目");
        }
        if (!"system".equals(system.getLevel())) {
            throw BizException.of(400, "只能基于系统级条目创建覆盖，当前条目不是系统级");
        }
        if (companyId == null || companyId.trim().isEmpty()) {
            throw BizException.of(400, "companyId 不能为空");
        }

        // 复制系统级字段，然后应用覆盖
        PlaceholderRegistry company = new PlaceholderRegistry();
        company.setLevel("company");
        company.setCompanyId(companyId);
        company.setPlaceholderName(system.getPlaceholderName()); // 保持同名（覆盖语义）
        company.setDisplayName(overrides != null && overrides.getDisplayName() != null
                ? overrides.getDisplayName() : system.getDisplayName());
        company.setPhType(overrides != null && overrides.getPhType() != null
                ? overrides.getPhType() : system.getPhType());
        company.setDataSource(overrides != null && overrides.getDataSource() != null
                ? overrides.getDataSource() : system.getDataSource());
        company.setSheetName(overrides != null && overrides.getSheetName() != null
                ? overrides.getSheetName() : system.getSheetName());
        company.setCellAddress(overrides != null && overrides.getCellAddress() != null
                ? overrides.getCellAddress() : system.getCellAddress());
        company.setTitleKeywords(overrides != null && overrides.getTitleKeywords() != null
                ? overrides.getTitleKeywords() : system.getTitleKeywords());
        company.setColumnDefs(overrides != null && overrides.getColumnDefs() != null
                ? overrides.getColumnDefs() : system.getColumnDefs());
        company.setSort(system.getSort());
        company.setEnabled(1);

        // saveEntry 内部已做重复校验（同名企业级条目存在时抛 400）
        return saveEntry(company);
    }

    // ========== 内部工具 ==========

    /**
     * 将数据库实体转换为引擎使用的 RegistryEntry
     */
    private ReverseTemplateEngine.RegistryEntry toRegistryEntry(PlaceholderRegistry db) {
        try {
            ReverseTemplateEngine.PlaceholderType type =
                    ReverseTemplateEngine.PlaceholderType.valueOf(db.getPhType());

            List<String> titleKeywords = parseJsonList(db.getTitleKeywords());
            List<String> columnDefs = parseJsonList(db.getColumnDefs());
            List<String> availableColDefs = parseJsonList(db.getAvailableColDefs());

            return new ReverseTemplateEngine.RegistryEntry(
                    db.getPlaceholderName(),
                    db.getDisplayName(),
                    type,
                    db.getDataSource(),
                    db.getSheetName(),
                    db.getCellAddress(),
                    titleKeywords,
                    columnDefs,
                    availableColDefs
            );
        } catch (Exception e) {
            log.warn("[RegistryService] 条目转换失败，跳过: id={}, name={}, error={}",
                    db.getId(), db.getPlaceholderName(), e.getMessage());
            return null;
        }
    }

    /**
     * 解析 JSON 字符串为 List<String>，解析失败返回 null
     */
    private List<String> parseJsonList(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[RegistryService] JSON解析失败: {}", json);
            return null;
        }
    }

    /**
     * 校验必填字段
     */
    private void validateEntry(PlaceholderRegistry entry) {
        if (entry.getLevel() == null || (!entry.getLevel().equals("system") && !entry.getLevel().equals("company"))) {
            throw BizException.of(400, "level 只能为 system 或 company");
        }
        if (entry.getPlaceholderName() == null || entry.getPlaceholderName().trim().isEmpty()) {
            throw BizException.of(400, "占位符名称不能为空");
        }
        if (entry.getPhType() == null || entry.getPhType().trim().isEmpty()) {
            throw BizException.of(400, "占位符类型不能为空");
        }
        if ("company".equals(entry.getLevel()) && (entry.getCompanyId() == null || entry.getCompanyId().trim().isEmpty())) {
            throw BizException.of(400, "企业级条目必须指定 companyId");
        }
        // 校验 phType 是否合法
        try {
            ReverseTemplateEngine.PlaceholderType.valueOf(entry.getPhType());
        } catch (IllegalArgumentException e) {
            throw BizException.of(400, "不支持的占位符类型: " + entry.getPhType());
        }
    }

    /**
     * 校验 placeholderName 是否重复
     */
    private void checkDuplicate(PlaceholderRegistry entry, String excludeId) {
        PlaceholderRegistry existing;
        if ("system".equals(entry.getLevel())) {
            existing = placeholderRegistryMapper.selectSystemByName(entry.getPlaceholderName());
        } else {
            existing = placeholderRegistryMapper.selectCompanyByName(entry.getCompanyId(), entry.getPlaceholderName());
        }
        if (existing != null && !existing.getId().equals(excludeId)) {
            throw BizException.of(400, "占位符名称已存在: " + entry.getPlaceholderName());
        }
    }

    // ========== TABLE_ROW_TEMPLATE 列选择接口 ==========

    /**
     * 获取指定 TABLE_ROW_TEMPLATE 占位符的所有可选列定义（前端列选择器数据源）。
     * <p>
     * 通用接口，适用于所有 TABLE_ROW_TEMPLATE 类型的注册表条目。
     * 已选中状态（{@link ColumnDefItem#selected}）由该条目的有效 column_defs 决定：
     * 企业级覆盖条目存在则用企业级，否则用系统级条目自身的 column_defs。
     * 不硬编码默认选中列。
     * </p>
     *
     * @param registryId 系统级注册表条目 ID
     * @param companyId  企业ID（用于获取企业级已选列；null 时使用系统级 column_defs）
     * @return 可选列定义列表
     */
    public List<ColumnDefItem> getColumnDefItems(String registryId, String companyId) {
        // 查询系统级条目
        PlaceholderRegistry system = placeholderRegistryMapper.selectById(registryId);
        if (system == null) {
            throw BizException.notFound("注册表条目");
        }
        if (!"TABLE_ROW_TEMPLATE".equals(system.getPhType())) {
            throw BizException.of(400, "该接口仅支持 TABLE_ROW_TEMPLATE 类型的占位符，当前类型: " + system.getPhType());
        }

        // 获取有效 column_defs：企业级优先，系统级兜底
        List<String> effectiveColDefs = parseJsonList(system.getColumnDefs());
        if (effectiveColDefs == null) effectiveColDefs = Collections.emptyList();

        if (companyId != null && !companyId.trim().isEmpty()) {
            try {
                PlaceholderRegistry companyEntry = placeholderRegistryMapper
                        .selectCompanyByName(companyId, system.getPlaceholderName());
                if (companyEntry != null && companyEntry.getColumnDefs() != null) {
                    List<String> companyColDefs = parseJsonList(companyEntry.getColumnDefs());
                    if (companyColDefs != null && !companyColDefs.isEmpty()) {
                        effectiveColDefs = companyColDefs;
                    }
                }
            } catch (Exception e) {
                log.warn("[RegistryService] 读取企业级 column_defs 失败，使用系统级兜底: {}", e.getMessage());
            }
        }

        // 从系统级 available_col_defs 构建全量可选列（优先），为空时 fallback 到 column_defs（向后兼容）
        // 通用：适用于任意 TABLE_ROW_TEMPLATE 占位符，无需为每个占位符硬编码列元数据
        List<String> allCols = parseJsonList(system.getAvailableColDefs());
        if (allCols == null || allCols.isEmpty()) {
            allCols = parseJsonList(system.getColumnDefs());
        }
        if (allCols == null) allCols = Collections.emptyList();

        final List<String> finalEffectiveColDefs = effectiveColDefs;
        List<ColumnDefItem> result = new ArrayList<>();
        for (int i = 0; i < allCols.size(); i++) {
            String fieldKey = allCols.get(i);
            ColumnDefItem item = new ColumnDefItem();
            item.setFieldKey(fieldKey);
            item.setLabel(fieldKey);
            item.setColIndex(i);
            item.setSelected(finalEffectiveColDefs.contains(fieldKey));
            result.add(item);
        }
        return result;
    }

    /**
     * 保存企业级自定义 column_defs（方案C：前端勾选列后回调）。
     * <p>
     * 逻辑：
     * <ol>
     *   <li>若该企业已有同名企业级条目，直接更新 column_defs 字段</li>
     *   <li>否则，先基于系统级条目创建企业级覆盖条目（{@link #overrideForCompany}），再更新 column_defs</li>
     * </ol>
     * </p>
     *
     * @param systemRegistryId 系统级注册表条目ID
     * @param companyId        企业ID
     * @param columnDefs       自定义列字段名列表
     * @return 更新后的注册表条目
     */
    public PlaceholderRegistry updateColumnDefs(String systemRegistryId, String companyId,
                                                 List<String> columnDefs) {
        if (columnDefs == null || columnDefs.isEmpty()) {
            throw BizException.of(400, "columnDefs 不能为空");
        }

        // 查询系统级条目（用于获取 placeholderName）
        PlaceholderRegistry system = placeholderRegistryMapper.selectById(systemRegistryId);
        if (system == null) {
            throw BizException.notFound("系统级注册表条目");
        }

        // 序列化 columnDefs 为 JSON
        String columnDefsJson;
        try {
            columnDefsJson = objectMapper.writeValueAsString(columnDefs);
        } catch (Exception e) {
            throw BizException.of(400, "columnDefs 序列化失败: " + e.getMessage());
        }

        // 查找是否已有企业级条目
        PlaceholderRegistry companyEntry = placeholderRegistryMapper
                .selectCompanyByName(companyId, system.getPlaceholderName());

        if (companyEntry != null) {
            // 已有企业级条目：直接更新 column_defs
            LambdaUpdateWrapper<PlaceholderRegistry> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(PlaceholderRegistry::getId, companyEntry.getId())
                    .set(PlaceholderRegistry::getColumnDefs, columnDefsJson)
                    .set(PlaceholderRegistry::getUpdatedAt, java.time.LocalDateTime.now());
            placeholderRegistryMapper.update(null, wrapper);
            log.info("[RegistryService] 企业级 column_defs 已更新：companyId={}, name={}, defs={}",
                    companyId, system.getPlaceholderName(), columnDefsJson);
            return placeholderRegistryMapper.selectById(companyEntry.getId());
        } else {
            // 无企业级条目：先 override，再更新 column_defs
            PlaceholderRegistry overrides = new PlaceholderRegistry();
            overrides.setColumnDefs(columnDefsJson);
            PlaceholderRegistry created = overrideForCompany(systemRegistryId, companyId, overrides);
            log.info("[RegistryService] 企业级覆盖条目已创建并更新 column_defs：companyId={}, name={}, defs={}",
                    companyId, system.getPlaceholderName(), columnDefsJson);
            return created;
        }
    }

    // ========== 内部 DTO ==========

    /**
     * TABLE_ROW_TEMPLATE 占位符的可选列定义（前端列选择接口返回类型）
     */
    @Data
    public static class ColumnDefItem {
        /** 字段键（对应 column_defs 数组元素），如 "NCP_CURRENT" */
        private String fieldKey;
        /** Excel 原始列头文字，如 "2020-2022 NCP" */
        private String label;
        /** Excel 列索引（0-based） */
        private int colIndex;
        /** 是否已选中（企业级 column_defs 优先，无企业级则用系统级 column_defs） */
        private boolean selected;
    }
}

