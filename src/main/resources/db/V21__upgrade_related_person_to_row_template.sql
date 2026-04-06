-- V21: 将"关联方个人信息"占位符从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE
-- Excel Sheet "关联方个人信息" 结构：行0为表头（个人关联方|国籍|关联关系类型|居住地址），行1起为数据行
-- 该表列数少（4列），column_defs 与 available_col_defs 完全相同

UPDATE `placeholder_registry`
SET `ph_type`            = 'TABLE_ROW_TEMPLATE',
    `sheet_name`         = '关联方个人信息',
    `column_defs`        = '["个人关联方","国籍","关联关系类型","居住地址"]',
    `available_col_defs` = '["个人关联方","国籍","关联关系类型","居住地址"]'
WHERE `placeholder_name` = '清单模板-关联方个人信息'
  AND `level` = 'system'
  AND `deleted` = 0;
