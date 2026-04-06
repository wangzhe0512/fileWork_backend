---
name: upgrade-relation-change-to-row-template
overview: 将"清单模板-关联关系变化情况"占位符从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE，涉及 SQL 迁移（V22）、ReverseTemplateEngine 注册表项更新、ReportGenerateEngine 新增数据提取分支三处改动，完全对标上次 V21 关联方个人信息的升级模式。
todos:
  - id: sql-v22
    content: 新建 V22__upgrade_relation_change_to_row_template.sql，将关联关系变化情况升级为 TABLE_ROW_TEMPLATE
    status: completed
  - id: reverse-engine
    content: 修改 ReverseTemplateEngine.java，将注册条目移入 TABLE_ROW_TEMPLATE 分组并补充 sheetName 和 columnDefs
    status: completed
  - id: report-engine
    content: 在 ReportGenerateEngine.java 中新增 extractRelationChangeData 方法及 extractRowTemplateData 路由分支
    status: completed
---

## 用户需求

将"清单模板-关联关系变化情况"占位符从 `TABLE_CLEAR_FULL` 升级为 `TABLE_ROW_TEMPLATE`，支持动态行数的报告生成。

## 产品概述

关联关系变化情况表（Sheet：关联关系变化情况）是清单模板中的极简结构表：行0为表头（关联方名称 | 国家/地区 | 关联关系类型 | 起止日期 | 变化原因），行1起为数据行，col0（关联方名称）非空则为有效数据行。与已完成的 V21 关联方个人信息升级结构完全一致。

## 核心功能

- **SQL 迁移脚本（V22）**：将数据库中该占位符的 `ph_type` 改为 `TABLE_ROW_TEMPLATE`，写入 `sheet_name`、`column_defs`（5列）和 `available_col_defs`（5列）
- **ReverseTemplateEngine 更新**：将注册条目从 `TABLE_CLEAR_FULL` 区移入 `TABLE_ROW_TEMPLATE` 分组，补充 sheetName 和完整列定义
- **ReportGenerateEngine 新增分支**：在 `extractRowTemplateData` 中新增路由 + 新增 `extractRelationChangeData` 私有方法（行0表头，行1+col0非空为有效行）

## 技术栈

与现有项目完全一致：Java（Spring Boot）+ MyBatis-Plus + Flyway 数据库迁移 + EasyExcel。

## 实现方案

严格对标 V21（关联方个人信息）已验证路径，三处改动相互独立：

1. **V22 SQL 迁移脚本**：UPDATE `placeholder_registry`，补全 ph_type / sheet_name / column_defs / available_col_defs，WHERE 条件加 `level='system' AND deleted=0`。

2. **ReverseTemplateEngine**：将第229-230行的 TABLE_CLEAR_FULL 条目删除，在 TABLE_ROW_TEMPLATE 分组（第233行注释之后、关联方个人信息之前或之后）新增带 sheetName + columnDefs + availableColDefs 的完整条目。

3. **ReportGenerateEngine**：在"关联方个人信息"路由分支之后新增"关联关系变化情况"路由，同时在 `extractRelatedPersonData` 方法之后新增 `extractRelationChangeData` 私有方法（逻辑完全复用同一模式）。

## 实现注意事项

- sheetName 字符串必须精确为 `"关联关系变化情况"`（无数字前缀、无空格），SQL 与 Java 保持严格一致
- 注册条目移入 TABLE_ROW_TEMPLATE 分组注释块，避免残留在 TABLE_CLEAR_FULL 区
- `extractRelationChangeData` 最低行数校验：`rows.size() < 2` 返回空列表 + warn 日志
- SQL WHERE 必须包含 `AND level = 'system' AND deleted = 0`

## 目录结构

```
src/main/resources/db/
└── V22__upgrade_relation_change_to_row_template.sql  # [NEW] Flyway 迁移脚本

src/main/java/com/fileproc/report/service/
├── ReverseTemplateEngine.java   # [MODIFY] 第229-230行：移除 TABLE_CLEAR_FULL 条目，在 TABLE_ROW_TEMPLATE 分组新增完整条目
└── ReportGenerateEngine.java    # [MODIFY] extractRowTemplateData 新增路由 + 新增 extractRelationChangeData 方法
```