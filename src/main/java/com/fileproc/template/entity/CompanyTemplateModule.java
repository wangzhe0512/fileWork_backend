package com.fileproc.template.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业子模板模块实体
 * <p>
 * 用于按模块组织占位符，模块通过占位符的Sheet名划分。
 * 每个模块包含一组相关占位符，支持模块级别的批量同步。
 * </p>
 */
@Data
@TableName("company_template_module")
public class CompanyTemplateModule {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 子模板ID */
    private String companyTemplateId;

    /**
     * 模块编码（由Sheet名转换）
     * 转换规则：trim() + 空格/横杠转下划线
     * 示例："基本 信息" -> "基本_信息"
     */
    private String code;

    /** 模块名称（Sheet原名） */
    private String name;

    /** 排序序号 */
    private Integer sort;

    /** 模块说明 */
    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 逻辑删除标志：0-未删除，1-已删除 */
    @TableLogic
    private Integer deleted;
}
