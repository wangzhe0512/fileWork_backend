-- V31: 为 清单模板-3_分部财务数据 补全 sheet_name
-- 该占位符类型维持 TABLE_CLEAR_FULL（多级表头复杂财务表，不适合升级为 TABLE_ROW_TEMPLATE）
-- title_keywords 保留不变（用于 Word 报告中精确定位对应表格）
UPDATE placeholder_registry
SET sheet_name  = '3 分部财务数据',
    updated_at  = NOW()
WHERE placeholder_name = '清单模板-3_分部财务数据'
  AND ph_type = 'TABLE_CLEAR_FULL'
  AND deleted = 0;
