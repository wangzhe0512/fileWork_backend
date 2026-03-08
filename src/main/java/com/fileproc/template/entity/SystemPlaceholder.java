package com.fileproc.template.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 系统占位符规则实体（系统级，不绑定租户）
 * 对应表：system_placeholder
 * <p>
 * 占位符格式：{{清单模板-数据表-B3}}
 * name = "清单模板-数据表-B3"，dataSource="list"，sourceSheet="数据表"，sourceField="B3"
 * </p>
 */
@Data
@TableName("system_placeholder")
public class SystemPlaceholder {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联系统模板ID */
    private String systemTemplateId;

    /** 所属模块编码 */
    private String moduleCode;

    /**
     * 占位符完整名称，如：清单模板-数据表-B3
     * 对应模板中 {{清单模板-数据表-B3}} 的内部名称
     */
    private String name;

    /** 显示名称（可读） */
    private String displayName;

    /**
     * 占位符类型：
     * text  — 文本段落
     * table — 表格单元格
     * chart — Word内嵌可编辑图表
     * image — 静态图片
     */
    private String type;

    /**
     * 数据来源：
     * list — 清单Excel模板
     * bvd  — BVD数据Excel模板
     */
    private String dataSource;

    /** Excel Sheet名称，如：数据表 */
    private String sourceSheet;

    /** 单元格地址，如：B3 */
    private String sourceField;

    /** 图表类型：bar|line|pie（type=chart时有效） */
    private String chartType;

    /** 排序 */
    private Integer sort;

    /** 描述 */
    private String description;

    @TableLogic
    private Integer deleted;
}
