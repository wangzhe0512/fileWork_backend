package com.fileproc.template.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业子模板占位符状态实体
 * <p>
 * 记录每个子模板中各占位符的确认状态，支持：
 * 1. 分多次确认（今天确认一部分，明天继续）
 * 2. 刷新页面后状态不丢失
 * 3. 保存草稿功能
 * </p>
 */
@Data
@TableName("company_template_placeholder")
public class CompanyTemplatePlaceholder {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 子模板ID */
    private String companyTemplateId;

    /** 占位符名称 */
    private String placeholderName;

    /**
     * 状态：uncertain(待确认)/confirmed(已确认)/ignored(忽略)
     */
    private String status;

    /**
     * 确认后的类型：text/table/chart/image
     * 用户确认时选择，用于前端显示和后续处理
     */
    private String confirmedType;

    /**
     * 位置信息JSON
     * 格式：{"paragraphIndex":0,"runIndex":1,"offset":10,"elementType":"paragraph"}
     */
    private String positionJson;

    /** 期望值（从Excel读取） */
    private String expectedValue;

    /** 实际值（在Word中找到） */
    private String actualValue;

    /** 冲突/不确定原因 */
    private String reason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
