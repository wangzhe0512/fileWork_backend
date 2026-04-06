-- V30: 将 清单模板-主要产品 从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE
-- 绑定 sheet_name=主要产品，单行表头，3列（产品、销售额（万元）、占比(%)），含合计行→subtotal
UPDATE placeholder_registry
SET ph_type            = 'TABLE_ROW_TEMPLATE',
    sheet_name         = '主要产品',
    title_keywords     = '["主要产品","产品清单","产品信息","产品列表"]',
    column_defs        = '["产品","销售额（万元）","占比(%)"]',
    available_col_defs = '["产品","销售额（万元）","占比(%)"]'
WHERE placeholder_name = '清单模板-主要产品'
  AND ph_type = 'TABLE_CLEAR_FULL';
