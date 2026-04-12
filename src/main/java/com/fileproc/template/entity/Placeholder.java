package com.fileproc.template.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 占位符实体，对应 placeholder 表
 */
@Data
@TableName("placeholder")
public class Placeholder {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String companyId;

    @NotBlank(message = "占位符名称不能为空")
    @Size(max = 100, message = "占位符名称最长100个字符")
    private String name;

    /** text | table | chart | image */
    @Pattern(regexp = "^(text|table|chart|image)$", message = "type只能为 text、table、chart 或 image")
    private String type;

    /** list | bvd */
    private String dataSource;

    @Size(max = 100, message = "源工作表名最长100个字符")
    private String sourceSheet;

    @Size(max = 100, message = "源字段名最长100个字符")
    private String sourceField;

    /** bar | line | pie */
    private String chartType;

    @Size(max = 500, message = "描述最长500个字符")
    private String description;

    @TableLogic
    private Integer deleted;
}
