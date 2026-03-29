-- V9：新增占位符注册表（placeholder_registry）和数据源Schema缓存表（data_source_schema）
-- 并初始化系统级默认占位符规则（迁移 ReverseTemplateEngine.PLACEHOLDER_REGISTRY 全部条目）

-- ============================================================
-- 1. 建表：placeholder_registry
-- ============================================================
CREATE TABLE IF NOT EXISTS `placeholder_registry` (
    `id`               VARCHAR(36)   NOT NULL COMMENT '主键UUID',
    `tenant_id`        VARCHAR(36)   DEFAULT NULL COMMENT '租户ID（系统级为NULL）',
    `company_id`       VARCHAR(36)   DEFAULT NULL COMMENT '企业ID（系统级为NULL）',
    `level`            VARCHAR(20)   NOT NULL COMMENT '级别：system / company',
    `placeholder_name` VARCHAR(200)  NOT NULL COMMENT '占位符标准名，对应Word模板中{{...}}内名称',
    `display_name`     VARCHAR(200)  DEFAULT NULL COMMENT '可读展示名',
    `ph_type`          VARCHAR(30)   NOT NULL COMMENT '类型：DATA_CELL/TABLE_CLEAR/TABLE_CLEAR_FULL/TABLE_ROW_TEMPLATE/LONG_TEXT/BVD',
    `data_source`      VARCHAR(20)   DEFAULT NULL COMMENT '数据来源：list / bvd',
    `sheet_name`       VARCHAR(100)  DEFAULT NULL COMMENT 'Excel Sheet名',
    `cell_address`     VARCHAR(20)   DEFAULT NULL COMMENT '单元格坐标，如B1',
    `title_keywords`   VARCHAR(1000) DEFAULT NULL COMMENT 'JSON数组，TABLE_CLEAR系列专用，前置标题关键词',
    `column_defs`      VARCHAR(1000) DEFAULT NULL COMMENT 'JSON数组，TABLE_ROW_TEMPLATE专用，列字段名列表',
    `sort`             INT           NOT NULL DEFAULT 0 COMMENT '排序号，影响引擎处理顺序',
    `enabled`          TINYINT       NOT NULL DEFAULT 1 COMMENT '是否启用：1=启用，0=禁用（企业级可禁用系统规则）',
    `deleted`          TINYINT       NOT NULL DEFAULT 0 COMMENT '软删除标记',
    `created_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_pr_level`      (`level`),
    KEY `idx_pr_company`    (`company_id`),
    KEY `idx_pr_tenant`     (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='占位符注册表（系统级+企业级两级规则）';

-- ============================================================
-- 2. 建表：data_source_schema
-- ============================================================
CREATE TABLE IF NOT EXISTS `data_source_schema` (
    `id`           VARCHAR(36)  NOT NULL COMMENT '主键UUID',
    `data_file_id` VARCHAR(36)  NOT NULL COMMENT '关联data_file.id',
    `tenant_id`    VARCHAR(36)  DEFAULT NULL COMMENT '租户ID',
    `sheet_name`   VARCHAR(100) NOT NULL COMMENT 'Sheet名称',
    `sheet_index`  INT          NOT NULL DEFAULT 0 COMMENT 'Sheet顺序（0起）',
    `fields`       TEXT         DEFAULT NULL COMMENT 'JSON数组，每项含address/label/sampleValue/inferredType',
    `parsed_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '解析时间',
    PRIMARY KEY (`id`),
    KEY `idx_dss_file` (`data_file_id`),
    KEY `idx_dss_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源Schema解析缓存表';

-- ============================================================
-- 3. 初始化系统级默认占位符规则（迁移 PLACEHOLDER_REGISTRY 全部条目）
--    仅在 placeholder_registry 表为空时插入，避免重复执行
-- ============================================================
INSERT INTO `placeholder_registry`
    (`id`, `level`, `placeholder_name`, `display_name`, `ph_type`, `data_source`, `sheet_name`, `cell_address`, `title_keywords`, `column_defs`, `sort`, `enabled`, `deleted`)
SELECT * FROM (

-- ===== 第一类：数据表单元格占位符（清单 → 数据表 Sheet B1~B8） =====
SELECT UUID() AS id, 'system' AS level, '清单模板-数据表-B1' AS placeholder_name, '企业全称'   AS display_name, 'DATA_CELL' AS ph_type, 'list' AS data_source, '数据表' AS sheet_name, 'B1' AS cell_address, NULL AS title_keywords, NULL AS column_defs, 10  AS sort, 1 AS enabled, 0 AS deleted UNION ALL
SELECT UUID(), 'system', '清单模板-数据表-B2', '年度',       'DATA_CELL', 'list', '数据表', 'B2', NULL, NULL, 20,  1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-数据表-B3', '事务所名称', 'DATA_CELL', 'list', '数据表', 'B3', NULL, NULL, 30,  1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-数据表-B4', '事务所简称', 'DATA_CELL', 'list', '数据表', 'B4', NULL, NULL, 40,  1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-数据表-B5', '企业简称',   'DATA_CELL', 'list', '数据表', 'B5', NULL, NULL, 50,  1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-数据表-B6', '母公司全称', 'DATA_CELL', 'list', '数据表', 'B6', NULL, NULL, 60,  1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-数据表-B7', '集团简介',   'LONG_TEXT', 'list', '数据表', 'B7', NULL, NULL, 70,  1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-数据表-B8', '公司概况',   'LONG_TEXT', 'list', '数据表', 'B8', NULL, NULL, 80,  1, 0 UNION ALL

-- ===== 第二类：整表/区域占位符 =====
-- TABLE_CLEAR_FULL（财务状况表整表展开）
SELECT UUID(), 'system', '清单模板-PL',              'PL全表',         'TABLE_CLEAR_FULL', 'list', 'PL',             NULL, '["财务状况"]',                                              NULL, 100, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-PL含特殊因素调整', 'PL含特殊因素',   'TABLE_CLEAR_FULL', 'list', 'PL含特殊因素调整', NULL, '["含特殊","特殊因素调整"]',                                NULL, 110, 1, 0 UNION ALL

-- TABLE_CLEAR_FULL（非财务整表）
SELECT UUID(), 'system', '清单模板-1_组织结构及管理架构',      '组织结构',     'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["组织结构","部门结构","管理架构","组织架构"]',                NULL, 120, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-主要产品-A列中所列所有产品', '主要产品',     'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["主要产品","产品清单","产品信息","产品列表"]',                NULL, 130, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-2_关联公司信息',             '关联公司信息', 'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["关联公司","关联方公司","关联企业"]',                         NULL, 140, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-关联方个人信息',             '关联方个人信息','TABLE_CLEAR_FULL', 'list', NULL, NULL, '["关联方个人","关联个人","个人信息"]',                        NULL, 150, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-关联关系变化情况',           '关联关系变化', 'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["关联关系变化","关联变化","关系变化"]',                       NULL, 160, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-关联交易汇总表',             '关联交易汇总', 'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["关联交易汇总","关联交易总","关联交易合计"]',                 NULL, 170, 1, 0 UNION ALL

-- TABLE_ROW_TEMPLATE（动态行数明细表，需在对应 TABLE_CLEAR_FULL 之前注册）
SELECT UUID(), 'system', '清单模板-4_供应商关联采购明细', '关联采购明细',
    'TABLE_ROW_TEMPLATE', 'list', '4 供应商清单', NULL,
    '["关联采购交易明细","关联采购明细表","采购交易明细表"]',
    '["供应商名称","金额（人民币）","占关联采购总金额比例"]',
    180, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-5_客户关联销售明细', '关联销售明细',
    'TABLE_ROW_TEMPLATE', 'list', '5 客户清单', NULL,
    '["关联销售交易明细","关联销售明细表","销售交易明细表"]',
    '["客户名称","金额（人民币）","占营业收入比例"]',
    190, 1, 0 UNION ALL

SELECT UUID(), 'system', '清单模板-5_客户清单',   '客户清单',   'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["客户清单","主要客户","前五大客户","主要客户情况"]',                  NULL, 200, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-4_供应商清单', '供应商清单', 'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["供应商清单","主要供应商","前五大供应商","供应商情况"]',               NULL, 210, 1, 0 UNION ALL

-- TABLE_ROW_TEMPLATE（劳务支出/收入明细）
SELECT UUID(), 'system', '清单模板-6_劳务支出明细', '接受关联劳务明细',
    'TABLE_ROW_TEMPLATE', 'list', '6 劳务交易表', NULL,
    '["接受关联劳务明细","关联劳务支出明细","劳务支出明细表"]',
    '["关联方名称","交易金额","占总经营成本费用比重（%）"]',
    220, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-6_劳务收入明细', '提供关联劳务明细',
    'TABLE_ROW_TEMPLATE', 'list', '6 劳务交易表', NULL,
    '["提供关联劳务明细","关联劳务收入明细","劳务收入明细表"]',
    '["关联方名称","交易金额","占营业收入比重（%）"]',
    230, 1, 0 UNION ALL

SELECT UUID(), 'system', '清单模板-劳务成本费用归集',          '劳务成本费用',   'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["劳务成本","费用归集","成本归集"]',                          NULL, 240, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-资金融通',                  '资金融通',       'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["资金融通","资金借贷","融通"]',                              NULL, 250, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-有形资产信息',              '有形资产信息',   'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["有形资产","固定资产","资产信息"]',                          NULL, 260, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-功能风险汇总表',            '功能风险汇总',   'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["功能风险","功能分析","风险汇总","功能与风险"]',              NULL, 270, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-3_分部财务数据',            '分部财务数据',   'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["分部财务","分部数据","分部财务数据"]',                      NULL, 280, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-公司经营背景资料',          '公司经营背景',   'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["经营背景","公司背景","背景资料","经营情况"]',                NULL, 290, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单数据模板-公司间资金融通交易总结', '公司间资金融通', 'TABLE_CLEAR_FULL', 'list', NULL, NULL, '["公司间资金","资金融通交易总结","公司间融通","资金融通总结"]', NULL, 300, 1, 0 UNION ALL

-- ===== 第三类：行业情况长文本占位符（清单 → 行业情况 Sheet B1~B5） =====
SELECT UUID(), 'system', '清单模板-行业情况-B1', '行业情况B1', 'LONG_TEXT', 'list', '行业情况', 'B1', NULL, NULL, 310, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-行业情况-B2', '行业情况B2', 'LONG_TEXT', 'list', '行业情况', 'B2', NULL, NULL, 320, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-行业情况-B3', '行业情况B3', 'LONG_TEXT', 'list', '行业情况', 'B3', NULL, NULL, 330, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-行业情况-B4', '行业情况B4', 'LONG_TEXT', 'list', '行业情况', 'B4', NULL, NULL, 340, 1, 0 UNION ALL
SELECT UUID(), 'system', '清单模板-行业情况-B5', '行业情况B5', 'LONG_TEXT', 'list', '行业情况', 'B5', NULL, NULL, 350, 1, 0 UNION ALL

-- ===== 第四类：BVD 数据占位符（BVD Excel 按坐标读取） =====
SELECT UUID(), 'system', 'BVD数据模板-数据表-B1',                 'BVD-可比公司数量',        'BVD', 'bvd', '数据表',         'B1',  NULL, NULL, 400, 1, 0 UNION ALL
SELECT UUID(), 'system', 'BVD数据模板-数据表-B2',                 'BVD-上四分位值',          'BVD', 'bvd', '数据表',         'B2',  NULL, NULL, 410, 1, 0 UNION ALL
SELECT UUID(), 'system', 'BVD数据模板-数据表-B3',                 'BVD-中位值',              'BVD', 'bvd', '数据表',         'B3',  NULL, NULL, 420, 1, 0 UNION ALL
SELECT UUID(), 'system', 'BVD数据模板-数据表-B4',                 'BVD-下四分位值',          'BVD', 'bvd', '数据表',         'B4',  NULL, NULL, 430, 1, 0 UNION ALL
SELECT UUID(), 'system', 'BVD数据模板-AP_YEAR',                   'BVD-AP年度',              'BVD', 'bvd', 'AP',             'A1',  NULL, NULL, 440, 1, 0 UNION ALL
SELECT UUID(), 'system', 'BVD数据模板-AP_Lead_Sheet_YEAR-13-19',  'BVD-AP Lead 13~19行',     'BVD', 'bvd', 'AP Lead Sheet',  'A13', NULL, NULL, 450, 1, 0 UNION ALL
SELECT UUID(), 'system', 'BVD数据模板-SummaryYear-第一张表格',    'BVD-SummaryYear表1',      'BVD', 'bvd', 'SummaryYear',    'A1',  NULL, NULL, 460, 1, 0 UNION ALL
SELECT UUID(), 'system', 'BVD数据模板-SummaryYear-MIN',           'BVD-SummaryYear最低值',   'BVD', 'bvd', 'SummaryYear',    'E14', NULL, NULL, 470, 1, 0 UNION ALL
SELECT UUID(), 'system', 'BVD数据模板-SummaryYear-LQ',            'BVD-SummaryYear下四分位', 'BVD', 'bvd', 'SummaryYear',    'E15', NULL, NULL, 480, 1, 0 UNION ALL
SELECT UUID(), 'system', 'BVD数据模板-SummaryYear-MED',           'BVD-SummaryYear中位值',   'BVD', 'bvd', 'SummaryYear',    'E16', NULL, NULL, 490, 1, 0 UNION ALL
SELECT UUID(), 'system', 'BVD数据模板-SummaryYear-UQ',            'BVD-SummaryYear上四分位', 'BVD', 'bvd', 'SummaryYear',    'E17', NULL, NULL, 500, 1, 0 UNION ALL
SELECT UUID(), 'system', 'BVD数据模板-SummaryYear-MAX',           'BVD-SummaryYear最高值',   'BVD', 'bvd', 'SummaryYear',    'E18', NULL, NULL, 510, 1, 0

) AS init_data
WHERE NOT EXISTS (SELECT 1 FROM `placeholder_registry` WHERE `level` = 'system' LIMIT 1);
