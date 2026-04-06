-- V27: 将 清单模板-公司间资金融通 从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE
-- 绑定 sheet_name=公司间资金融通交易总结，双行表头合并后9列
UPDATE placeholder_registry
SET ph_type            = 'TABLE_ROW_TEMPLATE',
    sheet_name         = '公司间资金融通交易总结',
    title_keywords     = '["公司间资金","资金融通交易总结","公司间融通","资金融通总结"]',
    column_defs        = '["缔约方","公司间资金融通交易性质","货币","本金（原币）","本金（人民币）","到期期限","利率","利息收入/利息支出（原币）","利息收入/利息支出（人民币）"]',
    available_col_defs = '["缔约方","公司间资金融通交易性质","货币","本金（原币）","本金（人民币）","到期期限","利率","利息收入/利息支出（原币）","利息收入/利息支出（人民币）"]'
WHERE placeholder_name = '清单模板-公司间资金融通'
  AND ph_type = 'TABLE_CLEAR_FULL';
