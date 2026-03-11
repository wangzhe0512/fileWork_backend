package com.fileproc.template.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
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

    /** 所属模块ID */
    private String moduleId;

    /** 占位符显示名称 */
    private String name;

    /**
     * 类型：text/table/chart/image/ignore
     */
    private String type;

    /** 数据源标识 */
    private String dataSource;

    /** 来源Sheet名称 */
    private String sourceSheet;

    /** 来源字段名称 */
    private String sourceField;

    /** 占位符说明 */
    private String description;

    /** 排序序号 */
    private Integer sort;

    /** 占位符名称（原始名称，用于匹配） */
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

    /** 逻辑删除标志：0-未删除，1-已删除 */
    @TableLogic
    private Integer deleted;
}
