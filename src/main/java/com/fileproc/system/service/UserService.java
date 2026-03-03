package com.fileproc.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fileproc.common.BizException;
import com.fileproc.common.PageResult;
import com.fileproc.common.TenantContext;
import com.fileproc.common.annotation.OperationLog;
import com.fileproc.system.entity.SysUser;
import com.fileproc.system.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户管理 Service
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public PageResult<SysUser> pageList(int page, int pageSize, String keyword) {
        String tenantId = TenantContext.getTenantId();
        IPage<SysUser> result = userMapper.selectPageWithRole(
                new Page<>(page, pageSize), tenantId, keyword);
        return PageResult.of(result);
    }

    @OperationLog(module = "用户管理", action = "新建用户")
    public SysUser createUser(SysUser user) {
        // P1修复：前置空值校验
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            throw BizException.of("用户名不能为空");
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw BizException.of("密码不能为空");
        }
        String tenantId = TenantContext.getTenantId();
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, user.getUsername())
                        .eq(SysUser::getTenantId, tenantId));
        if (count > 0) throw BizException.of("用户名已存在");

        user.setId(UUID.randomUUID().toString());
        user.setTenantId(tenantId);
        user.setStatus(user.getStatus() != null ? user.getStatus() : "active");
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
        user.setPassword(null);
        return user;
    }

    @OperationLog(module = "用户管理", action = "编辑用户")
    public SysUser updateUser(String id, SysUser user) {
        SysUser existing = userMapper.selectById(id);
        if (existing == null) throw BizException.notFound("用户");
        // P1修复：租户归属校验，防止越权修改
        String tenantId = TenantContext.getTenantId();
        if (!tenantId.equals(existing.getTenantId())) {
            throw BizException.forbidden("无权操作该用户");
        }
        user.setId(id);
        // 如果传了密码才更新
        if (user.getPassword() != null && !user.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(null);
        }
        userMapper.updateById(user);
        // P1修复：清除返回值中的 password 字段
        SysUser result = userMapper.selectById(id);
        if (result != null) result.setPassword(null);
        return result;
    }

    @OperationLog(module = "用户管理", action = "删除用户")
    public void deleteUser(String id) {
        SysUser existing = userMapper.selectById(id);
        if (existing == null) throw BizException.notFound("用户");
        // P1修复：租户归属校验，防止越权删除
        String tenantId = TenantContext.getTenantId();
        if (!tenantId.equals(existing.getTenantId())) {
            throw BizException.forbidden("无权操作该用户");
        }
        userMapper.deleteById(id);
    }
}
