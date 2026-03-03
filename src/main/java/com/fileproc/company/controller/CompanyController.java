package com.fileproc.company.controller;

import com.fileproc.common.PageResult;
import com.fileproc.common.R;
import com.fileproc.company.entity.Company;
import com.fileproc.company.service.CompanyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 企业档案接口
 * GET  /companies              - 分页列表
 * GET  /companies/search       - 模糊搜索（注意在 /{id} 之前声明）
 * GET  /companies/{id}         - 详情（含联系人）
 * POST /companies              - 新建
 * PUT  /companies/{id}         - 编辑
 * DELETE /companies/{id}       - 删除
 */
@Tag(name = "企业档案")
@Validated
@RestController
@RequestMapping("/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @Operation(summary = "分页查询企业列表")
    @PreAuthorize("hasAuthority('company:list')")
    @GetMapping
    public R<PageResult<Company>> list(
            @RequestParam(defaultValue = "1") int page,
            @Max(value = 100, message = "每页最多100条") @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword) {
        return R.ok(companyService.pageList(page, pageSize, keyword));
    }

    /** 注意：/search 必须在 /{id} 之前声明，避免路由冲突 */
    @Operation(summary = "模糊搜索企业（下拉联想）")
    @PreAuthorize("hasAuthority('company:list')")
    @GetMapping("/search")
    public R<List<Company>> search(
            @NotBlank(message = "搜索词不能为空")
            @Size(max = 50, message = "搜索词最多50个字符")
            @RequestParam String keyword) {
        return R.ok(companyService.search(keyword));
    }

    @Operation(summary = "获取企业详情（含联系人）")
    @PreAuthorize("hasAuthority('company:list')")
    @GetMapping("/{id}")
    public R<Company> detail(@PathVariable String id) {
        return R.ok(companyService.getById(id));
    }

    @Operation(summary = "新建企业")
    @PreAuthorize("hasAuthority('company:create')")
    @PostMapping
    public R<Company> create(@Valid @RequestBody Company company) {
        return R.ok(companyService.createCompany(company));
    }

    @Operation(summary = "编辑企业")
    @PreAuthorize("hasAuthority('company:edit')")
    @PutMapping("/{id}")
    public R<Company> update(@PathVariable String id, @Valid @RequestBody Company company) {
        return R.ok(companyService.updateCompany(id, company));
    }

    @Operation(summary = "删除企业")
    @PreAuthorize("hasAuthority('company:delete')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        companyService.deleteCompany(id);
        return R.ok();
    }
}
