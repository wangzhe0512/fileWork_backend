package com.fileproc.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fileproc.common.BizException;
import com.fileproc.common.PageResult;
import com.fileproc.common.TenantContext;
import com.fileproc.common.annotation.OperationLog;
import com.fileproc.template.entity.ReportModule;
import com.fileproc.template.mapper.ModuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModuleService {

    private final ModuleMapper moduleMapper;

    public PageResult<ReportModule> pageList(int page, int pageSize, String companyId) {
        // P1：显式 tenantId 保底过滤，防止 MyBatis-Plus 插件失效时数据泄露
        String tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<ReportModule> wrapper = new LambdaQueryWrapper<ReportModule>()
                .eq(ReportModule::getTenantId, tenantId)
                .orderByAsc(ReportModule::getSort);
        if (companyId != null) wrapper.eq(ReportModule::getCompanyId, companyId);
        IPage<ReportModule> result = moduleMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return PageResult.of(result);
    }

    @OperationLog(module = "模块管理", action = "新建模块")
    public ReportModule create(ReportModule m) {
        // P2：同 companyId+code 唯一性校验
        if (m.getCompanyId() != null && m.getCode() != null) {
            long count = moduleMapper.selectCount(
                    new LambdaQueryWrapper<ReportModule>()
                            .eq(ReportModule::getCompanyId, m.getCompanyId())
                            .eq(ReportModule::getCode, m.getCode())
            );
            if (count > 0) {
                throw BizException.of(400, "模块编码 '" + m.getCode() + "' 在该企业下已存在");
            }
        }
        m.setId(UUID.randomUUID().toString());
        m.setTenantId(TenantContext.getTenantId());
        moduleMapper.insert(m);
        return m;
    }

    @OperationLog(module = "模块管理", action = "编辑模块")
    public ReportModule update(String id, ReportModule m) {
        ReportModule existing = moduleMapper.selectById(id);
        if (existing == null) throw BizException.notFound("模块");
        // P2-QUAL-09：租户归属校验，防止跨租户篡改
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.equals(existing.getTenantId())) {
            throw BizException.forbidden("无权修改该模块");
        }
        m.setId(id);
        moduleMapper.updateById(m);
        return moduleMapper.selectById(id);
    }

    @OperationLog(module = "模块管理", action = "删除模块")
    public void delete(String id) {
        if (moduleMapper.selectById(id) == null) throw BizException.notFound("模块");
        moduleMapper.deleteById(id);
    }
}
