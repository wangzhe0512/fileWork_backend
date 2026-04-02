-- V13: 将 BVD数据模板-SummaryYear-第一张表格 从 BVD 单值类型升级为 TABLE_ROW_TEMPLATE 动态行模板类型
-- 背景：可比公司列表行数动态（10~15家），需用行模板克隆填充而非单值占位符
-- 默认 column_defs = ["#","COMPANY"]，企业可通过 /placeholder-registry/{id}/update-column-defs 接口自定义扩展

UPDATE `placeholder_registry`
SET
    `display_name`   = 'BVD-SummaryYear可比公司列表',
    `ph_type`        = 'TABLE_ROW_TEMPLATE',
    `cell_address`   = NULL,
    `title_keywords` = '["可比公司列表","可比公司","Comparable Companies","Comparable Company"]',
    `column_defs`    = '["#","COMPANY"]',
    `updated_at`     = NOW()
WHERE `level` = 'system'
  AND `placeholder_name` = 'BVD数据模板-SummaryYear-第一张表格'
  AND `deleted` = 0;
