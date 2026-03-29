package com.fileproc.datafile.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源 Schema 解析缓存实体
 * <p>
 * 对应表 data_source_schema，每条记录对应一个 Excel 文件的某个 Sheet 的字段结构快照。
 * 解析结果持久化，同一文件不重复解析（除非手动刷新）。
 * </p>
 */
@Data
@TableName("data_source_schema")
public class DataSourceSchema {

    /** 主键 UUID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联 data_file.id */
    private String dataFileId;

    /** 租户 ID */
    private String tenantId;

    /** Sheet 名称 */
    private String sheetName;

    /** Sheet 顺序（0起） */
    private Integer sheetIndex;

    /**
     * 字段列表 JSON 数组，每项结构：
     * {"address":"B1","label":"企业全称","sampleValue":"xxx","inferredType":"TEXT"}
     */
    private String fields;

    /** 解析时间 */
    private LocalDateTime parsedAt;
}
