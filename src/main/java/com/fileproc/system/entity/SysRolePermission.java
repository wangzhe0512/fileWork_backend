package com.fileproc.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 角色权限关联，对应 sys_role_permission 表
 */
@Data
@TableName("sys_role_permission")
public class SysRolePermission {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String roleId;

    private String permissionCode;
}
