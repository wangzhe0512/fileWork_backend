---
name: add_related_company_row_template
overview: 将 `清单模板-2_关联公司信息` 占位符从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE，配置10列全量可选列+3列默认选中列，并新增对应的 Excel 数据提取逻辑和 Flyway 迁移。
todos:
  - id: reverse-engine
    content: 修改 ReverseTemplateEngine.java：将 清单模板-2_关联公司信息 条目移至 TABLE_ROW_TEMPLATE 区块并用9参数构造配置列信息
    status: completed
  - id: generate-engine
    content: 修改 ReportGenerateEngine.java：rowTemplateSheets 新增"2 关联公司信息"，新增 extractRelatedCompanyData 方法及路由分支
    status: completed
  - id: v20-migration
    content: 新建 V20 Flyway 迁移文件，UPDATE 关联公司信息占位符的类型和列配置
    status: completed
    dependencies:
      - reverse-engine
      - generate-engine
---

## 用户需求

将 `清单模板-2_关联公司信息` 占位符类型从 `TABLE_CLEAR_FULL` 改为 `TABLE_ROW_TEMPLATE`，使其支持动态行数表格的填充，保留原有 titleKeywords，并配置全量可选列与默认选中列。

## 产品概述

关联公司信息表（Sheet: `2 关联公司信息`）结构简单，行4（0-based）为表头行，行5起为数据行（行次列非空则为有效数据行）。升级为 `TABLE_ROW_TEMPLATE` 后，逆向时保留表头+1行列占位符模板行，生成时按字段名从 Excel 动态克隆填充数据行。

## 核心功能

- **全量可选列（11列）**：行次、关联方名称、关联方类型、国家（地区）、纳税人证件类型、证件号、关联关系类型、起始日期、截止日期、法定税率、是否享受税收优惠
- **默认选中列（3列）**：关联方名称、国家（地区）、关联关系类型
- 生成引擎新增 `extractRelatedCompanyData` 方法：从行5起扫描，行次列（col0）非空则取数据行，动态解析行4表头构建字段名→列索引映射，按 columnDefs 提取值
- V20 Flyway 迁移同步更新数据库记录

## 技术栈

与现有项目一致：Java Spring Boot + MyBatis-Plus + Flyway

## 实现方案

### 涉及文件与修改点

**1. `ReverseTemplateEngine.java`**

- 将第229-230行 `清单模板-2_关联公司信息` 条目从 `TABLE_CLEAR_FULL`（7参数）改为 `TABLE_ROW_TEMPLATE`（9参数）
- 新增 `sheetName = "2 关联公司信息"`
- `columnDefs`（默认选中3列）：`["关联方名称","国家（地区）","关联关系类型"]`
- `availableColDefs`（全量11列）：`["行次","关联方名称","关联方类型","国家（地区）","纳税人证件类型","证件号","关联关系类型","起始日期","截止日期","法定税率","是否享受税收优惠"]`
- **注意注册顺序**：需将该条目从 `TABLE_CLEAR_FULL` 区块移至 `TABLE_ROW_TEMPLATE` 区块（第237行之后），防止关键词被 `TABLE_CLEAR_FULL` 逻辑抢先匹配

**2. `ReportGenerateEngine.java`**

- 第84行 `rowTemplateSheets` 新增 `"2 关联公司信息"`
- `extractRowTemplateData` 方法中新增路由分支：`if ("2 关联公司信息".equals(sheetName))` → 调用 `extractRelatedCompanyData(rows)`
- 新增 `extractRelatedCompanyData` 方法：
- 读行4（0-based index=3）作为表头行，构建 `colIdx→字段名` Map
- 从行5（0-based index=4）起扫描数据行，col0（行次列）非空则为有效数据行
- 按 `colIdx→字段名` 映射提取所有列，输出 `List<Map<字段名, 值>>`

**3. `V20__upgrade_related_company_to_row_template.sql`（新建）**

```sql
UPDATE placeholder_registry
SET ph_type            = 'TABLE_ROW_TEMPLATE',
    sheet_name         = '2 关联公司信息',
    column_defs        = '["关联方名称","国家（地区）","关联关系类型"]',
    available_col_defs = '["行次","关联方名称","关联方类型","国家（地区）","纳税人证件类型","证件号","关联关系类型","起始日期","截止日期","法定税率","是否享受税收优惠"]'
WHERE placeholder_name = '清单模板-2_关联公司信息'
  AND level = 'system'
  AND deleted = 0;
```

## 实现注意事项

- **注册顺序**：`TABLE_ROW_TEMPLATE` 条目统一在 `TABLE_CLEAR_FULL` 之前注册（或确保在同区块按顺序排在其他关键词不冲突的位置），关联公司的 titleKeywords `["关联公司","关联方公司","关联企业"]` 与其他占位符无冲突，直接移至 TABLE_ROW_TEMPLATE 区块末尾即可
- **提取方法结构**：参照 `extractLaborServiceData` 的独立方法模式，新增 `extractRelatedCompanyData`；`extractRowTemplateData` 保持路由分发职责，不做逻辑堆砌
- **表头行索引**：从图中确认行4（Excel显示行5，0-based index=4）为表头，行5（Excel显示行6，0-based index=5）起为数据；注意 EasyExcel 从行0开始，需确认实际 `rows.get(4)` 对应表头
- **向后兼容**：不影响其他已有 TABLE_ROW_TEMPLATE 和 TABLE_CLEAR_FULL 占位符逻辑

## 目录结构

```
src/main/java/com/fileproc/report/service/
├── ReverseTemplateEngine.java       # [MODIFY] 将 清单模板-2_关联公司信息 条目迁移至 TABLE_ROW_TEMPLATE 区块，使用9参数构造
└── ReportGenerateEngine.java        # [MODIFY] rowTemplateSheets 新增"2 关联公司信息"；extractRowTemplateData 新增路由分支；新增 extractRelatedCompanyData 方法

src/main/resources/db/
└── V20__upgrade_related_company_to_row_template.sql  # [NEW] UPDATE 数据库中该占位符的 ph_type/sheet_name/column_defs/available_col_defs
```