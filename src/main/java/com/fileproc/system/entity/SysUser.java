package com.fileproc.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户用户实体，对应 sys_user 表
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名最长50个字符")
    private String username;

    @Size(max = 100, message = "姓名最长100个字符")
    private String realName;

    @TableField(select = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最长100个字符")
    private String email;

    @Size(max = 20, message = "手机号最长20个字符")
    private String phone;

    private String roleId;

    private String status;

    private String avatar;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;

    /** 非数据库字段：角色名称（联表查询回显） */
    @TableField(exist = false)
    private String roleName;
}
