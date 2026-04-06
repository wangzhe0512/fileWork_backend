-- V20: 将"2 关联公司信息"占位符从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE
-- 补充 sheet_name、column_defs（默认选中3列）和 available_col_defs（全量11列）

UPDATE `placeholder_registry`
SET `ph_type`            = 'TABLE_ROW_TEMPLATE',
    `sheet_name`         = '2 关联公司信息',
    `column_defs`        = '["关联方名称","国家（地区）","关联关系类型"]',
    `available_col_defs` = '["行次","关联方名称","关联方类型","国家（地区）","纳税人证件类型","证件号","关联关系类型","起始日期","截止日期","法定税率","是否享受税收优惠"]'
WHERE `placeholder_name` = '清单模板-2_关联公司信息'
  AND `level` = 'system'
  AND `deleted` = 0;
