package com.fileproc.registry.controller;

import com.fileproc.common.R;
import com.fileproc.registry.entity.PlaceholderRegistry;
import com.fileproc.registry.service.PlaceholderRegistryService;
import com.fileproc.report.service.ReverseTemplateEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 占位符注册表接口
 * <pre>
 * GET    /placeholder-registry                       - 查询注册表（system 或 company 级）
 * GET    /placeholder-registry/effective             - 预览生效规则（企业级合并系统级）
 * POST   /placeholder-registry                       - 新建条目
 * PUT    /placeholder-registry/{id}                  - 更新条目
 * DELETE /placeholder-registry/{id}                  - 删除条目（软删除）
 * </pre>
 */
@Tag(name = "占位符注册表")
@RestController
@RequestMapping("/placeholder-registry")
@RequiredArgsConstructor
public class PlaceholderRegistryController {

    private final PlaceholderRegistryService placeholderRegistryService;

    @Operation(summary = "查询注册表条目（level=system | company | all，all 时返回系统级+企业级合并列表）")
    @PreAuthorize("hasAuthority('registry:list')")
    @GetMapping
    public R<List<PlaceholderRegistry>> list(
            @RequestParam(defaultValue = "system") String level,
            @RequestParam(required = false) String companyId) {
        if ("system".equals(level)) {
            return R.ok(placeholderRegistryService.listSystemEntries());
        } else if ("all".equals(level)) {
            // 全部：系统级 + 企业级，按 placeholderName 去重，企业级优先（覆盖语义）
            List<PlaceholderRegistry> systemList = placeholderRegistryService.listSystemEntries();
            List<PlaceholderRegistry> companyList = (companyId != null && !companyId.trim().isEmpty())
                    ? placeholderRegistryService.listCompanyEntries(companyId)
                    : java.util.Collections.emptyList();
            // 用 LinkedHashMap 按 sort 顺序去重：先放系统级，再用企业级覆盖
            java.util.Map<String, PlaceholderRegistry> merged = new java.util.LinkedHashMap<>();
            for (PlaceholderRegistry e : systemList) {
                merged.put(e.getPlaceholderName(), e);
            }
            for (PlaceholderRegistry e : companyList) {
                merged.put(e.getPlaceholderName(), e); // 企业级覆盖同名系统级
            }
            return R.ok(new java.util.ArrayList<>(merged.values()));
        } else {
            if (companyId == null || companyId.trim().isEmpty()) {
                return R.fail(400, "查询企业级规则时 companyId 不能为空");
            }
            return R.ok(placeholderRegistryService.listCompanyEntries(companyId));
        }
    }

    @Operation(summary = "预览生效规则（企业级覆盖系统级后的最终规则列表）")
    @PreAuthorize("hasAuthority('registry:list')")
    @GetMapping("/effective")
    public R<List<ReverseTemplateEngine.RegistryEntry>> effective(
            @RequestParam(required = false) String companyId) {
        return R.ok(placeholderRegistryService.getEffectiveRegistry(companyId));
    }

    @Operation(summary = "新建注册表条目")
    @PreAuthorize("hasAuthority('registry:edit')")
    @PostMapping
    public R<PlaceholderRegistry> save(@RequestBody PlaceholderRegistry entry) {
        return R.ok(placeholderRegistryService.saveEntry(entry));
    }

    @Operation(summary = "更新注册表条目")
    @PreAuthorize("hasAuthority('registry:edit')")
    @PutMapping("/{id}")
    public R<PlaceholderRegistry> update(
            @PathVariable String id,
            @RequestBody PlaceholderRegistry entry) {
        return R.ok(placeholderRegistryService.updateEntry(id, entry));
    }

    @Operation(summary = "删除注册表条目（软删除）")
    @PreAuthorize("hasAuthority('registry:edit')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        placeholderRegistryService.deleteEntry(id);
        return R.ok();
    }

    @Operation(summary = "基于系统级条目为指定企业创建企业级覆盖条目",
            description = "复制系统级条目，仅改 companyId/level，可传入需要覆盖的字段；同名企业级已存在时返回400")
    @PreAuthorize("hasAuthority('registry:edit')")
    @PostMapping("/{id}/override-for-company")
    public R<PlaceholderRegistry> overrideForCompany(
            @PathVariable String id,
            @RequestParam String companyId,
            @RequestBody(required = false) PlaceholderRegistry overrides) {
        return R.ok(placeholderRegistryService.overrideForCompany(id, companyId, overrides));
    }
}
