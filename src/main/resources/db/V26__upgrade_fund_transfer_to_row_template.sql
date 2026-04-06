-- V26: 将 清单模板-资金融通 从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE
-- 绑定 sheet_name=资金融通，column_defs=["关联方","金额"]
-- title_keywords 保持原有三个关键词
UPDATE placeholder_registry
SET ph_type            = 'TABLE_ROW_TEMPLATE',
    sheet_name         = '资金融通',
    title_keywords     = '["资金融通","资金借贷","融通"]',
    column_defs        = '["关联方","金额"]',
    available_col_defs = '["关联方","金额"]'
WHERE placeholder_name = '清单模板-资金融通'
  AND ph_type = 'TABLE_CLEAR_FULL';
