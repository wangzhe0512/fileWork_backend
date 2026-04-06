---
name: fix-row-template-precise
overview: 精准修复 TABLE_ROW_TEMPLATE 端到端问题：修正 ReportGenerateEngine 的子表头行索引、数据扫描起始行，以及 ReverseTemplateEngine 注册表中的 columnDefs 字段名与 Word 表格列的对应关系。
todos:
  - id: fix-report-engine-header-and-start
    content: 修复 ReportGenerateEngine.java：合并行4+行5构建 colNameMap，数据扫描起始行改为 i=8
    status: completed
  - id: fix-reverse-engine-empty-cell-write
    content: 修复 ReverseTemplateEngine.java writeRowMarkers：空单元格无 Run 时新建 Run 写入文本
    status: completed
---

## 用户需求

修复 TABLE_ROW_TEMPLATE 流程的3个剩余 Bug，目标是使逆向生成的子模板中 data 行能正确写入 `{{_tpl_...}} {{_row_data}} {{_col_字段名}}`，以及报告生成时能从 Excel 正确读取分组/明细/小计数据行。

## 产品概述

当前生成的子模板存在以下可见问题：

1. data 行第0列为空（`{{_tpl_}}` 和 `{{_row_data}}` 没有写进去）
2. 报告生成引擎从 Excel 提取数据时找不到"名称"列，兜底 col2，同时数据扫描起始行多包含了一行大类标题

## 核心功能

- **Bug1（ReportGenerateEngine）**：子表头行合并读取——同时读取 Excel 行5（含"名称"字段）和行6（含"金额/比例"字段）来构建列名映射，确保三个关键列（名称/金额/占比）都能被正确定位
- **Bug2（ReportGenerateEngine）**：数据扫描起始行从 `i=7`（Excel 行8，为"关联供应商"大类标题行）改为 `i=8`（Excel 行9，第一条真实数据行），避免大类标题被误判为 data 行
- **Bug3（ReverseTemplateEngine）**：在 `writeRowMarkers` lambda 中，当目标单元格没有任何 Run（空单元格，如 data 行 col0）时，自动通过 `para.createRun()` 新建 Run 并写入文本，确保 `{{_tpl_}}+{{_row_data}}+{{_col_字段名}}` 能写入原本空白的单元格

## Tech Stack

现有项目：Java + Spring Boot + Apache POI（操作 Word .docx）+ EasyExcel（读取 Excel），纯逻辑修改，无需引入新依赖。

## 实现思路

三处修改均为最小侵入式改动，精准针对已定位的代码行，不改变方法签名和调用链。

### Bug1 修复策略（ReportGenerateEngine L325~L353）

将单行读取改为**双行合并**：先遍历 `rows.get(4)`（Excel 行5）构建 colNameMap（可获得"名称"列），再遍历 `rows.get(5)`（Excel 行6）补充尚未加入的列（可获得"金额"/"比例"列）。已有列不覆盖，确保行5优先、行6补充。同时将扫描行数不足判断从 `rows.size() < 7` 改为 `rows.size() < 8`（要扫描到至少 i=8）。

### Bug2 修复策略（ReportGenerateEngine L364）

单行改动：`for (int i = 7;` → `for (int i = 8;`

### Bug3 修复策略（ReverseTemplateEngine L1782~L1790）

在现有 `if (!cellRuns.isEmpty())` 块之后，增加 `else if (!wt[0].isEmpty())` 分支：当无 Run 且 writeText 非空时，调用 `para.createRun().setText(wt[0])` 新建 Run 并写入，之后将 `wt[0]` 置空。仅处理第一个段落（后续段落 `wt[0]` 已为空，跳过）。

## 实现细节

- **Bug3 新建 Run**：`XWPFRun newRun = para.createRun(); newRun.setText(wt[0]); wt[0] = "";` — 不需要复制格式（占位符文本样式无特殊要求，与原有 group/subtotal 行的写入方式一致）
- **Bug1 双行合并**：遍历行5时 putIfAbsent 逻辑保证行5优先；行6用相同方式补充，已有 key 不覆盖
- **向后兼容**：Bug2 改 i=8 后，若某些 Excel 格式行8并非大类标题，因为 group 行判断（col0非空 && col1空 && colName空 → group）可以正确吸收，不影响最终结果

## 目录结构

```
src/main/java/com/fileproc/report/service/
├── ReportGenerateEngine.java   # [MODIFY] extractRowTemplateData 方法：
│                               #   L320 rows.size() < 7 → < 9（保证行8存在）
│                               #   L325~L333 单行 rows.get(5) → 合并 rows.get(4)+rows.get(5)
│                               #   L364 for (int i = 7; → for (int i = 8;
└── ReverseTemplateEngine.java  # [MODIFY] writeRowMarkers lambda L1784~L1790：
                                #   当 cellRuns.isEmpty() 且 wt[0] 非空时，
                                #   para.createRun().setText(wt[0])，wt[0]=""
```