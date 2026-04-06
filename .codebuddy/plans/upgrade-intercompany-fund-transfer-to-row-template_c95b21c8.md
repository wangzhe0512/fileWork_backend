---
name: upgrade-intercompany-fund-transfer-to-row-template
overview: 将 `清单模板-公司间资金融通` 从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE，绑定 Sheet `公司间资金融通交易总结`，处理双行表头合并逻辑。
todos:
  - id: sql-v9-comment
    content: 注释 V9 SQL 第114行公司间资金融通 INSERT，加废弃说明（→V27升级）
    status: completed
  - id: sql-v27-new
    content: 新建 V27__upgrade_inter_company_fund_to_row_template.sql，UPDATE ph_type/sheet_name/column_defs，WHERE 用 placeholder_name
    status: completed
    dependencies:
      - sql-v9-comment
  - id: update-reverse-java
    content: 更新 ReverseTemplateEngine.java：升级公司间资金融通为TABLE_ROW_TEMPLATE，绑定Sheet，补充9列column_defs
    status: completed
  - id: add-extract-logic
    content: ReportGenerateEngine.java 新增公司间资金融通路由分支及extractInterCompanyFundData方法（双行表头合并+没找到行跳过）
    status: completed
    dependencies:
      - update-reverse-java
---

## 用户需求

将 `清单模板-公司间资金融通` 从 `TABLE_CLEAR_FULL` 升级为 `TABLE_ROW_TEMPLATE`，绑定 Sheet `公司间资金融通交易总结`，支持双行表头合并、按行提取数据。

## 产品概述

与已完成的 `清单模板-资金融通` 升级相同模式，将公司间资金融通占位符从"清空填充"升级为"行模板"，使报告生成引擎能按 Sheet 数据逐行写入 Word 表格。

## 核心功能

- V9 SQL 第114行废弃注释，新建 V27 迁移脚本执行 UPDATE
- `ReverseTemplateEngine.java` 注册条目升级为 TABLE_ROW_TEMPLATE，绑定 Sheet 名及9列列定义
- `ReportGenerateEngine.java` 新增 `公司间资金融通交易总结` 路由分支及 `extractInterCompanyFundData` 提取方法（双行表头合并、"没找到"行跳过、全空行跳过）

## 技术栈

Java Spring Boot + MyBatis + Flyway（现有项目，全部沿用现有模式）

## 实现方案

沿用 `extractLaborCostData`（单行表头）和供应商/客户（双行表头）的现有模式，新增 `extractInterCompanyFundData`：

- **双行表头合并**：行0为主表头，行1为副表头；若行1同列非空则拼接（"本金"+"（原币）"→"本金（原币）"），为空则直接用行0值
- **数据行**：行2起，全空跳过，第一列含"没找到"跳过，其余输出 `_rowType=data`（无合计行）
- **迁移 SQL**：WHERE 条件用 `placeholder_name`（已验证正确列名，V26已踩坑修正）

## Sheet 结构（4个测试文件一致）

| 行 | 内容 |
| --- | --- |
| 行0 | 主表头：缔约方\ | 公司间资金融通交易性质\ | 货币\ | 本金\ | 本金\ | 到期期限\ | 利率\ | 利息收入/利息支出\ | 利息收入/利息支出 |
| 行1 | 副表头：null\ | null\ | null\ | （原币）\ | （人民币）\ | null\ | null\ | （原币）\ | （人民币） |
| 行2起 | 数据行；末尾可能有"没找到"行 |


合并后9列：`["缔约方","公司间资金融通交易性质","货币","本金（原币）","本金（人民币）","到期期限","利率","利息收入/利息支出（原币）","利息收入/利息支出（人民币）"]`

## 目录结构

```
src/main/resources/db/
├── V9__placeholder_registry_and_schema.sql              [MODIFY] 第114行加废弃注释（→V27）
└── V27__upgrade_inter_company_fund_to_row_template.sql  [NEW] UPDATE公司间资金融通

src/main/java/com/fileproc/report/service/
├── ReverseTemplateEngine.java   [MODIFY] 第297-298行升级为TABLE_ROW_TEMPLATE+9列column_defs
└── ReportGenerateEngine.java    [MODIFY] 新增路由分支+extractInterCompanyFundData方法
```