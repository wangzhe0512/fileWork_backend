-- V37: 按模板 Sheet 顺序重排所有占位符的 sort 值
-- 排序规则：先按类型大组（DATA_CELL→LONG_TEXT→TABLE_ROW_TEMPLATE→TABLE_CLEAR_FULL→BVD），组内按模板 Sheet 顺序

-- ─────────────────────────────────────────────────────────
-- 1. DATA_CELL（清单模板，sort 10~70）
--    Sheet 顺序：数据表(B1~B6) → 被测关联交易(B1)
-- ─────────────────────────────────────────────────────────
-- B1~B6 sort 不变（10~60），仅更新被测关联交易
UPDATE placeholder_registry SET sort = 70  WHERE placeholder_name = '清单模板-被测关联交易-B1'         AND level = 'system' AND deleted = 0;

-- ─────────────────────────────────────────────────────────
-- 2. LONG_TEXT（清单模板，sort 110~190）
--    Sheet 顺序：数据表(B7,B8) → 集团背景情况(A1) → 公司经营背景资料(A1) → 行业情况(B1~B5)
-- ─────────────────────────────────────────────────────────
UPDATE placeholder_registry SET sort = 110 WHERE placeholder_name = '清单模板-数据表-B7'              AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 120 WHERE placeholder_name = '清单模板-数据表-B8'              AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 130 WHERE placeholder_name = '清单模板-集团背景情况-A1'        AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 140 WHERE placeholder_name = '清单模板-公司经营背景资料'       AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 150 WHERE placeholder_name = '清单模板-行业情况-B1'            AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 160 WHERE placeholder_name = '清单模板-行业情况-B2'            AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 170 WHERE placeholder_name = '清单模板-行业情况-B3'            AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 180 WHERE placeholder_name = '清单模板-行业情况-B4'            AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 190 WHERE placeholder_name = '清单模板-行业情况-B5'            AND level = 'system' AND deleted = 0;

-- ─────────────────────────────────────────────────────────
-- 3. TABLE_ROW_TEMPLATE（清单模板，sort 210~350）
--    Sheet 顺序：主要产品 → 1组织结构 → 2关联公司 → 关联方个人 → 关联关系变化
--              → 关联交易汇总 → 4供应商 → 5客户 → 6劳务(支出/收入)
--              → 劳务成本归集 → 资金融通 → 公司间资金融通 → 有形资产 → 功能风险汇总
-- ─────────────────────────────────────────────────────────
UPDATE placeholder_registry SET sort = 210 WHERE placeholder_name = '清单模板-主要产品'                        AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 220 WHERE placeholder_name = '清单模板-1_组织结构及管理架构'            AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 230 WHERE placeholder_name = '清单模板-2_关联公司信息'                  AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 240 WHERE placeholder_name = '清单模板-关联方个人信息'                  AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 250 WHERE placeholder_name = '清单模板-关联关系变化情况'                AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 260 WHERE placeholder_name = '清单模板-关联交易汇总表'                  AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 270 WHERE placeholder_name = '清单模板-4_供应商清单-关联采购明细'       AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 280 WHERE placeholder_name = '清单模板-5_客户清单-关联销售明细'         AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 290 WHERE placeholder_name = '清单模板-6_劳务交易表-劳务支出明细'      AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 300 WHERE placeholder_name = '清单模板-6_劳务交易表-劳务收入明细'      AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 310 WHERE placeholder_name = '清单模板-劳务成本归集'                    AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 320 WHERE placeholder_name = '清单模板-资金融通'                        AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 330 WHERE placeholder_name = '清单模板-公司间资金融通'                  AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 340 WHERE placeholder_name = '清单模板-有形资产信息'                    AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 350 WHERE placeholder_name = '清单模板-功能风险汇总表'                  AND level = 'system' AND deleted = 0;

-- ─────────────────────────────────────────────────────────
-- 4. TABLE_CLEAR_FULL（清单模板，sort 370~390）
--    Sheet 顺序：3分部财务数据 → PL → PL含特殊因素调整
-- ─────────────────────────────────────────────────────────
UPDATE placeholder_registry SET sort = 370 WHERE placeholder_name = '清单模板-3_分部财务数据'         AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 380 WHERE placeholder_name = '清单模板-PL'                     AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 390 WHERE placeholder_name = '清单模板-PL含特殊因素调整'       AND level = 'system' AND deleted = 0;

-- ─────────────────────────────────────────────────────────
-- 5. BVD 数据模板（sort 410~520）
--    Sheet 顺序：数据表(B1~B4) → SummaryYear(表格+MIN~MAX) → AP Lead Sheet YEAR → AP YEAR
-- ─────────────────────────────────────────────────────────
UPDATE placeholder_registry SET sort = 410 WHERE placeholder_name = 'BVD数据模板-数据表-B1'                  AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 420 WHERE placeholder_name = 'BVD数据模板-数据表-B2'                  AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 430 WHERE placeholder_name = 'BVD数据模板-数据表-B3'                  AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 440 WHERE placeholder_name = 'BVD数据模板-数据表-B4'                  AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 450 WHERE placeholder_name = 'BVD数据模板-SummaryYear-第一张表格'     AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 460 WHERE placeholder_name = 'BVD数据模板-SummaryYear-MIN'            AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 470 WHERE placeholder_name = 'BVD数据模板-SummaryYear-LQ'             AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 480 WHERE placeholder_name = 'BVD数据模板-SummaryYear-MED'            AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 490 WHERE placeholder_name = 'BVD数据模板-SummaryYear-UQ'             AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 500 WHERE placeholder_name = 'BVD数据模板-SummaryYear-MAX'            AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 510 WHERE placeholder_name = 'BVD数据模板-AP_Lead_Sheet_YEAR-13-19'  AND level = 'system' AND deleted = 0;
UPDATE placeholder_registry SET sort = 520 WHERE placeholder_name = 'BVD数据模板-AP_YEAR'                   AND level = 'system' AND deleted = 0;
