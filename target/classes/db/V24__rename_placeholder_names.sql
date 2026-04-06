-- V24: 统一重命名6个占位符名称，对齐 Excel Sheet 名命名规范
-- 执行后 placeholder_name 与 ReverseTemplateEngine.java 注册表保持一致

UPDATE placeholder_registry
SET placeholder_name = '清单模板-4_供应商清单-关联采购明细'
WHERE placeholder_name = '清单模板-4_供应商关联采购明细'
  AND level = 'system' AND deleted = 0;

UPDATE placeholder_registry
SET placeholder_name = '清单模板-5_客户清单-关联销售明细'
WHERE placeholder_name = '清单模板-5_客户关联销售明细'
  AND level = 'system' AND deleted = 0;

UPDATE placeholder_registry
SET placeholder_name = '清单模板-6_劳务交易表-劳务支出明细'
WHERE placeholder_name = '清单模板-6_劳务支出明细'
  AND level = 'system' AND deleted = 0;

UPDATE placeholder_registry
SET placeholder_name = '清单模板-6_劳务交易表-劳务收入明细'
WHERE placeholder_name = '清单模板-6_劳务收入明细'
  AND level = 'system' AND deleted = 0;

UPDATE placeholder_registry
SET placeholder_name = '清单模板-主要产品'
WHERE placeholder_name = '清单模板-主要产品-A列中所列所有产品'
  AND level = 'system' AND deleted = 0;

UPDATE placeholder_registry
SET placeholder_name = '清单模板-公司间资金融通'
WHERE placeholder_name = '清单数据模板-公司间资金融通交易总结'
  AND level = 'system' AND deleted = 0;
