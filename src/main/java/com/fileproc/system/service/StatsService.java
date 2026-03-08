package com.fileproc.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fileproc.common.TenantContext;
import com.fileproc.company.entity.Company;
import com.fileproc.company.mapper.CompanyMapper;
import com.fileproc.datafile.entity.DataFile;
import com.fileproc.datafile.mapper.DataFileMapper;
import com.fileproc.report.entity.Report;
import com.fileproc.report.mapper.ReportMapper;
import com.fileproc.system.entity.SysUser;
import com.fileproc.system.mapper.UserMapper;
import com.fileproc.template.entity.Template;
import com.fileproc.template.mapper.TemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 首页统计 Service
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final CompanyMapper companyMapper;
    private final ReportMapper reportMapper;
    private final TemplateMapper templateMapper;
    private final DataFileMapper dataFileMapper;
    private final UserMapper userMapper;

    public Map<String, Object> getStats() {
        // P1-TENANT-02：显式 tenantId 保底过滤，防止租户插件失效时数据泄露
        // 同时过滤已删除数据（deleted = 0）
        String tenantId = TenantContext.getTenantId();
        long companyCount = companyMapper.selectCount(
                new LambdaQueryWrapper<Company>()
                        .eq(Company::getTenantId, tenantId)
                        .eq(Company::getDeleted, 0));
        long reportCount = reportMapper.selectCount(
                new LambdaQueryWrapper<Report>()
                        .eq(Report::getTenantId, tenantId)
                        .eq(Report::getDeleted, 0));
        long templateCount = templateMapper.selectCount(
                new LambdaQueryWrapper<Template>()
                        .eq(Template::getTenantId, tenantId)
                        .eq(Template::getDeleted, 0));
        long dataFileCount = dataFileMapper.selectCount(
                new LambdaQueryWrapper<DataFile>()
                        .eq(DataFile::getTenantId, tenantId)
                        .eq(DataFile::getDeleted, 0));
        long userCount = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getTenantId, tenantId)
                        .eq(SysUser::getDeleted, 0));

        return Map.of(
                "companyCount", companyCount,
                "reportCount", reportCount,
                "templateCount", templateCount,
                "dataFileCount", dataFileCount,
                "userCount", userCount
        );
    }
}
