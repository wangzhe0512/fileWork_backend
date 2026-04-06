-- V28: 将 清单模板-有形资产信息 从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE
-- 绑定 sheet_name=有形资产信息，单行表头，3列（资产净值、年初数、年末数），合计行→subtotal
UPDATE placeholder_registry
SET ph_type            = 'TABLE_ROW_TEMPLATE',
    sheet_name         = '有形资产信息',
    title_keywords     = '["有形资产","固定资产","资产信息"]',
    column_defs        = '["资产净值","年初数","年末数"]',
    available_col_defs = '["资产净值","年初数","年末数"]'
WHERE placeholder_name = '清单模板-有形资产信息'
  AND ph_type = 'TABLE_CLEAR_FULL';
