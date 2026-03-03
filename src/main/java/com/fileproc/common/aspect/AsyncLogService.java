package com.fileproc.common.aspect;

import com.fileproc.system.entity.SysLog;
import com.fileproc.system.mapper.LogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 异步操作日志写入 Service
 * P1-11修复：将 @Async 方法抽取到独立 @Service Bean，
 * 通过 Spring 容器代理确保异步真正生效（@Async 在 @Aspect 内部调用无效）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncLogService {

    private final LogMapper logMapper;

    @Async
    public void saveLog(String module, String action,
                        String userId, String userName,
                        String tenantId, String ip, String detail) {
        try {
            SysLog sysLog = new SysLog();
            sysLog.setId(UUID.randomUUID().toString());
            sysLog.setTenantId(tenantId);
            sysLog.setUserId(userId);
            sysLog.setUserName(userName);
            sysLog.setModule(module);
            sysLog.setAction(action);
            sysLog.setDetail(detail);
            sysLog.setIp(ip);
            sysLog.setCreatedAt(LocalDateTime.now());
            logMapper.insert(sysLog);
        } catch (Exception e) {
            log.warn("异步写入操作日志失败: {}", e.getMessage());
        }
    }
}
