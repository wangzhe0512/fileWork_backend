-- V15: 将 清单模板-数据表-B7/B8 的 display_name 修正为与清单 Excel 数据表 A 列标签一致
-- B7: '集团简介' -> '集团情况描述'
-- B8: '公司概况' -> '公司经营背景资料'

UPDATE `placeholder_registry`
SET
    `display_name` = '集团情况描述',
    `updated_at`   = NOW()
WHERE `level` = 'system'
  AND `placeholder_name` = '清单模板-数据表-B7'
  AND `deleted` = 0;

UPDATE `placeholder_registry`
SET
    `display_name` = '公司经营背景资料',
    `updated_at`   = NOW()
WHERE `level` = 'system'
  AND `placeholder_name` = '清单模板-数据表-B8'
  AND `deleted` = 0;
