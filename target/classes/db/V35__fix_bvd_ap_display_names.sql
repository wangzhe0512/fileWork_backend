-- V35: 修正 BVD AP 相关占位符显示名
UPDATE placeholder_registry
SET display_name = 'BVD-AP YEAR'
WHERE placeholder_name = 'BVD数据模板-AP_YEAR'
  AND level = 'system' AND deleted = 0;

UPDATE placeholder_registry
SET display_name = 'BVD-AP Lead'
WHERE placeholder_name = 'BVD数据模板-AP_Lead_Sheet_YEAR-13-19'
  AND level = 'system' AND deleted = 0;
