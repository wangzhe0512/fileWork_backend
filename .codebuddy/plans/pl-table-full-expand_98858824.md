---
name: pl-table-full-expand
overview: 将 PL 财务状况表从 TABLE_CLEAR（只清数字列）改为 TABLE_CLEAR_FULL（整表展开），子模板生成时整表清空只留一行占位符，生成报告时从 PL Sheet 逐行展开三列数据（保留 Word 原有表头行）。同时一并实现 TABLE_CLEAR_FULL 清空后删除多余空行。
todos:
  - id: modify-registry-pl-entries
    content: 修改 ReverseTemplateEngine.java 注册表：删除 PL-12行以上冗余条目，将 清单模板-PL 和 清单模板-PL含特殊因素调整 改为 TABLE_CLEAR_FULL 策略，填写 sheetName，精确更新 titleKeywords
    status: completed
  - id: modify-extract-table-data-pl
    content: 修改 ReportGenerateEngine.java extractTableData()：新增 PL Sheet 专用截取逻辑，仅取 rows[3..11] 并前置虚拟表头行，实现表头行保留 + 数据行展开
    status: completed
    dependencies:
      - modify-registry-pl-entries
  - id: rebuild-and-verify
    content: 重新打包（mvn package）并验证 spx 测试用例，确认子模板 PL 财务表只保留1行占位符，报告生成后整表内容与 PL Sheet 一致
    status: completed
    dependencies:
      - modify-extract-table-data-pl
---

## 用户需求

财务状况表（对应清单模板 PL Sheet / PL含特殊因素调整 Sheet）的处理策略改为"整表展开"：

1. **反向引擎（子模板生成）**：`清单模板-PL` 和 `清单模板-PL含特殊因素调整` 两条注册表条目由 `TABLE_CLEAR` 改为 `TABLE_CLEAR_FULL`，Word 历史报告中对应表格整表清空，仅保留第一行并写入占位符 `{{清单模板-PL}}`，多余行全部删除。同步修正 titleKeywords 使两条条目能精确匹配各自的 Word 表格，不会 fallback 错乱。

2. **生成引擎（报告生成）**：针对 `清单模板-PL` / `清单模板-PL含特殊因素调整` 占位符，从 PL Sheet 只提取第4~12行（跳过空行、表头行和第13行以后的关联销售明细表），以 Word 表格原有表头（`金额（人民币元）`）为第0行，后接 PL 数据行逐行展开填入 Word 表格的三列（项目/公式/金额）。

## 产品概述

报告生成系统中，财务状况表从原来的"仅清空数字列"模式升级为"整表数据展开"模式：子模板生成时整表清空只留占位符行，报告生成时按 PL Sheet 真实数据逐行重建整张表，保证项目名称、公式、金额三列数据与清单模板完全一致。

## 核心功能

- 注册表中 PL 类条目策略改为 `TABLE_CLEAR_FULL`，sheetName 填写 `PL` / `PL含特殊因素调整`，titleKeywords 精确区分两张表
- 生成引擎对 PL 类占位符特殊处理：`extractTableData` 增加 `startRow`/`endRow` 参数支持，PL Sheet 取 rows[3..11]（共9行数据），行0放置虚拟表头供 `fillTableWithData` 跳过
- Word 表格的原有表头行（第0行，含"金额（人民币元）"）保留不动，数据从第1行起按 PL 数据填入

## 技术栈

- Java 17 + Spring Boot（已有）
- Apache POI 5.2.5（已有，`XWPFDocument`/`XWPFTable`/`XWPFTableRow`）
- EasyExcel（已有，用于 `readSheet`）
- 修改文件：`ReverseTemplateEngine.java`、`ReportGenerateEngine.java`

## 实现方案

### 总体策略

分两端改造：反向引擎负责生成正确的子模板，生成引擎负责按正确数据行填充报告。两端通过占位符名称（`清单模板-PL` / `清单模板-PL含特殊因素调整`）耦合，无需引入新字段或数据库变更。

`Placeholder` 实体已有 `sourceSheet`、`sourceField` 字段，`extractTableData` 接收 `sheetName` 参数，可在其内部按 sheetName 做特殊分支处理，无需改接口签名。

### 关键技术决策

**PL 数据行截取**：PL Sheet 结构固定（行0空行、行1表头、行2空行、行3~11数据、行12+关联销售），在 `extractTableData` 内增加对 sheetName 为 `PL` 或 `PL含特殊因素调整` 的判断，直接取 `rows.subList(3, Math.min(12, rows.size()))` 作为数据行，前置一个空虚拟表头行（索引0，供 `fillTableWithData` 的 `startDataRow=1` 跳过），整体传出。

**Word 表头保留**：`fillTableWithData` 的 `startDataRow=1` 已跳过 data 第0行，从表格第1行（index=1）写起，index=0 的表格行（原有表头"金额（人民币元）"）天然保留，无需额外改动 `fillTableWithData`。

**TABLE_CLEAR_FULL 多余行删除**：代码第1638-1644行已实现 `targetTable.removeRow(ri)` 倒序删除，当前计划中此逻辑已就位，只需将 PL 条目改为 `TABLE_CLEAR_FULL` 即可生效。

### 性能与稳定性

- `extractTableData` 的 PL 特殊分支是 O(n) 切片，无额外 IO
- `excelDataCache` 按 `"type:sheetName"` 缓存，PL 数据只读一次
- `fillTableWithData` 复用已有行，超出时 `createRow()`，行数差异不会越界

## 实现说明

- `extractTableData` 新增 PL 分支：`if ("PL".equals(sheetName) || "PL含特殊因素调整".equals(sheetName))` → 取 rows[3..11]，前置空列表作为虚拟表头行（index 0），共返回10行（1虚拟表头+9数据）
- 注册表中 `清单模板-PL-12行以上的表格内容` 这条冗余条目**可删除**（与 `清单模板-PL` 功能重复，且 titleKeywords 相同），减少 fallback 歧义
- `清单模板-PL` 的 titleKeywords 改为精确匹配财务状况表关键词：`List.of("财务状况", "营业收入", "成本加成率", "毛利率")`；`清单模板-PL含特殊因素调整` 保持 `List.of("含特殊", "特殊因素调整")`
- 两条 PL 条目的 `sheetName` 从 `null` 改为实际 Sheet 名（`"PL"` / `"PL含特殊因素调整"`），使生成引擎可通过 `ph.getSourceSheet()` 识别并做特殊提取

## 架构设计

```mermaid
graph LR
    A[历史报告 Word] -->|clearTableBlock TABLE_CLEAR_FULL| B[子模板: 表头行保留 + 第1行写{{清单模板-PL}}]
    C[清单模板 PL Sheet 行4~12] -->|extractTableData PL特殊截取| D[data: 虚拟表头行 + 9行数据]
    B -->|fillTableWithData startDataRow=1| E[生成报告: 表头原样保留 + 9行PL数据展开]
    D --> E
```

## 目录结构

```
src/main/java/com/fileproc/report/service/
├── ReverseTemplateEngine.java  # [MODIFY]
│   └── 注册表第163~168行：
│       - 删除 清单模板-PL-12行以上的表格内容 冗余条目
│       - 清单模板-PL：策略改 TABLE_CLEAR_FULL，sheetName="PL"，更新 titleKeywords
│       - 清单模板-PL含特殊因素调整：策略改 TABLE_CLEAR_FULL，sheetName="PL含特殊因素调整"，收紧 titleKeywords
└── ReportGenerateEngine.java   # [MODIFY]
    └── extractTableData()：新增 PL Sheet 专用截取分支，取 rows[3..11]，前置虚拟表头行
```