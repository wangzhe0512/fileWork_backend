package com.fileproc.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fileproc.common.BizException;
import com.fileproc.common.PageResult;
import com.fileproc.common.enums.TenantStatus;
import com.fileproc.tenant.entity.SysTenant;
import com.fileproc.tenant.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 租户管理 Service
 */
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantMapper tenantMapper;

    /** 登录页获取活跃租户列表（P2-TENANT-05：仅返回 id/name，不暴露 code 等敏感字段） */
    public List<Map<String, Object>> getActiveTenantList() {
        List<SysTenant> list = tenantMapper.selectList(
                new LambdaQueryWrapper<SysTenant>().eq(SysTenant::getStatus, TenantStatus.ACTIVE.getCode())
        );
        return list.stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.getId(),
                        "name", t.getName()
                ))
                .toList();
    }

    /** 超管：分页查询租户列表 */
    public PageResult<SysTenant> pageList(int page, int pageSize, String keyword) {
        LambdaQueryWrapper<SysTenant> wrapper = new LambdaQueryWrapper<SysTenant>()
                .orderByDesc(SysTenant::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(SysTenant::getName, keyword)
                    .or().like(SysTenant::getCode, keyword));
        }
        IPage<SysTenant> result = tenantMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return PageResult.of(result);
    }

    /** 新建租户 */
    public SysTenant createTenant(SysTenant tenant) {
        // 若未传 code，自动生成唯一编码（UUID 前8位，冲突时重试）
        if (tenant.getCode() == null || tenant.getCode().isBlank()) {
            String generated;
            int retry = 0;
            do {
                generated = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                retry++;
            } while (retry < 3 && tenantMapper.selectCount(
                    new LambdaQueryWrapper<SysTenant>().eq(SysTenant::getCode, generated)) > 0);
            tenant.setCode(generated);
        } else {
            // 传了 code 则校验格式和唯一性
            if (!tenant.getCode().matches("^[a-z0-9_-]+$")) {
                throw BizException.of("租户编码只能包含小写字母、数字、下划线和连字符");
            }
            Long count = tenantMapper.selectCount(
                    new LambdaQueryWrapper<SysTenant>().eq(SysTenant::getCode, tenant.getCode()));
            if (count > 0) throw BizException.of("租户编码已存在");
        }

        tenant.setId(UUID.randomUUID().toString());
        tenant.setStatus(TenantStatus.ACTIVE.getCode());
        tenant.setAdminCount(0);
        tenant.setCreatedAt(LocalDateTime.now());
        tenantMapper.insert(tenant);
        return tenant;
    }

    /** 更新租户信息（P1：校验 code 唯一性，排除自身） */
    public SysTenant updateTenant(String id, SysTenant tenant) {
        SysTenant existing = tenantMapper.selectById(id);
        if (existing == null) throw BizException.notFound("租户");
        // 若传入 code，校验格式
        if (tenant.getCode() != null && !tenant.getCode().isBlank()
                && !tenant.getCode().matches("^[a-z0-9_-]+$")) {
            throw BizException.of("租户编码只能包含小写字母、数字、下划线和连字符");
        }
        // P1：若 code 发生变更，校验新 code 是否已被其他租户使用
        if (tenant.getCode() != null && !tenant.getCode().equals(existing.getCode())) {
            long count = tenantMapper.selectCount(
                    new LambdaQueryWrapper<SysTenant>()
                            .eq(SysTenant::getCode, tenant.getCode())
                            .ne(SysTenant::getId, id)
            );
            if (count > 0) throw BizException.of("租户编码 '" + tenant.getCode() + "' 已被其他租户使用");
        }
        if (tenant.getName() != null) existing.setName(tenant.getName());
        if (tenant.getCode() != null) existing.setCode(tenant.getCode());
        if (tenant.getLogoUrl() != null) existing.setLogoUrl(tenant.getLogoUrl());
        if (tenant.getDescription() != null) existing.setDescription(tenant.getDescription());
        tenantMapper.updateById(existing);
        return existing;
    }

    /** 删除租户（逻辑删除） */
    public void deleteTenant(String id) {
        if (tenantMapper.selectById(id) == null) throw BizException.notFound("租户");
        tenantMapper.deleteById(id);
    }

    /** 切换租户状态 */
    public SysTenant toggleStatus(String id, String status) {
        SysTenant tenant = tenantMapper.selectById(id);
        if (tenant == null) throw BizException.notFound("租户");
        if (!TenantStatus.ACTIVE.getCode().equals(status) && !TenantStatus.DISABLED.getCode().equals(status)) {
            throw BizException.of("无效的状态值");
        }
        tenant.setStatus(status);
        tenantMapper.updateById(tenant);
        return tenant;
    }
}
