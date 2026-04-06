-- V16: 合并三项占位符注册表修正
-- 1. 新增 清单模板-集团背景情况-A1（LONG_TEXT，Sheet="集团背景情况"，cell=A1，sort=360）
--    清单模板中"集团背景情况" Sheet 的 A1 为长文本，此前注册表中完全缺失该条目。
-- 2. 修正 清单模板-公司经营背景资料：ph_type 由 TABLE_CLEAR_FULL 改为 LONG_TEXT，
--    补充 sheet_name/cell_address="公司经营背景资料"/A1，清除无效 title_keywords。
--    原因：该 Sheet 实际只有 A1 一个长文本单元格，整表替换类型不正确。
-- 3. 更新行业情况 B1~B5 的 display_name 为更友好的展示名（依据 A 列标题）。

-- ─────────────────────────────────────────────────────────
-- 1. 新增"集团背景情况" Sheet A1 LONG_TEXT 占位符
-- ─────────────────────────────────────────────────────────
INSERT INTO `placeholder_registry`
    (`id`, `level`, `placeholder_name`, `display_name`, `ph_type`,
     `data_source`, `sheet_name`, `cell_address`, `title_keywords`, `column_defs`,
     `sort`, `enabled`, `deleted`)
SELECT UUID(), 'system', '清单模板-集团背景情况-A1', '集团背景情况', 'LONG_TEXT',
       'list', '集团背景情况', 'A1', NULL, NULL, 360, 1, 0
WHERE NOT EXISTS (
    SELECT 1 FROM `placeholder_registry`
    WHERE `placeholder_name` = '清单模板-集团背景情况-A1' AND `deleted` = 0
);

-- ─────────────────────────────────────────────────────────
-- 2. 修正"公司经营背景资料"：TABLE_CLEAR_FULL → LONG_TEXT，补充 sheet_name/cell_address，清除 title_keywords
-- ─────────────────────────────────────────────────────────
UPDATE `placeholder_registry`
SET
    `ph_type`        = 'LONG_TEXT',
    `sheet_name`     = '公司经营背景资料',
    `cell_address`   = 'A1',
    `title_keywords` = NULL,
    `updated_at`     = NOW()
WHERE `level` = 'system'
  AND `placeholder_name` = '清单模板-公司经营背景资料'
  AND `deleted` = 0;

-- ─────────────────────────────────────────────────────────
-- 3. 更新行业情况 B1~B5 display_name 为友好展示名
-- ─────────────────────────────────────────────────────────
UPDATE `placeholder_registry` SET `display_name` = '全球行业情况', `updated_at` = NOW()
WHERE `placeholder_name` = '清单模板-行业情况-B1' AND `deleted` = 0;

UPDATE `placeholder_registry` SET `display_name` = '中国行业情况', `updated_at` = NOW()
WHERE `placeholder_name` = '清单模板-行业情况-B2' AND `deleted` = 0;

UPDATE `placeholder_registry` SET `display_name` = '行业竞争情况', `updated_at` = NOW()
WHERE `placeholder_name` = '清单模板-行业情况-B3' AND `deleted` = 0;

UPDATE `placeholder_registry` SET `display_name` = '行业政策及未来趋势', `updated_at` = NOW()
WHERE `placeholder_name` = '清单模板-行业情况-B4' AND `deleted` = 0;

UPDATE `placeholder_registry` SET `display_name` = '行业小结', `updated_at` = NOW()
WHERE `placeholder_name` = '清单模板-行业情况-B5' AND `deleted` = 0;
