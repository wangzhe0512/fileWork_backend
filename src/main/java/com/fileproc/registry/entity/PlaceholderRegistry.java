package com.fileproc.registry.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 占位符注册表条目实体
 * <p>
 * 对应表 placeholder_registry，支持两级规则：
 * - system 级：系统默认规则（对应原 PLACEHOLDER_REGISTRY 硬编码），tenantId/companyId 为 null
 * - company 级：企业自定义规则，可覆盖或扩展系统级规则
 * </p>
 */
@Data
@TableName("placeholder_registry")
public class PlaceholderRegistry {

    /** 主键 UUID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户 ID（系统级为 null） */
    private String tenantId;

    /** 企业 ID（系统级为 null） */
    private String companyId;

    /**
     * 级别：system / company
     * system 级为全局共享默认规则；company 级为企业专属规则，优先于 system 级生效
     */
    private String level;

    /** 占位符标准名，对应 Word 模板中 {{...}} 内的完整名称 */
    private String placeholderName;

    /** 可读展示名，如"企业全称" */
    private String displayName;

    /**
     * 占位符类型：
     * DATA_CELL / TABLE_CLEAR / TABLE_CLEAR_FULL / TABLE_ROW_TEMPLATE / LONG_TEXT / BVD
     */
    private String phType;

    /** 数据来源：list（清单Excel）/ bvd（BVD Excel） */
    private String dataSource;

    /** Excel Sheet 名 */
    private String sheetName;

    /** 单元格坐标，如 B1（TABLE_CLEAR 类型为 null） */
    private String cellAddress;

    /** JSON 数组，TABLE_CLEAR 系列专用，前置标题关键词列表 */
    private String titleKeywords;

    /** JSON 数组，TABLE_ROW_TEMPLATE 专用，列字段名列表 */
    private String columnDefs;

    /** 排序号，影响引擎处理顺序 */
    private Integer sort;

    /** 是否启用：1=启用，0=禁用（企业级可禁用某条系统规则） */
    private Integer enabled;

    /** 软删除标记 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
