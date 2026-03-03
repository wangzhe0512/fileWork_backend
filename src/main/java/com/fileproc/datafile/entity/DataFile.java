package com.fileproc.datafile.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据文件实体，对应 data_file 表
 */
@Data
@TableName("data_file")
public class DataFile {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String companyId;

    private String name;

    /** list | bvd */
    private String type;

    private Integer year;

    /** 文件大小显示字符串，如 "2.4 MB" */
    private String size;

    /** 服务器存储路径 */
    @TableField(select = false)
    private String filePath;

    private LocalDateTime uploadAt;

    @TableLogic
    private Integer deleted;
}
