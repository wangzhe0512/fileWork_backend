package com.fileproc.template.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统标准模板实体（系统级，不绑定租户）
 * 对应表：system_template
 */
@Data
@TableName("system_template")
public class SystemTemplate {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 模板名称 */
    private String name;

    /** 版本号 */
    private String version;

    /** 标准Word模板文件路径 */
    @TableField(select = false)
    private String wordFilePath;

    /** 清单Excel模板文件路径 */
    @TableField(select = false)
    private String listExcelPath;

    /** BVD数据Excel模板文件路径 */
    @TableField(select = false)
    private String bvdExcelPath;

    /** 状态：active | inactive */
    private String status;

    /** 描述 */
    private String description;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
