package com.fileproc.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fileproc.common.BizException;
import com.fileproc.common.PageResult;
import com.fileproc.common.TenantContext;
import com.fileproc.common.annotation.OperationLog;
import com.fileproc.template.entity.Placeholder;
import com.fileproc.template.mapper.PlaceholderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlaceholderService {

    private final PlaceholderMapper placeholderMapper;

    public PageResult<Placeholder> pageList(int page, int pageSize, String companyId) {
        // P1：显式 tenantId 保底过滤，防止 MyBatis-Plus 插件失效时数据泄露
        String tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<Placeholder> wrapper = new LambdaQueryWrapper<Placeholder>()
                .eq(Placeholder::getTenantId, tenantId);
        if (companyId != null) wrapper.eq(Placeholder::getCompanyId, companyId);
        IPage<Placeholder> result = placeholderMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return PageResult.of(result);
    }

    @OperationLog(module = "占位符管理", action = "新建占位符")
    public Placeholder create(Placeholder p) {
        // P1：同一 companyId+name 唯一性校验
        if (p.getCompanyId() != null && p.getName() != null) {
            long count = placeholderMapper.selectCount(
                    new LambdaQueryWrapper<Placeholder>()
                            .eq(Placeholder::getCompanyId, p.getCompanyId())
                            .eq(Placeholder::getName, p.getName())
            );
            if (count > 0) {
                throw BizException.of(400, "占位符名称 '" + p.getName() + "' 在该企业下已存在");
            }
        }
        p.setId(UUID.randomUUID().toString());
        p.setTenantId(TenantContext.getTenantId());
        placeholderMapper.insert(p);
        return p;
    }

    @OperationLog(module = "占位符管理", action = "编辑占位符")
    public Placeholder update(String id, Placeholder p) {
        Placeholder existing = placeholderMapper.selectById(id);
        if (existing == null) throw BizException.notFound("占位符");
        // P1：名称修改时检查唯一性（排除自身）
        if (p.getName() != null && !p.getName().equals(existing.getName())) {
            String companyId = p.getCompanyId() != null ? p.getCompanyId() : existing.getCompanyId();
            long count = placeholderMapper.selectCount(
                    new LambdaQueryWrapper<Placeholder>()
                            .eq(Placeholder::getCompanyId, companyId)
                            .eq(Placeholder::getName, p.getName())
                            .ne(Placeholder::getId, id)
            );
            if (count > 0) {
                throw BizException.of(400, "占位符名称 '" + p.getName() + "' 在该企业下已存在");
            }
        }
        p.setId(id);
        placeholderMapper.updateById(p);
        // P2-QUAL-08：移除重复的 selectById（updateById 后直接返回最新数据）
        return placeholderMapper.selectById(id);
    }

    @OperationLog(module = "占位符管理", action = "删除占位符")
    public void delete(String id) {
        if (placeholderMapper.selectById(id) == null) throw BizException.notFound("占位符");
        placeholderMapper.deleteById(id);
    }
}
