-- V32: 修正 清单模板-行业情况-B4 的 display_name
-- V16 中写的是"行业政策及未来趋势"，漏掉了"情况"，应与 Excel A 列标题保持一致：
-- "3.4. 行业政策情况及未来趋势"
UPDATE `placeholder_registry`
SET `display_name` = '行业政策情况及未来趋势',
    `updated_at`   = NOW()
WHERE `placeholder_name` = '清单模板-行业情况-B4'
  AND `deleted` = 0;
