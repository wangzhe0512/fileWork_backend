package com.fileproc.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fileproc.common.PageResult;
import com.fileproc.common.TenantContext;
import com.fileproc.system.entity.SysLog;
import com.fileproc.system.mapper.LogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 操作日志 Service
 */
@Service
@RequiredArgsConstructor
public class LogService {

    private final LogMapper logMapper;

    public PageResult<SysLog> pageList(int page, int pageSize) {
        String tenantId = TenantContext.getTenantId();
        IPage<SysLog> result = logMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<SysLog>()
                        .eq(SysLog::getTenantId, tenantId)
                        .orderByDesc(SysLog::getCreatedAt)
        );
        return PageResult.of(result);
    }
}
