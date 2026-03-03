package com.fileproc.template.controller;

import com.fileproc.common.PageResult;
import com.fileproc.common.R;
import com.fileproc.template.entity.Template;
import com.fileproc.template.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "模板管理")
@RestController
@RequestMapping("/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @Operation(summary = "分页查询模板")
    @PreAuthorize("hasAuthority('template:list')")
    @GetMapping
    public R<PageResult<Template>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String companyId) {
        return R.ok(templateService.pageList(page, pageSize, keyword, year, companyId));
    }

    @Operation(summary = "新建模板")
    @PreAuthorize("hasAuthority('template:create')")
    @PostMapping
    public R<Template> create(@Valid @RequestBody Template template) {
        return R.ok(templateService.createTemplate(template));
    }

    @Operation(summary = "归档模板")
    @PreAuthorize("hasAuthority('template:edit')")
    @PostMapping("/{id}/archive")
    public R<Void> archive(@PathVariable String id) {
        templateService.archiveTemplate(id);
        return R.ok();
    }

    @Operation(summary = "删除模板")
    @PreAuthorize("hasAuthority('template:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        templateService.deleteTemplate(id);
        return R.ok();
    }
}
