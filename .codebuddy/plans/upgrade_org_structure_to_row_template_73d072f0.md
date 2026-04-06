---
name: upgrade_org_structure_to_row_template
overview: 将"1 组织结构及管理架构"占位符从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE，同步修改 Java 注册表、新建 V18 迁移脚本，并修复 getColumnDefItems 的 knownCols 硬编码问题（改为从注册表 column_defs 动态读取）。
todos:
  - id: modify-java-registry
    content: 修改 ReverseTemplateEngine.java：组织结构条目改为 TABLE_ROW_TEMPLATE，补充 sheetName 和 columnDefs，移至正确注释块
    status: completed
  - id: fix-column-def-items
    content: 修复 PlaceholderRegistryService.getColumnDefItems：删除 knownCols 硬编码，改为从系统级 column_defs 动态构建返回列表
    status: completed
  - id: create-v18-migration
    content: 新建 V18 Flyway 迁移文件，UPDATE 数据库组织结构占位符的 ph_type、sheet_name、column_defs
    status: completed
    dependencies:
      - modify-java-registry
      - fix-column-def-items
---

## 用户需求

将 `清单模板-1_组织结构及管理架构` 占位符从 `TABLE_CLEAR_FULL` 升级为 `TABLE_ROW_TEMPLATE`，支持行数动态的部门表格自动克隆填充；同步修复 `PlaceholderRegistryService.getColumnDefItems` 中 `knownCols` 硬编码 BVD 12列的问题，使该接口对所有 `TABLE_ROW_TEMPLATE` 占位符通用。

## 产品概述

- 组织结构表部门行数因客户不同而动态变化，改用 `TABLE_ROW_TEMPLATE` 后，逆向时自动保留表头+1行列模板，生成时按 Excel 实际数据行数克隆展开，不再需要手动补填
- `getColumnDefItems` 修复后，前端列选择器数据源从系统级注册表条目的 `column_defs` 动态读取，适用于任意 `TABLE_ROW_TEMPLATE` 占位符，不再局限于 BVD 12列

## 核心功能

- 修改 `ReverseTemplateEngine.java` 注册表：组织结构条目类型改为 `TABLE_ROW_TEMPLATE`，补充 `sheetName` 和 5列 `columnDefs`
- 新建 `V18` Flyway 迁移文件，UPDATE 数据库对应条目的 `ph_type`、`sheet_name`、`column_defs`
- 修复 `PlaceholderRegistryService.getColumnDefItems`：删除硬编码 `knownCols`，改为从系统级条目的 `column_defs` 动态构建返回列表

## 技术栈

- Java / Spring Boot（现有项目）
- Flyway SQL 迁移（现有迁移机制）
- MyBatis-Plus（现有 ORM）

## 实现方案

### 修改1：ReverseTemplateEngine.java

将第 202-203 行从 7 参数 `TABLE_CLEAR_FULL` 构造改为 8 参数 `TABLE_ROW_TEMPLATE` 构造，同时将该条目从 `TABLE_CLEAR_FULL` 注释块移至 `TABLE_ROW_TEMPLATE` 注释块（供应商明细之前），保持代码分类清晰：

```java
// TABLE_ROW_TEMPLATE：动态行数表
reg.add(new RegistryEntry("清单模板-1_组织结构及管理架构", "组织结构",
        PlaceholderType.TABLE_ROW_TEMPLATE, "list", "1 组织结构及管理架构", null,
        List.of("组织结构", "部门结构", "管理架构", "组织架构"),
        List.of("主要部门", "人数", "主要职责范围", "汇报对象", "汇报对象主要办公所在")));
```

### 修改2：PlaceholderRegistryService.java（第 333-360 行）

删除 `knownCols` 硬编码数组，改为从系统级条目 `column_defs` 动态构建：

- `fieldKey = label = 字段名`（因为中文列名本身就是显示名）
- `colIndex = 数组下标`
- `selected = effectiveColDefs.contains(fieldKey)`
- 若系统级 `column_defs` 为空，返回空列表（兜底）

这样对任意 `TABLE_ROW_TEMPLATE` 占位符（无论中英文列名）都能正确返回可选列，完全通用。

### 修改3：V18 Flyway 迁移

新建 `V18__upgrade_org_structure_to_row_template.sql`，执行带 `WHERE` 精确定位的 UPDATE，幂等安全：

```sql
UPDATE placeholder_registry
SET ph_type     = 'TABLE_ROW_TEMPLATE',
    sheet_name  = '1 组织结构及管理架构',
    column_defs = '["主要部门","人数","主要职责范围","汇报对象","汇报对象主要办公所在"]'
WHERE placeholder_name = '清单模板-1_组织结构及管理架构'
  AND level = 'system';
```

## 注意事项

- `getColumnDefItems` 修复后，BVD SummaryYear 条目的 `column_defs` 数据库字段已存有完整12列 JSON，无需任何额外改动即可正确返回
- `knownCols` 中 BVD 字段的 `label` 与 `fieldKey` 不同（如 `FY2023_STATUS` vs `FY2023 Status`）——删除后统一用 `fieldKey` 作为 `label`，对前端展示有轻微影响（列名从 `FY2023 Status` 变为 `FY2023_STATUS`）。如需保留原 label，可在注册表 `column_defs` 中扩展支持 map 格式，但当前需求范围内不做此扩展
- 注册表条目位置调整（从 `TABLE_CLEAR_FULL` 块移至 `TABLE_ROW_TEMPLATE` 块）仅影响代码可读性，不影响运行时行为

## 目录结构

```
src/
├── main/
│   ├── java/com/fileproc/
│   │   ├── report/service/
│   │   │   └── ReverseTemplateEngine.java             # [MODIFY] 第202-203行：组织结构条目改为 TABLE_ROW_TEMPLATE，8参数构造，补充 sheetName 和 columnDefs，移至 TABLE_ROW_TEMPLATE 注释块
│   │   └── registry/service/
│   │       └── PlaceholderRegistryService.java        # [MODIFY] 第333-360行：删除 knownCols 硬编码，改为从 system.getColumnDefs() 动态构建 ColumnDefItem 列表
│   └── resources/db/
│       └── V18__upgrade_org_structure_to_row_template.sql  # [NEW] Flyway UPDATE 迁移：更新 ph_type/sheet_name/column_defs，WHERE 精确定位 system 级条目
```