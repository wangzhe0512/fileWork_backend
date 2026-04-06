-- V19: 新增 available_col_defs 字段，将"全量可选列"与"默认选中列（column_defs）"职责分离
-- 背景：column_defs 仅作默认选中列，available_col_defs 作前端列选择器展示的全量可选列
-- 修复 BVD SummaryYear 列选择器回归（从12列缩减为2列），并为组织结构配置5列可选+前3列默认

-- ============================================================
-- 1. 新增 available_col_defs 列
-- ============================================================
ALTER TABLE `placeholder_registry`
    ADD COLUMN `available_col_defs` VARCHAR(1000) DEFAULT NULL
        COMMENT 'JSON数组，TABLE_ROW_TEMPLATE专用，全量可选列字段名列表（前端列选择器数据源）；为空时 fallback 到 column_defs'
    AFTER `column_defs`;

-- ============================================================
-- 2. BVD SummaryYear：available_col_defs = 12列全量（来自 BVD_COLUMN_KEYWORD_MAP）
--    column_defs 保持 ["#","COMPANY"]（默认选中2列，不变）
-- ============================================================
UPDATE `placeholder_registry`
SET `available_col_defs` = '["#","COMPANY","FY2023_STATUS","FY2022_STATUS","NCP_CURRENT","NCP_PRIOR","Remarks","Sales","CoGS","SGA","Depreciation","OP"]'
WHERE `level` = 'system'
  AND `placeholder_name` = 'BVD数据模板-SummaryYear-第一张表格'
  AND `deleted` = 0;

-- ============================================================
-- 3. 组织结构：available_col_defs = 5列全量，column_defs 修正为前3列默认选中
--    （V18 中 column_defs 写的是5列，V19 在此一并修正）
-- ============================================================
UPDATE `placeholder_registry`
SET `available_col_defs` = '["主要部门","人数","主要职责范围","汇报对象","汇报对象主要办公所在"]',
    `column_defs`        = '["主要部门","人数","主要职责范围"]'
WHERE `level` = 'system'
  AND `placeholder_name` = '清单模板-1_组织结构及管理架构'
  AND `deleted` = 0;

-- ============================================================
-- 4. 其他所有 system 级 TABLE_ROW_TEMPLATE 占位符：available_col_defs = column_defs（批量兜底）
--    供应商明细、客户明细、劳务支出、劳务收入等全选场景，零感知
-- ============================================================
UPDATE `placeholder_registry`
SET `available_col_defs` = `column_defs`
WHERE `level` = 'system'
  AND `ph_type` = 'TABLE_ROW_TEMPLATE'
  AND `available_col_defs` IS NULL
  AND `deleted` = 0;
