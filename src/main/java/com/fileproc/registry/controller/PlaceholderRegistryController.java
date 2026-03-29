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

    @Operation(summary = "查询注册表条目（level=system 或 level=company&companyId=xxx）")
    @PreAuthorize("hasAuthority('registry:list')")
    @GetMapping
    public R<List<PlaceholderRegistry>> list(
            @RequestParam(defaultValue = "system") String level,
            @RequestParam(required = false) String companyId) {
        if ("system".equals(level)) {
            return R.ok(placeholderRegistryService.listSystemEntries());
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
}
