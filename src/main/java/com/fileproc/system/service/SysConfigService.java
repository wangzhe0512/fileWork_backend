package com.fileproc.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fileproc.common.BizException;
import com.fileproc.common.TenantContext;
import com.fileproc.common.annotation.OperationLog;
import com.fileproc.system.entity.SysConfig;
import com.fileproc.system.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 系统配置 Service
 */
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysConfigMapper sysConfigMapper;

    /**
     * 纯读配置：readOnly=true，不执行初始化写操作。
     * 如果配置不存在返回 null，由调用方决策。
     */
    @Transactional(readOnly = true)
    public SysConfig getConfig() {
        String tenantId = TenantContext.getTenantId();
        SysConfig config = sysConfigMapper.selectOne(
                new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getTenantId, tenantId));
        if (config == null) {
            // P1-TXN-03：getConfig 为只读事务，初始化逻辑移至单独事务
            return initDefaultConfig(tenantId);
        }
        return config;
    }

    /**
     * 初始化默认配置（独立写事务，避免在 readOnly 事务中写库）
     */
    @Transactional(rollbackFor = Exception.class)
    public SysConfig initDefaultConfig(String tenantId) {
        // 双重检查，防并发重复初始化
        SysConfig existing = sysConfigMapper.selectOne(
                new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getTenantId, tenantId));
        if (existing != null) return existing;
        SysConfig config = new SysConfig();
        config.setId(UUID.randomUUID().toString());
        config.setTenantId(tenantId);
        config.setSiteName("文件解析处理平台");
        config.setLogoUrl("");
        config.setIcp("");
        config.setMaxFileSize(52428800);
        sysConfigMapper.insert(config);
        return config;
    }

    @OperationLog(module = "系统配置", action = "更新配置")
    @Transactional(rollbackFor = Exception.class)
    public SysConfig updateConfig(SysConfig input) {
        SysConfig config = getConfig();
        if (config == null) throw BizException.of("系统配置不存在");
        if (input.getSiteName() != null) config.setSiteName(input.getSiteName());
        if (input.getLogoUrl() != null) config.setLogoUrl(input.getLogoUrl());
        if (input.getIcp() != null) config.setIcp(input.getIcp());
        if (input.getMaxFileSize() != null) config.setMaxFileSize(input.getMaxFileSize());
        sysConfigMapper.updateById(config);
        return config;
    }
}
