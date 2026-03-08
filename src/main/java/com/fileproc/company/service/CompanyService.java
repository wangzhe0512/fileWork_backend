package com.fileproc.company.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fileproc.common.BizException;
import com.fileproc.common.PageResult;
import com.fileproc.common.TenantContext;
import com.fileproc.common.annotation.OperationLog;
import com.fileproc.company.entity.Company;
import com.fileproc.company.entity.Contact;
import com.fileproc.company.mapper.CompanyMapper;
import com.fileproc.company.mapper.ContactMapper;
import com.fileproc.datafile.mapper.DataFileMapper;
import com.fileproc.report.mapper.ReportMapper;
import com.fileproc.template.mapper.CompanyTemplateMapper;
import com.fileproc.template.mapper.CompanyTemplatePlaceholderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 企业档案 Service（含联系人子表级联）
 */
@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyMapper companyMapper;
    private final ContactMapper contactMapper;
    private final DataFileMapper dataFileMapper;
    private final CompanyTemplateMapper companyTemplateMapper;
    private final CompanyTemplatePlaceholderMapper companyTemplatePlaceholderMapper;
    private final ReportMapper reportMapper;

    /** 分页查询企业列表 */
    public PageResult<Company> pageList(int page, int pageSize, String keyword) {
        // P2：租户过滤保底（TenantContext 兜底，防止 MyBatis-Plus 插件未生效时数据泄露）
        String tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<Company> wrapper = new LambdaQueryWrapper<Company>()
                .eq(Company::getTenantId, tenantId)
                .orderByDesc(Company::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Company::getName, keyword)
                    .or().like(Company::getAlias, keyword));
        }
        IPage<Company> result = companyMapper.selectPage(new Page<>(page, pageSize), wrapper);
        // 不在列表中加载联系人（详情页才加载）
        return PageResult.of(result);
    }

    /** 获取企业详情（含联系人） */
    public Company getById(String id) {
        Company company = companyMapper.selectById(id);
        if (company == null) throw BizException.notFound("企业");
        // P2：租户归属校验保底
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.equals(company.getTenantId())) {
            throw BizException.forbidden("无权访问该企业");
        }
        List<Contact> contacts = contactMapper.selectByCompanyId(id);
        company.setContacts(contacts);
        return company;
    }

    /** 搜索企业（下拉联想，limit 10） */
    public List<Company> search(String keyword) {
        // P2：租户过滤保底
        String tenantId = TenantContext.getTenantId();
        return companyMapper.selectList(
                new LambdaQueryWrapper<Company>()
                        .eq(Company::getTenantId, tenantId)
                        .and(w -> w.like(Company::getName, keyword)
                                .or().like(Company::getAlias, keyword))
                        .last("LIMIT 10")
        );
    }

    /** 新建企业（含联系人） */
    @OperationLog(module = "企业档案", action = "新建企业")
    @Transactional
    public Company createCompany(Company company) {
        String tenantId = TenantContext.getTenantId();
        company.setId(UUID.randomUUID().toString());
        company.setTenantId(tenantId);
        company.setCreatedAt(LocalDateTime.now());
        companyMapper.insert(company);

        // 保存联系人
        saveContacts(company.getId(), tenantId, company.getContacts());

        return getById(company.getId());
    }

    /** 更新企业（先删联系人再插入） */
    @OperationLog(module = "企业档案", action = "编辑企业")
    @Transactional
    public Company updateCompany(String id, Company company) {
        Company existing = companyMapper.selectById(id);
        if (existing == null) throw BizException.notFound("企业");
        // P2-QUAL-07：租户归属校验，防止跨租户篡改
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.equals(existing.getTenantId())) {
            throw BizException.forbidden("无权修改该企业");
        }
        company.setId(id);
        company.setTenantId(existing.getTenantId());
        company.setCreatedAt(existing.getCreatedAt());
        companyMapper.updateById(company);

        // 级联更新联系人（先删后插）
        contactMapper.deleteByCompanyId(id);
        saveContacts(id, existing.getTenantId(), company.getContacts());

        return getById(id);
    }

    /** 删除企业（级联删除关联数据：数据文件、子模板、占位符、报告、联系人） */
    @OperationLog(module = "企业档案", action = "删除企业")
    @Transactional(rollbackFor = Exception.class)
    public void deleteCompany(String id) {
        if (companyMapper.selectById(id) == null) throw BizException.notFound("企业");

        // 1. 删除数据文件
        dataFileMapper.deleteByCompanyId(id);

        // 2. 先查询该企业所有子模板ID，删除占位符状态
        List<String> templateIds = companyTemplateMapper.selectIdsByCompanyId(id);
        if (!templateIds.isEmpty()) {
            companyTemplatePlaceholderMapper.deleteByTemplateIds(templateIds);
        }

        // 3. 删除企业子模板
        companyTemplateMapper.deleteByCompanyId(id);

        // 4. 删除报告
        reportMapper.deleteByCompanyId(id);

        // 5. 删除联系人
        contactMapper.deleteByCompanyId(id);

        // 6. 删除企业主表
        companyMapper.deleteById(id);
    }

    // P2-09：批量插入联系人，减少数据库往返
    private void saveContacts(String companyId, String tenantId, List<Contact> contacts) {
        if (contacts == null || contacts.isEmpty()) return;
        contacts.forEach(c -> {
            c.setId(UUID.randomUUID().toString());
            c.setCompanyId(companyId);
            c.setTenantId(tenantId);
        });
        contactMapper.batchInsert(contacts);
    }
}
