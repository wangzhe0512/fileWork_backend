package com.fileproc.template.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 报告模块实体，对应 report_module 表
 */
@Data
@TableName(value = "report_module", autoResultMap = true)
public class ReportModule {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String companyId;

    @NotBlank(message = "模块名称不能为空")
    @Size(max = 100, message = "模块名称最长100个字符")
    private String name;

    @NotBlank(message = "模块编码不能为空")
    @Size(max = 50, message = "模块编码最长50个字符")
    private String code;

    @Size(max = 500, message = "描述最长500个字符")
    private String description;

    /** 占位符id列表（JSON存储） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> placeholders;

    private Integer sort;

    @TableLogic
    private Integer deleted;
}
