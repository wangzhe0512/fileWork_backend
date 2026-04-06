-- V25: 将 清单模板-劳务成本费用归集 从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE
-- 同步重命名为 清单模板-劳务成本归集，绑定 sourceSheet=劳务成本归集，设置 column_defs 和关键词

UPDATE `placeholder_registry`
SET `placeholder_name`  = '清单模板-劳务成本归集',
    `display_name`      = '劳务成本归集',
    `ph_type`           = 'TABLE_ROW_TEMPLATE',
    `sheet_name`        = '劳务成本归集',
    `title_keywords`    = '["劳务成本归集","成本归集","劳务成本费用归集"]',
    `column_defs`       = '["劳务内容","分配方法","总成本费用","所需承担的比例"]',
    `available_col_defs` = '["劳务内容","分配方法","总成本费用","所需承担的比例"]'
WHERE `placeholder_name` = '清单模板-劳务成本费用归集'
  AND `level` = 'system' AND `deleted` = 0;
