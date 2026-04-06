---
name: row-template-multi-row-type
overview: 改造行模板克隆方案，支持多种行类型（分组标题行/明细数据行/小计合计行）保留原表格结构并按行类型克隆填充
todos:
  - id: add-rowtype-to-extract
    content: 在 ReportGenerateEngine.buildRowMap 各分支新增 _rowType 字段（group/data/subtotal）
    status: completed
  - id: multi-tpl-row-fill
    content: 改造 fillTableByRowTemplateMapped，收集多种模板行并按 _rowType 路由克隆
    status: completed
    dependencies:
      - add-rowtype-to-extract
  - id: reverse-multi-row-tpl
    content: 改造 ReverseTemplateEngine TABLE_ROW_TEMPLATE块，识别并保留分组/明细/小计三种模板行，写入对应行类型标记
    status: completed
  - id: verify-columndefs
    content: 核对注册表 columnDefs 字段名与 Excel 子表头一致性，必要时修正
    status: completed
    dependencies:
      - reverse-multi-row-tpl
---

## 用户需求

历史报告中供应商关联采购/客户关联销售明细表包含多种行类型：

- **分组标题行**（如"境外采购"，合并单元格，有背景色）
- **明细数据行**（普通数据行，含编号）
- **小计行**（如"境外采购合计"，加粗）
- **总计行**（如"关联采购合计"，加粗）

当前反向生成子模板时，只保留表头 + 1行数据模板，删除了所有其他行，导致子模板丢失了分组行和汇总行的格式结构。报告生成时也只有1种模板行可克隆，无法区分行类型、无法保留原始行结构视觉效果。

## 产品概述

扩展行模板克隆方案，支持**多行类型模板**：反向生成时保留历史报告中每种行类型各一行作为模板（分组行/明细行/小计行），每种行的第0列写入行类型标记 `{{_row_group}}`/`{{_row_data}}`/`{{_row_subtotal}}`；报告生成时读取Excel清单数据，按 `_rowType` 字段将每行路由到对应模板行克隆，1:1还原历史报告的视觉结构。

## 核心功能

- **反向生成（ReverseTemplateEngine）**：TABLE_ROW_TEMPLATE 处理块改为识别表中每种行类型，各保留1行模板，并在第0列写入对应行类型标记
- **数据提取（ReportGenerateEngine.extractRowTemplateData）**：在 `buildRowMap` 中新增 `_rowType` 字段，值为 `group`/`data`/`subtotal`
- **报告填充（ReportGenerateEngine.fillTableByRowTemplateMapped）**：扫描表格时收集所有模板行（按行类型分组），对每条数据按 `_rowType` 找到对应模板行克隆填值

## 技术栈

- 现有栈：Java + Spring Boot + Apache POI（XWPF）+ EasyExcel
- 无需引入新依赖，完全在现有架构内改造

## 实现方案

### 核心思路

**多模板行方案（方案A）**：子模板保留历史报告中的每种行类型各1行，用 `{{_row_XXX}}` 标记区分，生成时按 Excel 行的 `_rowType` 字段路由到对应模板行克隆。原有 `{{_tpl_占位符名}}` 和 `{{_col_字段名}}` 占位符体系完全复用，仅扩展行类型维度。

### 改造链路

```mermaid
graph LR
    A[历史报告Word] -->|ReverseTemplateEngine| B[子模板Word]
    B --> B1[表头行-原样保留]
    B --> B2[分组行: {{_tpl_}} {{_row_group}} {{_col_xxx}}]
    B --> B3[明细行: {{_tpl_}} {{_row_data}} {{_col_xxx}}]
    B --> B4[小计行: {{_row_subtotal}} {{_col_xxx}}]
    C[Excel清单数据] -->|extractRowTemplateData| D[带_rowType的行Map列表]
    D --> E[fillTableByRowTemplateMapped]
    B -->|多模板行Map| E
    E -->|按_rowType克隆对应模板行| F[生成报告Word]
```

### 行类型识别规则

**反向生成时（从Word历史报告识别）**：

- 分组行（group）：第0列合并到末列（或col1-2均空），且文本不含"小计/合计/总计"
- 明细行（data）：通常是第1行数据行（即原来的模板行，col1为数字或有编号列）
- 小计行（subtotal）：任意列文本含"小计"/"合计"/"总计"

