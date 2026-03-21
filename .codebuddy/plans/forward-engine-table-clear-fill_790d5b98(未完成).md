---
name: forward-engine-table-clear-fill
overview: 在正向引擎中支持 TABLE_CLEAR 类型占位符的填充：找到子模板中的 {{清单模板-PL-xxx}} 等占位符所在的 Word 表格，用清单 Excel 中对应 Sheet 的完整数据替换表格内容。
todos:
  - id: add-table-clear-map
    content: 在 ReportGenerateEngine.java 顶部新增静态常量 TABLE_CLEAR_SHEET_MAP（19条占位符名→Sheet名映射）
    status: pending
  - id: add-fill-method
    content: 在 ReportGenerateEngine.java 新增私有方法 fillTableClearPlaceholders，扫描 Word 表格中残留占位符并用清单 Excel 数据填充
    status: pending
    dependencies:
      - add-table-clear-map
  - id: wire-main-flow
    content: 在 generate() 主流程 replaceHeaderFooterPlaceholders 调用后追加 fillTableClearPlaceholders 调用，传入 listFilePath 和 excelDataCache
    status: pending
    dependencies:
      - add-fill-method
---

## 用户需求

生成的报告中，Word 子模板表格"报告索引"列里残留 `{{清单模板-PL-12行以上的表格内容}}`、`{{清单模板-5_客户清单}}` 等 TABLE_CLEAR 类型占位符标记未被替换，需要在正向引擎生成报告时，将这类占位符所在的 Word 表格用清单 Excel 对应 Sheet 的真实数据填充（选项B：用清单Excel中对应表格的数据填充进去）。

## 产品概述

报告生成正向引擎（`ReportGenerateEngine`）在生成报告时，需额外处理一类特殊占位符：TABLE_CLEAR 类型占位符。这类占位符由反向引擎写入子模板（如 `{{清单模板-PL}}`），在反向引擎阶段只做"清空数字列"操作，不存储 Sheet 信息；正向引擎在 Placeholder 列表中也没有这些条目（`system_placeholder` 表不收录此类格式），导致这些 `{{...}}` 标记原样保留在最终报告中。

## 核心功能

- 正向引擎生成报告时，额外扫描 Word 文档中所有仍含 `{{...}}` 标记的表格单元格
- 通过内置静态映射表（占位符名 → Excel Sheet 名）解析对应 Sheet
- 从清单 Excel（type=list）中读取对应 Sheet 的全部行数据
- 将数据填充到占位符所在的 Word 表格（复用现有 `fillTableWithData`/`clearCellText` 逻辑）
- 无法通过映射表解析的占位符，降级替换为空字符串（消除残留标记），记录 warn 日志

## 技术栈

与现有项目完全一致：Java + Spring Boot，Apache POI（XWPFDocument/XWPFTable/XWPFTableCell），EasyExcel，Lombok。不引入任何新依赖。

## 实现思路

