---
name: reorder_placeholder_sort_values
overview: 新建 V37 SQL 迁移，按模板 Sheet 顺序批量更新所有占位符的 sort 值，实现前端列表按"类型分组 + Sheet 顺序"展示。
todos:
  - id: create-v37-sql
    content: 新建 V37__reorder_placeholder_sort.sql，批量 UPDATE 30 条占位符 sort 值
    status: completed
---

## 用户需求

按照已确认的 sort 值方案，将前端占位符列表的排序调整为：先按类型大组（DATA_CELL → LONG_TEXT → TABLE_ROW_TEMPLATE → TABLE_CLEAR_FULL → BVD），组内按清单/BVD 模板 Sheet 顺序展示。

## 核心功能

- 新建 Flyway 迁移脚本 `V37__reorder_placeholder_sort.sql`
- 批量 UPDATE `placeholder_registry` 表中共 **30 条**需调整 sort 的记录
- 仅修改数据库，无需改代码

## 技术栈

- Flyway SQL 迁移（现有模式，参照 V35/V36 写法）
- 每条 UPDATE 加 `level = 'system' AND deleted = 0` 精准匹配条件

## 实现思路

单文件 SQL 迁移，30 条 UPDATE 语句，按类型分组并加注释，遵循现有 V35/V36 格式规范。

## 目录结构

```
src/main/resources/db/
└── V37__reorder_placeholder_sort.sql  # [NEW] 批量重排占位符 sort 值
```