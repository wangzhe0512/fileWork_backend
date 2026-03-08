package com.fileproc.template.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 系统模块实体（系统级，不绑定租户）
 * 对应表：system_module
 */
@Data
@TableName("system_module")
public class SystemModule {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联系统模板ID */
    private String systemTemplateId;

    /** 模块名称 */
    private String name;

    /** 模块编码（如：基本信息、关联交易） */
    private String code;

    /** 描述 */
    private String description;

    /** 排序 */
    private Integer sort;

    @TableLogic
    private Integer deleted;
}