**根本原因**：`SystemTemplateParser` 的占位符正则 `^([^-]+)-(.*?)([A-Za-z]+\d+) 要求以"字母+数字"（单元格地址）结尾，而 TABLE_CLEAR 类型占位符名（如 `清单模板-PL-12行以上的表格内容`）不含单元格地址，无法被解析器识别，因此 `system_placeholder` 表中没有这类条目，正向引擎 Placeholder 列表中也没有，Word 中的标记自然保留。

**方案（方案A+，最小改动、最大复用）**：
仅修改 `ReportGenerateEngine.java`：

1. 新增静态常量 `TABLE_CLEAR_SHEET_MAP`：维护19条 TABLE_CLEAR 占位符名 → 清单 Excel Sheet 名的对应关系（与 `ReverseTemplateEngine.PLACEHOLDER_REGISTRY` 对齐）。
2. 新增私有方法 `fillTableClearPlaceholders(doc, listFilePath, excelDataCache)`：在正向引擎主流程最后一步，扫描 Word 文档全部表格，定位仍含 `{{...}}` 标记的单元格，通过 `TABLE_CLEAR_SHEET_MAP` 查 Sheet 名，读取 Excel 数据，调用已有的 `clearCellText` + `fillTableWithData` 填充表格；对未命中映射表的占位符直接清除标记（避免报告中出现占位符原文）。
3. 在 `generate()` 主流程中，在 `replaceHeaderFooterPlaceholders` 调用后追加该步骤，并将 `dataFileByType` 和 `excelDataCache` 传入，利用已有缓存避免重复 IO。

**为什么不同时修改反向引擎注册表**：
反向引擎设计上 TABLE_CLEAR 的 `sheetName=null` 是刻意的（它只清空 Word 表格数字列，不读值），此改动不影响反向引擎的正确性；只改正向引擎，影响最小，兼容性最强。

## 实现细节（关键执行注意事项）

- **复用缓存**：`excelDataCache` 已在 `generate()` 方法中创建，在 `fillTableClearPlaceholders` 中继续复用，key 格式保持 `"list:SheetName"`，避免对同一 Sheet 重复读取 IO。
- **占位符扫描范围**：只扫描 Word 表格单元格（TABLE_CLEAR 占位符本身就在表格内），不扫描正文段落，避免与已处理的 textValues 替换阶段冲突。
- **填充策略**：与现有 `replaceTablePlaceholders` 逻辑一致：在找到包含占位符标记的单元格所属 `XWPFTable` 上调用 `clearCellText` + `fillTableWithData`，保留表格第一行作为表头，从第1行起填入 Excel 数据行。
- **降级兜底**：对 `TABLE_CLEAR_SHEET_MAP` 中不存在的占位符名，将该单元格的占位符标记替换为空字符串（避免残留），记录 `warn` 日志提示需补充映射。
- **不修改 `system_placeholder` 表 / `CompanyTemplatePlaceholder` 表**：当前持久层数据结构不变，所有改动封装在引擎内部，影响范围最小。
- **`listFilePath` 获取**：在 `generate()` 方法中，`dataFileByType.get("list")` 即为清单 Excel 的 `DataFile`，其 `filePath` 已解析为绝对路径。

## 架构设计

```
generate() 主流程
  ├── 阶段1: 构建 textValues / tableValues（现有，读 system_placeholder 列表）
  ├── 阶段2: 读取模板，替换占位符
  │   ├── mergeAllRunsInDocument
  │   ├── replaceParagraphPlaceholders (textValues)
  │   ├── replaceTablePlaceholders (tableValues)
  │   ├── replaceTextInTables (textValues)
  │   ├── replaceHeaderFooterPlaceholders (textValues)
  │   └── [NEW] fillTableClearPlaceholders(doc, listFilePath, excelDataCache)
  │       ├── 扫描 doc.getTables() → 找含 {{...}} 的单元格
  │       ├── TABLE_CLEAR_SHEET_MAP.get(phName) → sheetName
  │       ├── readSheet(listFilePath, sheetName) (命中缓存直接返回)
  │       ├── extractTableData(rows, sheetName)
  │       └── clearCellText + fillTableWithData
  └── 写出 Word 文件
```

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReportGenerateEngine.java   # [MODIFY]
    # 1. 新增静态常量 TABLE_CLEAR_SHEET_MAP（类顶部，19条映射）
    # 2. generate() 方法末尾（第122行后）追加 fillTableClearPlaceholders 调用
    #    并将 listFilePath 和 excelDataCache 传入
    # 3. 新增私有方法 fillTableClearPlaceholders(doc, listFilePath, excelDataCache)
```

## 关键代码结构

TABLE_CLEAR_SHEET_MAP 静态映射表（与反向引擎注册表 PLACEHOLDER_REGISTRY 中19条 TABLE_CLEAR 条目对应）：

| 占位符名 | Excel Sheet 名 |
| --- | --- |
| 清单模板-PL-12行以上的表格内容 | PL |
| 清单模板-PL | PL |
| 清单模板-PL含特殊因素调整 | PL |
| 清单模板-1_组织结构及管理架构 | 1_组织结构及管理架构 |
| 清单模板-主要产品-A列中所列所有产品 | 主要产品 |
| 清单模板-2_关联公司信息 | 2_关联公司信息 |
| 清单模板-关联方个人信息 | 关联方个人信息 |
| 清单模板-关联关系变化情况 | 关联关系变化情况 |
| 清单模板-关联交易汇总表 | 关联交易汇总表 |
| 清单模板-5_客户清单 | 5_客户清单 |
| 清单模板-4_供应商清单 | 4_供应商清单 |
| 清单模板-6_劳务交易表 | 6_劳务交易表 |
| 清单模板-劳务成本费用归集 | 劳务成本费用归集 |
| 清单模板-资金融通 | 资金融通 |
| 清单模板-有形资产信息 | 有形资产信息 |
| 清单模板-功能风险汇总表 | 功能风险汇总表 |
| 清单模板-3_分部财务数据 | 3_分部财务数据 |
| 清单模板-公司经营背景资料 | 公司经营背景资料 |
| 清单数据模板-公司间资金融通交易总结 | 公司间资金融通交易总结 |


> Sheet 名以清单 Excel 实际 Sheet 名为准；`PL含特殊因素调整` 等变体映射到同一 `PL` Sheet，实际 Sheet 名须与客户 Excel 一致（若不同需调整映射）。

## Agent Extensions

### SubAgent

- **code-explorer**
- 用途：若需要扫描多个文件确认 TABLE_CLEAR 占位符出现位置、或验证清单 Excel Sheet 名称与映射表的一致性时使用
- 预期结果：准确定位所有受影响的代码位置，确保映射表与实际 Excel Sheet 名完全匹配