package com.fileproc.report.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报告实体，对应 report 表
 */
@Data
@TableName("report")
public class Report {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String companyId;

    /** 关联的企业子模板ID（company_template.id），新架构使用 */
    private String templateId;

    private String name;

    private Integer year;

    /** editing | history */
    private String status;

    /** 文件生成状态：pending | success | failed */
    private String generationStatus;

    /** 文件生成失败时的错误信息（最多500字符） */
    private String generationError;

    private Boolean isManualUpload;

    @TableField(select = false)
    private String filePath;

    private String fileSize;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
