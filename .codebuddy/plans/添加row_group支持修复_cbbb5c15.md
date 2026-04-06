---
name: 添加row_group支持修复
overview: 修复反向生成子模板中group行识别问题，确保能正确识别并保留{{_row_group}}分组行，使生成的子模板包含完整的group+data+subtotal三层结构
todos:
  - id: add-vertical-merge-detection
    content: 添加垂直合并检测方法 isVerticalMergeStart() 和 isGroupRow()
    status: completed
  - id: fix-row-type-logic
    content: 修改行类型判断逻辑，支持检测垂直合并的group行
    status: completed
    dependencies:
      - add-vertical-merge-detection
  - id: adjust-simplification-logic
    content: 调整精简行数逻辑，保留所有group行
    status: completed
    dependencies:
      - fix-row-type-logic
---

## 需求概述

修复反向生成子模板中 `{{_row_group}}` 分组行缺失的问题。

## 问题分析

当前生成的子模板只有 `{{_row_data}}` 和 `{{_row_subtotal}}` 行，缺少 `{{_row_group}}` 分组标题行。

根本原因：`isFullRowMerged()` 方法只检测**水平合并**（gridSpan跨列），但历史报告中的"境外采购"这类group行使用的是**垂直合并**（rowspan跨行）——第一列单元格跨多行，后续行的第一列为空。

## 预期效果

生成的子模板应包含：

- `{{_tpl_...}} {{_row_data}}` —— 数据行模板
- `{{_row_group}}` —— 分组标题行（如"境外采购"）
- `{{_row_subtotal}}` —— 小计/合计行

最终子模板结构：

```
{{_tpl_清单模板-4_供应商关联采购明细}} {{_row_data}}
{{_row_group}} {{_col_供应商名称}}
{{_row_subtotal}} {{_col_供应商名称}}
```

## 技术方案

### 问题诊断

当前行类型判断逻辑（1747行）：

```java
} else if (isFullRowMerged(row, headerColCount)) {
    // 只检测水平合并（gridSpan）
}
```

"境外采购"group行的特征：

- 第一列单元格有文字（如"境外采购"）
- 该单元格跨多行（rowspan/垂直合并）
- 后续行的第一列为空（被合并）

### 解决方案

在 `isFullRowMerged()` 判断之外，增加对**垂直合并**的检测：

1. **新增 `isFirstColumnMergedCell()` 方法**：检测第一列单元格是否是垂直合并的一部分（通过检查CTTcPr的vMerge属性）

2. **新增 `isGroupRow()` 方法**：综合判断是否为group行：

- 第一列有非空文字（如"境外采购"）
- 且满足以下任一条件：
    - 是垂直合并的起始单元格（vMerge="restart"）
    - 或整行水平合并（现有逻辑）

3. **修改行类型判断逻辑**（1742-1759行）：在 `isFullRowMerged()` 之前，优先检测垂直合并特征

4. **精简行数逻辑调整**（1765-1810行）：保留group行（不删除）

### 关键代码结构

```java
/**
 * 检测是否为group行（分组标题行）
 * 特征：第一列有内容，且是垂直合并的起始单元格（跨多行）
 */
private boolean isGroupRow(XWPFTableRow row, int headerColCount) {
    List<XWPFTableCell> cells = row.getTableCells();
    if (cells.isEmpty()) return false;
    
    String firstCellText = cells.get(0).getText().trim();
    if (firstCellText.isEmpty()) return false;
    
    // 检测是否是垂直合并的起始单元格
    return isVerticalMergeStart(cells.get(0));
}

/**
 * 检测单元格是否是垂直合并的起始单元格
 */
private boolean isVerticalMergeStart(XWPFTableCell cell) {
    try {
        CTTc tc = cell.getCTTc();
        if (tc != null && tc.isSetTcPr()) {
            CTTcPr tcPr = tc.getTcPr();
            if (tcPr.isSetVMerge()) {
                // vMerge="restart" 表示垂直合并的起始
                return "restart".equals(tcPr.getVMerge().getVal().toString());
            }
        }
    } catch (Exception e) {
        log.debug("[ReverseEngine] 获取vMerge失败: {}", e.getMessage());
    }
    return false;
}
```

### 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java  # [MODIFY] 修复group行识别逻辑
    - 新增 isGroupRow() 方法
    - 新增 isVerticalMergeStart() 方法
    - 修改行类型判断逻辑（1742-1759行）
    - 调整精简行数逻辑（1765-1810行，保留group行）
```

### 实现细节

1. **行类型判断优先级**：

- 优先检测subtotal（关键词"小计/合计/总计"）
- 其次检测group（垂直合并起始 或 整行水平合并非末行）
- 最后为data行

2. **精简行数策略**：

- 保留第1个data行作为模板
- 保留所有group行（可能有多个，如"境外采购"、"境内采购"）
- 保留所有subtotal行

3. **兼容性**：不影响现有财务表策略（TABLE_CLEAR_FULL）