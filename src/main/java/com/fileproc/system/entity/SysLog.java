package com.fileproc.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志实体，对应 sys_log 表
 */
@Data
@TableName("sys_log")
public class SysLog {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String userId;

    private String userName;

    private String action;

    private String module;

    private String detail;

    private String ip;

    private LocalDateTime createdAt;
}
