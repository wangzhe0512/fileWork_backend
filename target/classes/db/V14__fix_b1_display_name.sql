-- V14: 将 清单模板-数据表-B1 的 display_name 从 '企业全称' 修正为 '企业名称'
-- 背景：清单模板 Excel 数据表 Sheet A1 标签写的是"企业名称"，与系统注册表保持一致

UPDATE `placeholder_registry`
SET
    `display_name` = '企业名称',
    `updated_at`   = NOW()
WHERE `level` = 'system'
  AND `placeholder_name` = '清单模板-数据表-B1'
  AND `deleted` = 0;
