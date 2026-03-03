package com.fileproc.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色实体，对应 sys_role 表
 */
@Data
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 50, message = "角色名称最长50个字符")
    private String name;

    @Size(max = 50, message = "角色编码最长50个字符")
    private String code;

    @Size(max = 200, message = "描述最长200个字符")
    private String description;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;

    /** 非数据库字段：该角色拥有的权限 code 列表 */
    @TableField(exist = false)
    private java.util.List<String> permissions;
}
