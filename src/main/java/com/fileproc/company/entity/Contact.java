package com.fileproc.company.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 企业联系人，对应 company_contact 表
 */
@Data
@TableName("company_contact")
public class Contact {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String companyId;

    private String tenantId;

    private String name;

    private String position;

    private String phone;

    private String email;
}
