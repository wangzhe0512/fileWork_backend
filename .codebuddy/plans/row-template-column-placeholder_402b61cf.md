---
name: row-template-column-placeholder
overview: 改造行模板克隆方案，子模板模板行每列写具体字段占位符（如 {{_col_供应商名称}}），生成引擎按占位符名称从 Excel 行数据 Map 取值，消除硬编码列索引。
todos:
  - id: extend-registry-entry
    content: 在 RegistryEntry 新增 columnDefs 字段和8参数构造方法，更新两条 TABLE_ROW_TEMPLATE 注册表条目传入列字段名列表
    status: completed
  - id: reverse-engine-col-marks
    content: 改造 ReverseTemplateEngine TABLE_ROW_TEMPLATE 处理块：模板行每列按 columnDefs[ci] 写入 {{_col_字段名}}，第0列同时保留 {{_tpl_占位符名}} 标识
    status: completed
    dependencies:
      - extend-registry-entry
  - id: extract-row-template-data
    content: 在 ReportGenerateEngine 新增 extractRowTemplateData() 方法：动态解析 Excel 行5子表头构建列名映射，输出 List<Map<字段名,值>>；generate() 中新增 rowTemplateValues 容器并路由到新方法
    status: completed
    dependencies:
      - extend-registry-entry
  - id: fill-by-field-name
    content: 改造 ReportGenerateEngine：新增 fillTableByRowTemplateMapped() 按 {{_col_字段名}} 查 Map 填值，replaceTablePlaceholders() 增加 rowTemplateValues 的处理入口，旧路径保留 fallback 兼容
    status: completed
    dependencies:
      - reverse-engine-col-marks
      - extract-row-template-data
---

## 用户需求

当前行模板克隆方案在两处存在硬编码：

1. `ReportGenerateEngine.extractTableData` 中针对供应商/客户清单 Sheet 硬编码取 col2（名称）、col4（金额）、col5（占比）三列
2. `ReportGenerateEngine.fillTableByRowTemplate` 中按列索引 0/1/2 顺序填数据，不识别字段语义

用户希望改为：**子模板模板行的每个单元格写入各自对应的列字段占位符**（如 `{{_col_供应商名称}}`、`{{_col_交易金额}}`、`{{_col_占采购总金额比例}}`），生成引擎克隆行后读取每列 cell 的占位符名称，按名称从 Excel 数据行 Map 中取对应字段值填入，彻底消除列索引硬编码。

## 产品概述

行模板克隆从"按位置填数据"升级为"按字段名填数据"：子模板每列写明字段占位符，生成时引擎自动解析列名、按名称从 Excel 行取值，支持任意列顺序和任意列数，具备可扩展性。

## 核心功能

- 注册表 `RegistryEntry` 新增 `columnDefs` 字段，定义 `TABLE_ROW_TEMPLATE` 类型每列的字段名（列索引 → 字段名）
- 反向引擎 `ReverseTemplateEngine` 改造模板行写入逻辑：每列写入 `{{_col_字段名}}` 而非统一写一个 `{{_tpl_占位符名}}`，但保留原有的 `{{_tpl_占位符名}}` 作为整表标识写在第0列（用于引擎识别表格）
- 生成引擎 `ReportGenerateEngine` 改造 `extractTableData`：供应商/客户清单分支改为按子表头行（Excel 行5）动态读取列名，输出 `Map<列名, 值>` 的行列表
- 生成引擎 `fillTableByRowTemplate` 改造：克隆行后读取每个 cell 的 `{{_col_字段名}}`，按字段名从当前数据行 Map 中取值填入

## 技术栈

- Java 17 + Spring Boot（现有）
- Apache POI 5.2.5（现有，`XWPFDocument/XWPFTable/XWPFTableRow`）
- EasyExcel（现有，`readSheet`）
- 修改文件：`ReverseTemplateEngine.java`、`ReportGenerateEngine.java`

---

## 实现方案

### 总体策略

