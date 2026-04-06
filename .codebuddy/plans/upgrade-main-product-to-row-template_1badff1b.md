---
name: upgrade-main-product-to-row-template
overview: 将 `清单模板-主要产品` 从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE（V30），涉及 SQL 迁移脚本、ReverseTemplateEngine 静态注册表、ReportGenerateEngine 提取方法三处改动。
todos:
  - id: sql-v30
    content: 新建 V30__upgrade_main_product_to_row_template.sql，UPDATE 注册表 ph_type/sheet_name/column_defs/available_col_defs
    status: completed
  - id: reverse-engine
    content: 修改 ReverseTemplateEngine.java 第227行，将清单模板-主要产品升级为 TABLE_ROW_TEMPLATE 并补全参数
    status: completed
    dependencies:
      - sql-v30
  - id: report-engine
    content: 在 ReportGenerateEngine.java 新增主要产品专用分支和 extractMainProductData 方法
    status: completed
    dependencies:
      - reverse-engine
---

## 用户需求

将 `清单模板-主要产品` 占位符从 `TABLE_CLEAR_FULL` 升级为 `TABLE_ROW_TEMPLATE`，使其支持动态行数提取与行模板克隆填充。

## 产品概述

`主要产品` Sheet 结构规则、列固定（3列）、行数随客户不同而变化，与已升级的有形资产信息、功能风险汇总表等完全一致，具备升级条件。升级后系统可按行模板精确填充产品列表，并正确处理合计行（标记为 subtotal）。

## 核心功能

- **SQL迁移脚本**（V30）：将注册表中该占位符的 `ph_type` 改为 `TABLE_ROW_TEMPLATE`，补充 `sheet_name`、`column_defs`、`available_col_defs`
- **ReverseTemplateEngine 同步更新**：将内存注册表中的该条目类型与参数同步修改，与数据库保持一致
- **ReportGenerateEngine 新增提取分支**：实现 `extractMainProductData()` 方法，处理单行表头 + 数据行 + 合计行（subtotal）逻辑

## 技术栈

- Java（Spring Boot）后端，已有 Flyway 数据库迁移机制（V9~V29 升级链）
- `ReportGenerateEngine.java`：负责从 Excel Sheet 提取行数据
- `ReverseTemplateEngine.java`：内存注册表，与数据库同步
- EasyExcel 行数据格式：`List<Map<Integer, Object>>`

## 实现方案

### 总体策略

遵循 V28（有形资产）和 V29（功能风险汇总表）的完全相同升级模式：

1. 新增 Flyway SQL 脚本 UPDATE 注册表记录
2. 修改 `ReverseTemplateEngine.java` 内存注册表同步
3. 在 `ReportGenerateEngine.extractRowTemplateData()` 专用分支区域新增 `主要产品` 分支，调用新提取方法

### extractMainProductData 实现规则

- **行0**：单行表头，解析 `colIdx → 字段名` Map
- **行1起**：逐行扫描，全空行跳过，"没找到"标记行跳过
- **合计行识别**：首列含"总计"/"合计"/"小计" → `_rowType=subtotal`
- **数据行**：其余非空行 → `_rowType=data`
- 与 `extractTangibleAssetData` 逻辑几乎一致，仅日志标签不同

## 执行细节

- `ReverseTemplateEngine.java` 第227行：将 `TABLE_CLEAR_FULL` 改为 `TABLE_ROW_TEMPLATE`，补充 `sheet_name="主要产品"`、`columnDefs` 和 `availableColDefs` 参数，并加注释说明 V30 迁移
- `ReportGenerateEngine.java` 在第411行（`功能风险汇总表` 分支之后）插入 `主要产品` 专用路径，保持现有分支顺序规范
- SQL 脚本使用 `WHERE ph_type = 'TABLE_CLEAR_FULL'` 双重保护，防止重复执行

## 目录结构

```
src/
├── main/
│   ├── resources/db/
│   │   └── V30__upgrade_main_product_to_row_template.sql   # [NEW] Flyway 迁移脚本
│   └── java/com/fileproc/
│       └── report/service/
│           ├── ReverseTemplateEngine.java                   # [MODIFY] 第227行：同步内存注册表类型和参数
│           └── ReportGenerateEngine.java                    # [MODIFY] 新增主要产品专用分支+extractMainProductData方法
```