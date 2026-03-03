package com.fileproc.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 系统配置实体，对应 sys_config 表
 */
@Data
@TableName("sys_config")
public class SysConfig {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    @Size(max = 100, message = "站点名称最长100个字符")
    private String siteName;

    @Size(max = 500, message = "Logo URL 最长500个字符")
    private String logoUrl;

    @Size(max = 100, message = "ICP 备案号最长100个字符")
    private String icp;

    @Min(value = 1024, message = "最大上传大小不能低于1KB")
    @Max(value = 524288000, message = "最大上传大小不能超过500MB")
    private Integer maxFileSize;
}
