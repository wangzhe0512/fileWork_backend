package com.fileproc.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 超管账号实体，对应 sys_admin 表
 */
@Data
@TableName("sys_admin")
public class SysAdmin {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String username;

    private String password;

    private String realName;

    /** 账号状态：active | disabled */
    private String status;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