**双端联动**，通过统一的字段名约定（`{{_col_字段名}}`）解耦列索引依赖：

- **反向端**：子模板模板行每列写 `{{_col_字段名}}`，字段名来自注册表 `columnDefs` 静态声明
- **生成端**：`extractTableData` 改为动态按 Excel 子表头行解析列名 → 输出 `List<Map<字段名,值>>`；`fillTableByRowTemplate` 按 cell 内的 `{{_col_字段名}}` 查 Map 取值

两端通过字段名字符串耦合，无需新数据库字段，无需改接口签名，向后兼容旧子模板（旧子模板的 cell 无 `{{_col_}}` 标记时，退回按列索引填充）。

### 关键技术决策

**1. 字段名来源**：反向端从注册表 `columnDefs`（有序 List，index = Word 表格列索引）静态声明，无需运行时解析 Word 表头。生成端从 Excel 子表头行（行5）动态解析，两端完全解耦，互不依赖。

**2. 标记格式区分**：

- `{{_tpl_占位符名}}` 保留，写在第 0 列，作为整表标识供 `tableHasRowTemplateMarker` 检测（维持现有检测逻辑不变）
- `{{_col_字段名}}` 写在每列（含第 0 列，与 `{{_tpl_}}` 并排），生成时优先按此取值

**3. 向后兼容**：`fillTableByRowTemplate` 读取 cell 时，若 cell 内有 `{{_col_字段名}}` 则按名称查 Map；若无（旧子模板），则按列索引 fallback，不影响已有子模板。

**4. 数据结构变化**：`extractTableData` 供应商/客户清单分支的返回类型从 `List<List<Object>>` 改为 `List<Map<String,Object>>`。但上层 `tableValues` Map 的泛型为 `Map<String, List<List<Object>>>`，为保持接口一致、最小改动，引入一个内部中间结构 `RowTemplateData`，或在 `tableValues` 旁边新增 `rowTemplateValues: Map<String, List<Map<String,Object>>>`，专门用于 `TABLE_ROW_TEMPLATE` 类型，两套 Map 互不干扰。

### 性能与稳定性

- Excel 子表头行解析是 O(列数) 的一次扫描，在 `extractTableData` 入口处完成，无额外 IO
- `excelDataCache` 已按 `"type:sheetName"` 缓存，数据只读一次，改造后不引入额外 IO
- `fillTableByRowTemplate` 中按字段名查 Map 为 O(1)，优于原来的列索引 O(1)，性能持平

---

## 架构设计

```mermaid
graph TD
    A[注册表 RegistryEntry<br/>新增 columnDefs: List&lt;String&gt;<br/>供应商采购明细: 供应商名称/交易金额/占比<br/>客户销售明细: 客户名称/交易金额/占比] --> B

    B[ReverseTemplateEngine<br/>TABLE_ROW_TEMPLATE 模板行处理] --> C
    B --> D

    C[第0列写入<br/>{{_tpl_占位符名}}<br/>保留表格标识]
    D[每列按 columnDefs 写入<br/>{{_col_供应商名称}}<br/>{{_col_交易金额}}<br/>{{_col_占采购总金额比例}}]

    E[ReportGenerateEngine<br/>extractTableData 供应商/客户清单分支] --> F
    F[动态解析 Excel 行5 子表头<br/>构建 colIdx→字段名 映射<br/>输出 List&lt;Map&lt;字段名,值&gt;&gt;] --> G

    G[fillTableByRowTemplate 改造<br/>克隆模板行后<br/>读取每列 {{_col_字段名}}<br/>按名查 Map 填值<br/>无 _col_ 时 fallback 列索引] --> H

    H[生成报告<br/>Word 表格正确填充<br/>无硬编码列索引]
```

---

## 目录结构

