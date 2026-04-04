-- V36: 修正公司经营背景相关占位符显示名（清单模板-数据表-B8 与 清单模板-公司经营背景资料 互换）
UPDATE placeholder_registry
SET display_name = '公司经营背景'
WHERE placeholder_name = '清单模板-数据表-B8'
  AND level = 'system' AND deleted = 0;

UPDATE placeholder_registry
SET display_name = '公司经营背景资料'
WHERE placeholder_name = '清单模板-公司经营背景资料'
  AND level = 'system' AND deleted = 0;
