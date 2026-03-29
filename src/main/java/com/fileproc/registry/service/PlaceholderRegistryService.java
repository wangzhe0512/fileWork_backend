package com.fileproc.registry.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileproc.common.BizException;
import com.fileproc.common.TenantContext;
import com.fileproc.registry.entity.PlaceholderRegistry;
import com.fileproc.registry.mapper.PlaceholderRegistryMapper;
import com.fileproc.report.service.ReverseTemplateEngine;
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

            return new ReverseTemplateEngine.RegistryEntry(
                    db.getPlaceholderName(),
                    db.getDisplayName(),
                    type,
                    db.getDataSource(),
                    db.getSheetName(),
                    db.getCellAddress(),
                    titleKeywords,
                    columnDefs
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
}
