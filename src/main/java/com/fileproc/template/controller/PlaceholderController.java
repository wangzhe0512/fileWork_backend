package com.fileproc.template.controller;

import com.fileproc.common.PageResult;
import com.fileproc.common.R;
import com.fileproc.template.entity.Placeholder;
import com.fileproc.template.service.PlaceholderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "占位符管理")
@Validated
@RestController
@RequestMapping("/placeholders")
@RequiredArgsConstructor
public class PlaceholderController {

    private final PlaceholderService placeholderService;

    @GetMapping
    @PreAuthorize("hasAuthority('placeholder:list')")
    @Operation(summary = "分页查询占位符")
    public R<PageResult<Placeholder>> list(
            @RequestParam(defaultValue = "1") int page,
            @Max(value = 100, message = "每页最多100条") @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String companyId) {
        return R.ok(placeholderService.pageList(page, pageSize, companyId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('placeholder:create')")
    @Operation(summary = "新建占位符")
    public R<Placeholder> create(@Valid @RequestBody Placeholder p) {
        return R.ok(placeholderService.create(p));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('placeholder:edit')")
    @Operation(summary = "编辑占位符")
    public R<Placeholder> update(@PathVariable String id, @Valid @RequestBody Placeholder p) {
        return R.ok(placeholderService.update(id, p));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('placeholder:delete')")
    @Operation(summary = "删除占位符")
    public R<Void> delete(@PathVariable String id) {
        placeholderService.delete(id);
        return R.ok();
    }
}
