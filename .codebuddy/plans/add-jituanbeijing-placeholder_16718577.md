---
name: add-jituanbeijing-placeholder
overview: 新增 V16 迁移脚本，同时新增"集团背景情况"占位符 + 修正"公司经营背景资料"占位符类型
todos:
  - id: create-v16-sql
    content: 新建 V16__add_and_fix_background_placeholders.sql，合并新增集团背景情况占位符和修正公司经营背景资料类型
    status: completed
---

## 用户需求

在 Flyway 迁移脚本中合并处理三件事：

1. **新增**占位符：`清单模板-集团背景情况-A1`（LONG_TEXT，Sheet="集团背景情况"，cell=A1，sort=360），对应清单模板中"集团背景情况" Sheet 的 A1 长文本，此前注册表中完全缺失该条目。

2. **修正**占位符：`清单模板-公司经营背景资料` 的类型由 `TABLE_CLEAR_FULL` 改为 `LONG_TEXT`，同时补充 `sheet_name="公司经营背景资料"`、`cell_address="A1"`，因为该 Sheet 实际只有 A1 一个长文本单元格，原来注册为整表替换类型不正确。

3. **更新 displayName**：将"行业情况" Sheet 对应的 B1~B5 五个占位符的 `display_name` 由"行业情况Bx"改为更友好的展示名（依据 A 列标题）：

- B1：`全球行业情况`
- B2：`中国行业情况`
- B3：`行业竞争情况`
- B4：`行业政策及未来趋势`
- B5：`行业小结`

## 产品概述

通过新建 Flyway V16 迁移脚本，修正占位符注册表数据，使系统能正确识别和处理清单模板中"集团背景情况"和"公司经营背景资料"两个 Sheet 的长文本内容，同时优化"行业情况"五个占位符的展示名。

## 核心功能

- 新增 `清单模板-集团背景情况-A1`，LONG_TEXT 类型，sort=360
- 修正 `清单模板-公司经营背景资料`：ph_type 改 LONG_TEXT，补充 sheet_name 和 cell_address，清除无效 title_keywords
- 更新行业情况 B1~B5 的 display_name 为友好展示名（全球行业情况/中国行业情况/行业竞争情况/行业政策及未来趋势/行业小结）
- 三个操作合并在同一个 V16 脚本中，一次迁移完成

## 技术栈

现有项目：Spring Boot + Flyway + MySQL，沿用已有迁移文件规范，无新依赖。

## 实现方案

新建 `V16__add_and_fix_background_placeholders.sql`，包含三段 SQL：

1. **INSERT**（用 WHERE NOT EXISTS 防重复）：插入新占位符 `清单模板-集团背景情况-A1`
2. **UPDATE**（直接更新，Flyway 版本锁保证幂等）：修正 `清单模板-公司经营背景资料` 的 ph_type、sheet_name、cell_address、title_keywords
3. **UPDATE × 5**：更新行业情况 B1~B5 的 display_name

**UPDATE 修正字段说明（公司经营背景资料）：**

- `ph_type`: `TABLE_CLEAR_FULL` → `LONG_TEXT`
- `sheet_name`: `NULL` → `公司经营背景资料`
- `cell_address`: `NULL` → `A1`
- `title_keywords`: `["经营背景","公司背景","背景资料","经营情况"]` → `NULL`（LONG_TEXT 类型不使用该字段）
- `sort` 保持 290 不变

**UPDATE 展示名说明（行业情况 B1~B5）：**

| placeholder_name | 旧 display_name | 新 display_name |
| --- | --- | --- |
| 清单模板-行业情况-B1 | 行业情况B1 | 全球行业情况 |
| 清单模板-行业情况-B2 | 行业情况B2 | 中国行业情况 |
| 清单模板-行业情况-B3 | 行业情况B3 | 行业竞争情况 |
| 清单模板-行业情况-B4 | 行业情况B4 | 行业政策及未来趋势 |
| 清单模板-行业情况-B5 | 行业情况B5 | 行业小结 |


**注释风格**：与 V15 保持一致（说明背景和变更原因）。

## 目录结构

```
src/main/resources/db/
└── V16__add_and_fix_background_placeholders.sql  # [NEW] 合并三项占位符注册表修正
                                                   # 1. INSERT 新增 清单模板-集团背景情况-A1 (LONG_TEXT/sort=360)
                                                   # 2. UPDATE 修正 清单模板-公司经营背景资料 (TABLE_CLEAR_FULL→LONG_TEXT)
                                                   # 3. UPDATE 行业情况 B1~B5 display_name 友好化
```

## 关键代码结构

```sql
-- V16: 1. 新增"集团背景情况" Sheet A1 LONG_TEXT 占位符
INSERT INTO `placeholder_registry`
    (`id`, `level`, `placeholder_name`, `display_name`, `ph_type`,
     `data_source`, `sheet_name`, `cell_address`, `title_keywords`, `column_defs`,
     `sort`, `enabled`, `deleted`)
SELECT UUID(), 'system', '清单模板-集团背景情况-A1', '集团背景情况', 'LONG_TEXT',
       'list', '集团背景情况', 'A1', NULL, NULL, 360, 1, 0
WHERE NOT EXISTS (
    SELECT 1 FROM `placeholder_registry`
    WHERE `placeholder_name` = '清单模板-集团背景情况-A1' AND `deleted` = 0
);

-- V16: 2. 修正"公司经营背景资料"类型：TABLE_CLEAR_FULL → LONG_TEXT，补充 sheet_name/cell_address，清除 title_keywords
UPDATE `placeholder_registry`
SET
    `ph_type`        = 'LONG_TEXT',
    `sheet_name`     = '公司经营背景资料',
    `cell_address`   = 'A1',
    `title_keywords` = NULL,
    `updated_at`     = NOW()
WHERE `level` = 'system'
  AND `placeholder_name` = '清单模板-公司经营背景资料'
  AND `deleted` = 0;

-- V16: 3. 更新行业情况 B1~B5 display_name 为友好展示名
UPDATE `placeholder_registry` SET `display_name` = '全球行业情况', `updated_at` = NOW()
WHERE `placeholder_name` = '清单模板-行业情况-B1' AND `deleted` = 0;
UPDATE `placeholder_registry` SET `display_name` = '中国行业情况', `updated_at` = NOW()
WHERE `placeholder_name` = '清单模板-行业情况-B2' AND `deleted` = 0;
UPDATE `placeholder_registry` SET `display_name` = '行业竞争情况', `updated_at` = NOW()
WHERE `placeholder_name` = '清单模板-行业情况-B3' AND `deleted` = 0;
UPDATE `placeholder_registry` SET `display_name` = '行业政策及未来趋势', `updated_at` = NOW()
WHERE `placeholder_name` = '清单模板-行业情况-B4' AND `deleted` = 0;
UPDATE `placeholder_registry` SET `display_name` = '行业小结', `updated_at` = NOW()
WHERE `placeholder_name` = '清单模板-行业情况-B5' AND `deleted` = 0;
```