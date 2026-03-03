package com.fileproc.template.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模板实体，对应 template 表
 */
@Data
@TableName("template")
public class Template {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String companyId;

    @NotBlank(message = "模板名称不能为空")
    @Size(max = 100, message = "模板名称最长100个字符")
    private String name;

    @NotNull(message = "年度不能为空")
    private Integer year;

    /** active | archived */
    private String status;

    @Size(max = 500, message = "描述最长500个字符")
    private String description;

    @TableField(select = false)
    private String filePath;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
