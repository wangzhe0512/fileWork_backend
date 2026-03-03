package com.fileproc.notice.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知投送记录，对应 notice_user 表
 */
@Data
@TableName("notice_user")
public class NoticeUser {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String noticeId;

    private String tenantId;

    private String userId;

    private Boolean isRead;

    private LocalDateTime readAt;
}
