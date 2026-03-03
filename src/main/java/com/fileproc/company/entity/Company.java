package com.fileproc.company.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 企业档案实体，对应 company 表
 */
@Data
@TableName("company")
public class Company {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String name;

    private String alias;

    private String industry;

    private String taxId;

    private LocalDate establishDate;

    private String address;

    private String businessScope;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;

    /** 联系人列表（非数据库字段，查询时关联加载） */
    @TableField(exist = false)
    private List<Contact> contacts;
}
