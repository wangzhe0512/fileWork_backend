---
name: fix-display-name-b1
overview: 将注册表中"清单模板-数据表-B1"条目的 display_name 从"企业全称"统一改为"企业名称"，与清单模板 Excel 文件中 A1 标签保持一致。
todos:
  - id: fix-v9-sql
    content: 修改 V9 SQL 第56行 display_name 从'企业全称'改为'企业名称'，并新建 V14 迁移脚本
    status: completed
---

## 用户需求

将后端占位符注册表中 `清单模板-数据表-B1` 对应的展示名称从"企业全称"统一改为"企业名称"，与清单模板 Excel 文件数据表 Sheet A1 标签的实际叫法保持一致。

## 变更范围

- `V9__placeholder_registry_and_schema.sql` 第56行：`display_name` 从 `'企业全称'` 改为 `'企业名称'`
- 新建 `V14__fix_b1_display_name.sql`：UPDATE 已部署数据库中该记录，与 V13 脚本风格保持一致

## 核心特征

- 仅涉及展示用字段 `display_name`，不影响取值逻辑、占位符名称、单元格地址等任何功能字段
- Java 代码无需修改

## 技术方案

### 变更说明

`display_name` 是纯展示字段，只在前端列表、日志等地方展示，不参与任何数据提取逻辑。本次变更仅修改两处 SQL，无代码风险。

### V9 SQL 修改

第56行 `'企业全称' AS display_name` 改为 `'企业名称' AS display_name`，用于全新部署时初始化正确值。

### V14 迁移脚本（新建）

参照 V13 脚本风格，UPDATE 已部署数据库中 `placeholder_name = '清单模板-数据表-B1'` 且 `level = 'system'` 的记录，将 `display_name` 改为 `'企业名称'`。

## 目录结构

```
src/main/resources/db/
├── V9__placeholder_registry_and_schema.sql   [MODIFY] 第56行 display_name 从'企业全称'改为'企业名称'
└── V14__fix_b1_display_name.sql              [NEW]    UPDATE 已部署记录的 display_name
```