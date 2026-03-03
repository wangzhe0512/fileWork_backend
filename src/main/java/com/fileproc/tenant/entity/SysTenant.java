package com.fileproc.tenant.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户实体，对应 sys_tenant 表
 */
@Data
@TableName("sys_tenant")
public class SysTenant {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @NotBlank(message = "租户名称不能为空")
    @Size(max = 100, message = "租户名称最长100个字符")
    private String name;

    @NotBlank(message = "租户编码不能为空")
    @Size(max = 50, message = "租户编码最长50个字符")
    @Pattern(regexp = "^[a-z0-9_-]+$", message = "租户编码只能包含小写字母、数字、下划线和连字符")
    private String code;

    private String status;

    private Integer adminCount;

    @Size(max = 500, message = "Logo URL最长500个字符")
    private String logoUrl;

    private String description;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
