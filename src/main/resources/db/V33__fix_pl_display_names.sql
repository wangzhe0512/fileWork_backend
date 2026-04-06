-- V33: 修正 PL 相关占位符的 display_name
-- 清单模板-PL: PL全表 → PL财务数据
-- 清单模板-PL含特殊因素调整: PL含特殊因素 → PL财务数据（含特殊因素调整）

UPDATE `placeholder_registry`
SET `display_name` = 'PL财务数据',
    `updated_at`   = NOW()
WHERE `placeholder_name` = '清单模板-PL'
  AND `deleted` = 0;

UPDATE `placeholder_registry`
SET `display_name` = 'PL财务数据（含特殊因素调整）',
    `updated_at`   = NOW()
WHERE `placeholder_name` = '清单模板-PL含特殊因素调整'
  AND `deleted` = 0;
