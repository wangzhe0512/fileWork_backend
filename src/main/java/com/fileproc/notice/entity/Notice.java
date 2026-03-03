package com.fileproc.notice.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息通知实体，对应 notice 表
 */
@Data
@TableName(value = "notice", autoResultMap = true)
public class Notice {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String title;

    private String content;

    /** all | role */
    private String targetType;

    /** 目标角色ID列表（JSON） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> targetRoleIds;

    private String publishedBy;

    private String publishedByName;

    /** published | draft */
    private String status;

    private Integer readCount;

    private Integer totalCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
