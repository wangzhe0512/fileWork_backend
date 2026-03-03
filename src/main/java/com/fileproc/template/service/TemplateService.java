package com.fileproc.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fileproc.common.BizException;
import com.fileproc.common.PageResult;
import com.fileproc.common.TenantContext;
import com.fileproc.common.annotation.OperationLog;
import com.fileproc.report.mapper.ReportMapper;
import com.fileproc.template.entity.Template;
import com.fileproc.template.mapper.TemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TemplateService {

    private final TemplateMapper templateMapper;
    private final ReportMapper reportMapper;

    public PageResult<Template> pageList(int page, int pageSize, String keyword, Integer year, String companyId) {
        String tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<Template> wrapper = new LambdaQueryWrapper<Template>()
                .eq(Template::getTenantId, tenantId)
                .orderByDesc(Template::getCreatedAt);
        if (companyId != null) wrapper.eq(Template::getCompanyId, companyId);
        if (year != null) wrapper.eq(Template::getYear, year);
        if (keyword != null && !keyword.isBlank()) wrapper.like(Template::getName, keyword);
        IPage<Template> result = templateMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return PageResult.of(result);
    }

    @OperationLog(module = "模板管理", action = "新建模板")
    public Template createTemplate(Template template) {
        template.setId(UUID.randomUUID().toString());
        template.setTenantId(TenantContext.getTenantId());
        template.setStatus("active");
        template.setCreatedAt(LocalDateTime.now());
        templateMapper.insert(template);
        return template;
    }

    @OperationLog(module = "模板管理", action = "归档模板")
    public void archiveTemplate(String id) {
        Template t = templateMapper.selectById(id);
        if (t == null) throw BizException.notFound("模板");
        t.setStatus("archived");
        templateMapper.updateById(t);
    }

    @OperationLog(module = "模板管理", action = "删除模板")
    public void deleteTemplate(String id) {
        Template t = templateMapper.selectById(id);
        if (t == null) throw BizException.notFound("模板");
        // P1：若有编辑中的报告正依赖该模板（同 companyId+year），拒绝删除
        if (t.getCompanyId() != null && t.getYear() != null) {
            long editingCount = reportMapper.countEditingByCompanyAndYear(t.getCompanyId(), t.getYear());
            if (editingCount > 0) {
                throw BizException.of(400, "该模板关联的企业年度存在 " + editingCount + " 个编辑中报告，请先归档或删除相关报告");
            }
        }
        templateMapper.deleteById(id);
    }
}
