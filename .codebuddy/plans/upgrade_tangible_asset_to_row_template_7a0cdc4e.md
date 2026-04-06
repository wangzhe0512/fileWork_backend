---
name: upgrade_tangible_asset_to_row_template
overview: 将"清单模板-有形资产信息"从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE，绑定 Sheet "有形资产信息"，3列（资产净值、年初数、年末数），合计行标记 subtotal。
todos:
  - id: comment-v9-and-create-v28
    content: 注释 V9 第110行并新建 V28 迁移脚本，UPDATE 有形资产信息占位符为 TABLE_ROW_TEMPLATE，3列定义
    status: completed
  - id: update-reverse-engine
    content: 更新 ReverseTemplateEngine.java 第289行，将有形资产信息条目升级为 TABLE_ROW_TEMPLATE 并补全列定义
    status: completed
    dependencies:
      - comment-v9-and-create-v28
  - id: add-extract-method
    content: 在 ReportGenerateEngine.java 新增有形资产信息路由分支和 extractTangibleAssetData 提取方法
    status: completed
    dependencies:
      - update-reverse-engine
  - id: flyway-repair
    content: 执行 mvn flyway:repair 修复 V9 checksum，验证 V28 迁移成功
    status: completed
    dependencies:
      - add-extract-method
---

## 用户需求

将占位符 `清单模板-有形资产信息` 从 `TABLE_CLEAR_FULL` 升级为 `TABLE_ROW_TEMPLATE`，使其支持行模板提取逻辑。

## 产品概述

"有形资产信息" Sheet 结构简单固定：单行表头（3列）+ 8条资产类别数据行 + 1行合计，所有测试文件结构完全一致。升级后系统能按行提取该 Sheet 的数据，合计行标记为 `subtotal`，数据行标记为 `data`。

## 核心功能

- 注释 V9 SQL 第110行原始 INSERT，添加废弃说明（→V28）
- 新建 V28 Flyway 迁移脚本，将数据库中该占位符字段更新为 TABLE_ROW_TEMPLATE，绑定3列定义
- 更新 `ReverseTemplateEngine.java` 中该条目的类型和列定义
- 在 `ReportGenerateEngine.java` 中新增路由分支和 `extractTangibleAssetData` 提取方法

## 技术栈

- Java Spring Boot 后端（现有项目）
- Flyway 数据库版本迁移（V28 脚本）
- MyBatis-Plus ORM

## 实现方案

### 整体策略

完全复用 V26（资金融通）的单行表头升级模式：注释 V9 原始行 → 新建 V28 UPDATE 脚本 → 同步更新 ReverseTemplateEngine → 新增 ReportGenerateEngine 路由 + 提取方法。

### Sheet 数据结构

- 行0（表头）：`["资产净值", "年初数", "年末数"]`
- 行1~8（数据行）：8种资产类别（房屋及建筑物、电脑设备等）
- 行9（合计行）：第一列含"合计" → `_rowType=subtotal`

### extractTangibleAssetData 方法设计

与 `extractFundTransferData` 完全对称：

1. 行0动态解析表头，构建 `colIdx → 字段名` Map
2. 行1起逐行遍历，跳过全空行和"没找到"标记行
3. 第一列含"合计"→ `_rowType=subtotal`，否则 `data`

### 注意事项

- V28 脚本 WHERE 条件必须使用 `placeholder_name`（非 `name`），`AND ph_type='TABLE_CLEAR_FULL'` 防误更新
- 修改 V9 内容后需执行 `mvn flyway:repair` 修复 checksum（与 V26/V27 一致）
- `column_defs` 与 `available_col_defs` 保持一致，均为3列

## 目录结构

```
src/main/resources/db/
├── V9__placeholder_registry_and_schema.sql   # [MODIFY] 第110行：注释废弃，加 →V28 说明
└── V28__upgrade_tangible_asset_to_row_template.sql  # [NEW] UPDATE 脚本

src/main/java/com/fileproc/report/service/
├── ReverseTemplateEngine.java   # [MODIFY] 第289行：TABLE_CLEAR_FULL → TABLE_ROW_TEMPLATE，补全 sourceSheet 和 columnDefs
└── ReportGenerateEngine.java    # [MODIFY] 第401行后新增路由分支；文件末尾新增 extractTangibleAssetData 方法
```