-- V34: 废弃 清单模板-5_客户清单（TABLE_CLEAR_FULL，sourceSheet=null，生成引擎无法正确填充）
-- 实际客户数据由 清单模板-5_客户清单-关联销售明细（TABLE_ROW_TEMPLATE）处理，此条目无用
UPDATE placeholder_registry
SET deleted = 1
WHERE placeholder_name = '清单模板-5_客户清单'
  AND level = 'system' AND deleted = 0;
