-- V22: 将"关联关系变化情况"占位符从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE
-- Excel Sheet "关联关系变化情况" 结构：行0为表头（关联方名称|国家/地区|关联关系类型|起止日期|变化原因），行1起为数据行
-- 该表列数少（5列），column_defs 与 available_col_defs 完全相同

UPDATE `placeholder_registry`
SET `ph_type`            = 'TABLE_ROW_TEMPLATE',
    `sheet_name`         = '关联关系变化情况',
    `column_defs`        = '["关联方名称","国家/地区","关联关系类型","起止日期","变化原因"]',
    `available_col_defs` = '["关联方名称","国家/地区","关联关系类型","起止日期","变化原因"]'
WHERE `placeholder_name` = '清单模板-关联关系变化情况'
  AND `level` = 'system'
  AND `deleted` = 0;
