---
name: fix-cell-text-write-corrupted
overview: 修复 ReverseTemplateEngine 中 writeRowMarkers 写入单元格文本时出现截断/残留的问题：POI setText(text, 0) 只替换 Run 的第0个 w:t 节点，若 Run 内有多个 w:t 节点（Word 格式原因），后面的 w:t 会残留，导致输出如 {_col_供应商名称}} 的乱码。同时修复 data/subtotal 行第0列（合并延续行，不在 tplCells2 中）无法写入行标记的问题。
todos:
  - id: fix-write-cell-text
    content: 修复 ReverseTemplateEngine.java：提取 writeCellText 私有方法（CTR 级清空所有 w:t 再写入），并替换 writeRowMarkers lambda 中的内联写入逻辑
    status: completed
---

## 用户需求

截图显示最新生成的子模板存在两个问题：

1. **单元格文字截断/乱码**：group 行第1列显示 `{_col_供应商名称}}`（开头缺一个 `{`），data/subtotal 行同样出现列标记字符残缺
2. **data/subtotal 行第0列为空**：合并单元格延续行无标记写入，但实际 ci==0 已对应到原始第1列（供应商名称列），问题根源是写入时文字残缺

## 产品概述

逆向模板引擎生成的子模板中，TABLE_ROW_TEMPLATE 表格各行的占位符文字必须完整、正确，供后续报告生成引擎按占位符识别和填充数据。

## 核心功能

- **彻底清空 Run 中所有 `<w:t>` 节点再写入**：修复 `writeRowMarkers` lambda 中单元格写入逻辑，避免多 `<w:t>` 残留导致的字符截断乱码
- **与现有 `mergeRunsInParagraph` 模式保持一致**：提取公共方法 `writeCellText`，使用 CTR 操作彻底清空每个 Run 的所有 `<w:t>` 后写入，只在第一个有效 Run 的第一个 `<w:t>` 中写入目标文本

## 技术栈

现有项目：Java 17 + Apache POI（`CTR`、`CTText` 已导入），纯逻辑修复，无新依赖。

## 根本原因

`writeRowMarkers` 中使用 `cellRuns.get(0).setText(wt[0], 0)` 只替换第0个 Run 的第 **0** 号 `<w:t>` 节点。当 Word 将格式或拼写检查标记打断文字时，一个 Run 内会有多个 `<w:t>` 子节点（如 `<w:t>{</w:t><w:t>_col_供应商名称}}</w:t>`）。`setText(newText, 0)` 只改第一个 `<w:t>` 的内容，后续 `<w:t>` 节点的旧文字残留，与新文字拼接后产生乱码（如 `{{_col_供应商名称}}_col_供应商名称}}`，截图中呈现为 `{_col_供应商名称}}`）。

同样问题存在于其余 Run 中（`cellRuns.get(r).setText("", 0)` 也只清空了每个 Run 的第0个 `<w:t>`）。

## 修复策略

仿照已有 `mergeRunsInParagraph` 的 CTR 操作模式，提取私有方法 `writeCellText(XWPFTableCell, String)`：

1. 遍历单元格所有段落，对每个段落，使用 `para.getCTP().getRList()` 拿到 XML 层所有 `CTR`
2. 找到第一个 `CTR`（即第一个有效 Run）：

- 清空其所有 `<w:t>`（`removeT`）后 `addNewT()` 写入目标文本，并设置 `xml:space="preserve"`
- 目标文本写完后置空（只写一次）

3. 其余所有 `CTR` 的所有 `<w:t>` 全部 `setStringValue("")`
4. 若段落内无任何 `CTR`（纯空段落），且目标文本非空，则 `para.createRun().setText(text)` 新建一个 Run 写入

用此方法替换 `writeRowMarkers` lambda 中原有的内联写入逻辑（L1807~L1833）。

## 实现注意点

- `CTR.getTList()` 返回的是 live list，删除时需**倒序** `removeT(i)` 避免索引越界
- `xml:space="preserve"` 对含空格的占位符（如 `{{_tpl_xxx}} {{_row_data}} {{_col_供应商名称}}`）必须设置，防止 Word 解析时截断首尾空格
- 提取为私有方法后，后续 `writeRowMarkers` 调用处代码更简洁，也可复用

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java  # [MODIFY]
    # 新增私有方法 writeCellText(XWPFTableCell cell, String text)
    #   位于 mergeRunsInParagraph 方法附近（L2688 之后的辅助方法区）
    # 修改 writeRowMarkers lambda（L1807~L1833）
    #   用 writeCellText(cell, writeText) 替换原有的内联写入逻辑
```