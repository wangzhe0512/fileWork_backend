---
name: add_tested_related_transaction_placeholder
overview: 新增一条占位符注册表条目：从清单的"被测关联交易"Sheet 的 B1 读取单个值，display_name 为"被测关联交易利润水平"，通过新 Flyway 迁移文件 V17 写入数据库。
todos:
  - id: add-v17-migration
    content: 新增 V17 Flyway 迁移脚本，注册"被测关联交易利润水平"DATA_CELL 占位符（Sheet=被测关联交易，cell=B1，sort=520）
    status: completed
---

## 用户需求

在占位符注册表中新增一条针对清单 `被测关联交易` Sheet B1 单元格的 `DATA_CELL` 类型占位符，用于读取被测方的关联交易利润水平数值（如 `3.73%`）。

## 功能概述

- 新增占位符：`清单模板-被测关联交易利润水平-B1`
- 类型：`DATA_CELL`，精确读取清单文件 `被测关联交易` Sheet 的 B1 单元格值
- 通过 Flyway 迁移脚本 V17 写入数据库，幂等执行（含 `WHERE NOT EXISTS` 保护）

## 核心特性

- 按现有 `DATA_CELL` 占位符命名规范命名
- sort 值设为 520（当前最大值 510 + 10）
- 脚本头部含说明注释，风格与 V16 保持一致

## 技术栈

- Flyway SQL 迁移脚本（与现有 V9~V16 完全一致的规范）
- MySQL 语法

## 实现思路

新增一个 Flyway 迁移文件 `V17__add_tested_related_transaction_placeholder.sql`，使用 `INSERT ... SELECT ... WHERE NOT EXISTS` 模式写入一条 `DATA_CELL` 类型占位符，保持幂等性，避免重复执行报错。

## 实现要点

- **命名规范**：`placeholder_name = '清单模板-被测关联交易利润水平-B1'`，与 `清单模板-数据表-B1` 等现有条目命名格式一致
- **幂等保护**：使用 `WHERE NOT EXISTS (SELECT 1 FROM placeholder_registry WHERE placeholder_name = '...' AND deleted = 0)` 防重
- **sort 连续**：当前最大 sort = 510（`BVD数据模板-SummaryYear-MAX`），新条目使用 520
- **字段规范**：`title_keywords = NULL`，`column_defs = NULL`，`level = 'system'`，`enabled = 1`，`deleted = 0`

## 目录结构

```
src/main/resources/db/
└── V17__add_tested_related_transaction_placeholder.sql  # [NEW] 新增被测关联交易利润水平占位符迁移脚本
```