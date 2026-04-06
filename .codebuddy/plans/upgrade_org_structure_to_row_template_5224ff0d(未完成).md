---
name: upgrade_org_structure_to_row_template
overview: 将"1 组织结构及管理架构"占位符从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE，同步修改 ReverseTemplateEngine.java 注册表条目和新建 V18 Flyway 迁移脚本。
todos:
  - id: modify-java-registry
    content: 修改 ReverseTemplateEngine.java 第202-203行，将组织结构条目改为 TABLE_ROW_TEMPLATE，补充 sheetName 和 columnDefs
    status: pending
  - id: create-v18-migration
    content: 新建 V18 Flyway 迁移文件，UPDATE 数据库组织结构占位符的 ph_type、sheet_name、column_defs
    status: pending
    dependencies:
      - modify-java-registry
---

## 用户需求

将占位符 `清单模板-1_组织结构及管理架构` 的类型从 `TABLE_CLEAR_FULL` 升级为 `TABLE_ROW_TEMPLATE`，实现组织结构表格按 Excel 实际行数动态克隆填充，替代原来的整表清空+手动填写模式。

## 产品概述

组织结构及管理架构表的部门行数因客户不同而动态变化，`TABLE_ROW_TEMPLATE` 可在逆向时保留表头 + 1 行列占位符模板，生成时按 Excel 实际数据行数自动克隆展开，无需人工手动补填。

## 核心功能

- 修改 Java 注册表条目：类型改为 `TABLE_ROW_TEMPLATE`，补充 `sheetName`（`1 组织结构及管理架构`）和 5 列 `columnDefs`（主要部门、人数、主要职责范围、汇报对象、汇报对象主要办公所在）
- 新建 V18 Flyway 迁移文件，对数据库已存在的条目执行 UPDATE，同步更新 `ph_type`、`sheet_name`、`column_defs` 三个字段

## 技术栈

- 语言：Java（Spring Boot）
- 数据库迁移：Flyway SQL 迁移脚本
- 修改范围：`ReverseTemplateEngine.java` 静态注册表 + 新建 V18 SQL 迁移文件

## 实现方案

**两处同步修改，保持 Java 代码与数据库注册表一致：**

1. **Java 注册表**（`ReverseTemplateEngine.java` 第 202-203 行）：将 7 参数构造改为 8 参数构造，类型改为 `TABLE_ROW_TEMPLATE`，补充 `sheetName` 和 `columnDefs`。同时将该条目从 `TABLE_CLEAR_FULL` 注释块移至下方 `TABLE_ROW_TEMPLATE` 注释块，保持代码归类清晰。

2. **数据库迁移**（新建 `V18__upgrade_org_structure_to_row_template.sql`）：对已存在的条目执行 `UPDATE`，不新增记录，幂等安全。

## 实现注意事项

- `columnDefs` JSON 数组列顺序必须严格对应 Word 表格的列索引（0-4），与 Excel Sheet 列 A-E 一一对应
- `TABLE_ROW_TEMPLATE` 条目注册顺序不影响关键词匹配优先级（无与其他条目冲突的关键词），无需调整注册位置
- V18 UPDATE 使用 `WHERE placeholder_name = '清单模板-1_组织结构及管理架构'` 精确定位，不影响其他条目

## 目录结构

```
src/
├── main/
│   ├── java/com/fileproc/report/service/
│   │   └── ReverseTemplateEngine.java          # [MODIFY] 第202-203行：修改注册表条目类型及参数
│   └── resources/db/
│       └── V18__upgrade_org_structure_to_row_template.sql  # [NEW] Flyway 迁移：UPDATE 数据库中该占位符的 ph_type/sheet_name/column_defs
```