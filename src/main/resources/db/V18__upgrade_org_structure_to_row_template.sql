-- V18: 将"1 组织结构及管理架构"占位符从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE
-- 补充 sheet_name 和 column_defs，以支持行数动态的部门表格自动克隆填充

UPDATE `placeholder_registry`
SET `ph_type`      = 'TABLE_ROW_TEMPLATE',
    `sheet_name`   = '1 组织结构及管理架构',
    `column_defs`  = '["主要部门","人数","主要职责范围","汇报对象","汇报对象主要办公所在"]'
WHERE `placeholder_name` = '清单模板-1_组织结构及管理架构'
  AND `level` = 'system'
  AND `deleted` = 0;
