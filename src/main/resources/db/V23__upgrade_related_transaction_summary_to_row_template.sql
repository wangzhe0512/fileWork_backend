-- V23: 将"清单模板-关联交易汇总表"占位符从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE
-- Excel 结构：行0主表头，行1副表头（A/B/C=A+B，跳过），行2起数据行，col0非空且≠"合计"为有效行
UPDATE placeholder_registry
SET ph_type            = 'TABLE_ROW_TEMPLATE',
    sheet_name         = '关联交易汇总表',
    column_defs        = '["关联交易类型","境外交易金额","境内交易金额","交易总额"]',
    available_col_defs = '["关联交易类型","境外交易金额","境内交易金额","交易总额"]'
WHERE placeholder_name = '清单模板-关联交易汇总表'
  AND level = 'system'
  AND deleted = 0;
