package com.fileproc.template.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业子模板实体（企业级，绑定租户）
 * 对应表：company_template
 * <p>
 * 由历史报告反向生成，保留企业排版风格，数据替换为占位符。
 * 每个企业可有多个版本的子模板。
 * </p>
 */
@Data
@TableName("company_template")
public class CompanyTemplate {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    private String tenantId;

    /** 企业ID */
    private String companyId;

    /** 关联系统模板ID */
    private String systemTemplateId;

    /** 子模板名称 */
    private String name;

    /** 来源年份（反向生成时历史报告的年份） */
    private Integer year;

    /** 来源历史报告ID（反向生成时的源报告，可为空） */
    private String sourceReportId;

    /** 子模板Word文件路径 */
    @TableField(select = false)
    private String filePath;

    /** 文件大小显示（如：1.2 MB） */
    private String fileSize;

    /** 状态：active | archived */
    private String status;

    /**
     * 获取是否激活状态（用于前端展示）
     */
    public Boolean getIsActive() {
        return "active".equals(status);
    }

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
