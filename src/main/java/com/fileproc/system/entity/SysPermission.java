package com.fileproc.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.util.List;

/**
 * 权限实体，对应 sys_permission 表（全局共享，无 tenant_id）
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String name;

    private String code;

    private String type;

    private String parentId;

    private String path;

    private String icon;

    private Integer sort;

    @TableLogic
    private Integer deleted;

    /** 子权限列表（递归构建树时使用） */
    @TableField(exist = false)
    private List<SysPermission> children;
}
