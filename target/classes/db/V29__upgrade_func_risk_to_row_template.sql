-- V29: 将 清单模板-功能风险汇总表 从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE
-- 绑定 sheet_name=功能风险汇总表，单行表头，4列（序号、风险、【清单模板-数据表B5】、关联方），无合计行
UPDATE placeholder_registry
SET ph_type            = 'TABLE_ROW_TEMPLATE',
    sheet_name         = '功能风险汇总表',
    title_keywords     = '["功能风险","功能分析","风险汇总","功能与风险"]',
    column_defs        = '["序号","风险","【清单模板-数据表B5】","关联方"]',
    available_col_defs = '["序号","风险","【清单模板-数据表B5】","关联方"]'
WHERE placeholder_name = '清单模板-功能风险汇总表'
  AND ph_type = 'TABLE_CLEAR_FULL';