**数据提取时（从Excel读取已有）**：

- 已有 `extractRowTemplateData()` 中的判断分支，只需在 `buildRowMap()` 新增 `_rowType` 字段

### 关键设计决策

1. **`{{_tpl_占位符名}}` 写在 data 行还是所有行的第0列？** 只写在明细行（data）第0列，用于定位表格；分组/小计行第0列只写 `{{_row_XXX}} {{_col_字段名}}`
2. **多模板行收集**：`fillTableByRowTemplateMapped` 扫描时不再只找第一个 `{{_tpl_}}`，而是收集所有含 `{{_row_XXX}}` 标记的行，按类型建立 Map
3. **向下兼容**：若子模板只有1种模板行（旧格式，无 `{{_row_}}`），退化为现有逻辑

## 实现细节

### 1. ReverseTemplateEngine — TABLE_ROW_TEMPLATE 块改造（约1695行）

**当前逻辑**：保留行0（表头）+ 行1（写占位符），删除行2+

**新逻辑**：

```
- 行0（表头行）：原样保留
- 扫描行1起，识别行类型，各取一行：
    * 找到第一个分组行 → 写入 {{_row_group}} {{_col_字段名}} ...，保留原格式
    * 找到第一个明细行 → 写入 {{_tpl_占位符名}} {{_row_data}} {{_col_字段名}} ...，保留原格式
    * 找到第一个小计行 → 写入 {{_row_subtotal}} {{_col_字段名}} ...，保留原格式
- 删除其余行（保留表头+最多3种模板行）
```

识别方法（复用 extractRowTemplateData 的判断逻辑）：

- 分组行：col0非空 & col0不含小计/合计/总计 & 其他列均空（合并行特征）
- 小计行：任意列文本含"小计"/"合计"/"总计"
- 明细行：非分组非小计的普通数据行

**注意**：写入占位符时只清文本，不改变单元格的合并属性、背景色、字体加粗等XML样式，这样克隆出的行天然继承原始格式。

### 2. ReportGenerateEngine — buildRowMap 新增 _rowType（约431行）

在 `extractRowTemplateData()` 各分支中，调用 `buildRowMap` 时传入行类型，或直接在分支结束前 `rowMap.put("_rowType", "group"/"data"/"subtotal")`。

### 3. ReportGenerateEngine — fillTableByRowTemplateMapped 改造（约902行）

**扫描阶段改造**：

```java
// 收集所有模板行：按 _row_XXX 标记类型建立 Map
Map<String, Integer> rowTypeToTplIdx = new LinkedHashMap<>();
// "data" 行（含 {{_tpl_}}），"group" 行（含 {{_row_group}}），"subtotal" 行（含 {{_row_subtotal}}）
```

**克隆阶段改造**：

```java
for (Map<String, Object> dataRowMap : rowData) {
    String rowType = dataRowMap.getOrDefault("_rowType", "data").toString();
    Integer srcIdx = rowTypeToTplIdx.getOrDefault(rowType,
                         rowTypeToTplIdx.getOrDefault("data", -1));
    // 按 srcIdx 克隆对应模板行
}
```

**删除阶段**：最后删除所有模板行（即 rowTypeToTplIdx 中记录的行）。

**向下兼容**：若 `rowTypeToTplIdx` 中只有 `data` 行（旧子模板无 `{{_row_}}`），走原有单模板行逻辑。

### 4. columnDefs 字段名修正

反向生成时，对 TABLE_ROW_TEMPLATE 行，columnDefs 中的字段名需与Excel子表头精确一致。当前注册表已有 `List.of("供应商名称", "交易金额", "占关联采购总金额比例")`，需确认与 Excel 实际子表头（行5）的列名完全吻合，若不一致需在注册表中修正。

## 目录结构

```
src/main/java/com/fileproc/report/service/
├── ReverseTemplateEngine.java  # [MODIFY] TABLE_ROW_TEMPLATE块：识别多种行类型，各保留1行模板，写行类型标记
└── ReportGenerateEngine.java   # [MODIFY] buildRowMap新增_rowType；fillTableByRowTemplateMapped支持多模板行路由
```