```
src/main/java/com/fileproc/report/service/
├── ReverseTemplateEngine.java   # [MODIFY]
│   ├── RegistryEntry 新增 columnDefs: List<String> 字段及对应构造方法（8参数版本）
│   ├── 注册表（第184~189行）：两条 TABLE_ROW_TEMPLATE 条目改用新构造方法，传入列字段名列表
│   └── TABLE_ROW_TEMPLATE 处理块（第1663~1699行）：
│       模板行每列按 columnDefs[ci] 写入 {{_col_字段名}}，第0列额外保留 {{_tpl_占位符名}} 标识
│
└── ReportGenerateEngine.java    # [MODIFY]
    ├── generate() 方法：新增 rowTemplateValues: Map<String, List<Map<String,Object>>> 用于存放行模板数据
    │   并在 ph.getType().equals("table") 分支中，按 sourceSheet 判断是否为行模板 Sheet，走不同的提取方法
    ├── extractTableData()：供应商/客户清单 Sheet 分支改为调用新方法 extractRowTemplateData()，其余分支不变
    ├── extractRowTemplateData()（新方法）：
    │   - 读取 Excel 行5（子表头）动态构建 colIdx→字段名 Map
    │   - 按原有行7起的分组/明细/小计逻辑扫描，输出 List<Map<字段名,值>>（字段名与子表头对齐）
    ├── replaceTablePlaceholders()：新增对 rowTemplateValues 的处理入口，调用 fillTableByRowTemplateMapped()
    ├── fillTableByRowTemplateMapped()（新方法，替代原 fillTableByRowTemplate 中的按索引填数据部分）：
    │   - 找到模板行（含 {{_tpl_}} 的行）
    │   - 克隆 CTRow，读取每列 cell 文本，提取 {{_col_字段名}}
    │   - 按字段名查当前数据行 Map<String,Object> 取值，调用 setCellText() 写入
    │   - 无 {{_col_}} 标记的列清空（兼容旧子模板时 fallback 按列索引）
    └── tableHasRowTemplateMarker()：检测逻辑不变（仍检测 {{_tpl_}} 前缀）
```

### 关键数据结构

```java
// RegistryEntry 新增字段（ReverseTemplateEngine 内部类）
@Data
static class RegistryEntry {
    // ... 现有字段不变 ...
    
    /**
     * TABLE_ROW_TEMPLATE 专用：列字段名定义，index 对应 Word 表格列索引。
     * 反向生成时每列写入 {{_col_字段名}}；生成时按字段名从 Excel 行 Map 取值。
     * 其他类型为 null。
     */
    List<String> columnDefs;  // 新增
    
    // 新增 8 参数构造方法（TABLE_ROW_TEMPLATE 专用）
    RegistryEntry(String placeholderName, String displayName, PlaceholderType type,
                  String dataSource, String sheetName, String cellAddress,
                  List<String> titleKeywords, List<String> columnDefs) { ... }
}

// 注册表条目示例（供应商关联采购明细，Word表格3列）
new RegistryEntry("清单模板-4_供应商关联采购明细", "关联采购明细",
        PlaceholderType.TABLE_ROW_TEMPLATE, "list", "4 供应商清单", null,
        List.of("关联采购交易明细", "关联采购明细表", "采购交易明细表"),
        List.of("供应商名称", "交易金额", "占关联采购总金额比例"));  // columnDefs 新增
```

```java
// generate() 方法内新增的行模板数据容器
// key: 占位符名，value: 行列表，每行是字段名->值的 Map
Map<String, List<Map<String, Object>>> rowTemplateValues = new LinkedHashMap<>();

// extractRowTemplateData 返回类型示例（每行一个 Map）
// [ {"供应商名称":"XX公司", "交易金额":"100.00", "占关联采购总金额比例":"10.00%"}, ... ]
```

## Agent Extensions

### SubAgent

- **code-explorer**
- Purpose: 在执行改造时，若需要进一步确认 Excel 供应商清单 Sheet 行5子表头的实际列名，或验证多个调用链路上下文，使用此 SubAgent 进行跨文件深度搜索
- Expected outcome: 准确定位 Excel 子表头实际字段名，确保 columnDefs 声明与 Excel 结构完全对齐