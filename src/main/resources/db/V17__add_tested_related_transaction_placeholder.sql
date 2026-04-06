-- V17: 新增"被测关联交易利润水平"占位符
--    清单"被测关联交易" Sheet B1 为被测方关联交易利润水平数值（如 3.73%）。
--    使用 DATA_CELL 类型，精确读取该单元格。

INSERT INTO `placeholder_registry`
    (`id`, `level`, `placeholder_name`, `display_name`, `ph_type`,
     `data_source`, `sheet_name`, `cell_address`, `title_keywords`, `column_defs`,
     `sort`, `enabled`, `deleted`)
SELECT UUID(), 'system', '清单模板-被测关联交易-B1', '被测关联交易利润水平', 'DATA_CELL',
       'list', '被测关联交易', 'B1', NULL, NULL,
       520, 1, 0
WHERE NOT EXISTS (
    SELECT 1 FROM `placeholder_registry`
    WHERE `placeholder_name` = '清单模板-被测关联交易-B1' AND `deleted` = 0
);
