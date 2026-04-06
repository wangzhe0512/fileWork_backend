---
name: add-jituanbeijing-placeholder
overview: 新增 V16 迁移脚本，向 placeholder_registry 表插入"清单模板-集团背景情况-A1"占位符
todos:
  - id: create-v16-sql
    content: 新建 V16__add_jituan_background_placeholder.sql，插入集团背景情况 LONG_TEXT 占位符记录
    status: pending
---

## 用户需求

在占位符注册表中补充缺失的"集团背景情况"Sheet 对应占位符，使系统能正确识别和处理清单模板中该 Sheet 的 A1 长文本数据。

## 产品概述

通过新建 Flyway 迁移 SQL 文件（V16），向 `placeholder_registry` 表插入一条系统级 `LONG_TEXT` 类型占位符记录，覆盖清单模板"集团背景情况" Sheet 的 A1 单元格。

## 核心功能

- 新增占位符：`清单模板-集团背景情况-A1`，展示名"集团背景情况"，类型 LONG_TEXT，来源 list，Sheet 名"集团背景情况"，坐标 A1，sort=360
- 防重复插入：使用 `WHERE NOT EXISTS` 模式，保证迁移幂等性
- 不影响任何已有注册表记录

## 技术栈

现有项目：Spring Boot + Flyway + MySQL，沿用已有迁移文件规范，无新依赖。

## 实现方案

新建 `V16__add_jituan_background_placeholder.sql`，使用 `INSERT INTO ... SELECT ... WHERE NOT EXISTS(...)` 防重复插入模式（与 V9 末尾模式一致），插入一条 LONG_TEXT 系统级占位符记录。

**sort 值选择**：行业情况 B5 = 350，BVD 类从 400 开始，新条目 sort=360 正好填入此空档，与清单模板其他 LONG_TEXT 条目保持语义连贯。

## 实现细节

- `title_keywords` 和 `column_defs` 均为 `NULL`（LONG_TEXT 类型不使用这两列）
- `tenant_id`、`company_id` 均为 `NULL`（系统级记录）
- `enabled=1`，`deleted=0`
- 注释风格与 V15 保持一致（说明背景和变更原因）

## 目录结构

```
src/main/resources/db/
└── V16__add_jituan_background_placeholder.sql  # [NEW] 新增"集团背景情况"占位符迁移脚本
                                                 # 插入 placeholder_name=清单模板-集团背景情况-A1
                                                 # LONG_TEXT / list / sheet=集团背景情况 / cell=A1 / sort=360
                                                 # 使用 WHERE NOT EXISTS 防重复
```

## 关键代码结构

```sql
INSERT INTO `placeholder_registry`
    (`id`, `level`, `placeholder_name`, `display_name`, `ph_type`,
     `data_source`, `sheet_name`, `cell_address`, `title_keywords`, `column_defs`,
     `sort`, `enabled`, `deleted`)
SELECT UUID(), 'system', '清单模板-集团背景情况-A1', '集团背景情况', 'LONG_TEXT',
       'list', '集团背景情况', 'A1', NULL, NULL, 360, 1, 0
WHERE NOT EXISTS (
    SELECT 1 FROM `placeholder_registry`
    WHERE `placeholder_name` = '清单模板-集团背景情况-A1'
      AND `deleted` = 0
);
```