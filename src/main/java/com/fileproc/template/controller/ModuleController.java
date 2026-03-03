package com.fileproc.template.controller;

import com.fileproc.common.PageResult;
import com.fileproc.common.R;
import com.fileproc.template.entity.ReportModule;
import com.fileproc.template.service.ModuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "模块管理")
@Validated
@RestController
@RequestMapping("/modules")
@RequiredArgsConstructor
public class ModuleController {

    private final ModuleService moduleService;

    @GetMapping
    @PreAuthorize("hasAuthority('module:list')")
    @Operation(summary = "分页查询模块")
    public R<PageResult<ReportModule>> list(
            @RequestParam(defaultValue = "1") int page,
            @Max(value = 100, message = "每页最多100条") @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String companyId) {
        return R.ok(moduleService.pageList(page, pageSize, companyId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('module:create')")
    @Operation(summary = "新建模块")
    public R<ReportModule> create(@Valid @RequestBody ReportModule m) {
        return R.ok(moduleService.create(m));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('module:edit')")
    @Operation(summary = "编辑模块")
    public R<ReportModule> update(@PathVariable String id, @Valid @RequestBody ReportModule m) {
        return R.ok(moduleService.update(id, m));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('module:delete')")
    @Operation(summary = "删除模块")
    public R<Void> delete(@PathVariable String id) {
        moduleService.delete(id);
        return R.ok();
    }
}
