---
name: 简化子模板结构-删除多余data行
overview: 修改反向生成逻辑，对于TABLE_ROW_TEMPLATE类型表格，只保留第1行data行作为模板，删除红框中的其他data行，保留subtotal行
todos:
  - id: simplify-row-template
    content: 修改ReverseTemplateEngine.java，删除TABLE_ROW_TEMPLATE中多余的data行，只保留第1行data模板和subtotal行
    status: completed
---

## 产品概述

修改反向生成子模板时的行数处理逻辑，对于TABLE_ROW_TEMPLATE类型表格，删除多余的data行，只保留第1行data行作为模板，同时保留subtotal行。

## 核心需求

从截图分析，当前生成的子模板包含多行data行，用户希望简化结构：

- 当前：表头行 + 多行data行 + subtotal行
- 期望：表头行 + 第1行data行（模板） + subtotal行

## 具体修改内容

1. 保留表头行（第0行）
2. 保留第1行data行（包含{{*tpl*...}} {{_row_data}}标记，作为克隆模板）
3. 删除第2行到倒数第2行的其他data行
4. 保留末行subtotal行（{{_row_subtotal}}）

## 技术栈

- Java 17
- Apache POI 5.x（XWPF操作Word文档）
- XMLBeans（底层XML操作）

## 实现方案

### 核心修改

修改 `ReverseTemplateEngine.java` 中 `TABLE_ROW_TEMPLATE` 分支的处理逻辑：

1. **遍历所有行，识别行类型**（data/group/subtotal）
2. **记录第1个data行的索引**（作为模板保留）
3. **从后往前删除其他data行**（避免索引错乱）
4. **保留subtotal行**

### 关键实现细节

```java
// 在TABLE_ROW_TEMPLATE处理逻辑中，写入占位符后执行行数精简
List<Integer> dataRowIndices = new ArrayList<>();
List<Integer> subtotalRowIndices = new ArrayList<>();

// 遍历所有行（从第1行开始，跳过表头），记录行类型
for (int ri = 1; ri < rows.size(); ri++) {
    XWPFTableRow row = rows.get(ri);
    String rowType = determineRowType(row, headerColCount); // 复用已有的行类型判断逻辑
    if ("{{_row_data}}".equals(rowType)) {
        dataRowIndices.add(ri);
    } else if ("{{_row_subtotal}}".equals(rowType)) {
        subtotalRowIndices.add(ri);
    }
}

// 删除多余的data行（只保留第1个data行）
if (dataRowIndices.size() > 1) {
    // 从后往前删除，避免索引错乱
    for (int i = dataRowIndices.size() - 1; i >= 1; i--) {
        int rowIdx = dataRowIndices.get(i);
        targetTable.removeRow(rowIdx);
    }
}
```

### ReportGenerateEngine.java调整

确保正向生成时能正确处理单行data模板+subtotal行的结构：

1. 识别data行模板（含{{*tpl*}}和{{_row_data}}）
2. 识别subtotal行（含{{_row_subtotal}}）
3. 根据实际数据行数M，克隆data行模板M-1次
4. 填充数据，保留subtotal行在末尾

## 目录结构

```
src/main/java/com/fileproc/report/service/
├── ReverseTemplateEngine.java  # [MODIFY] 添加删除多余data行逻辑
└── ReportGenerateEngine.java   # [MODIFY] 调整正向生成逻辑（如需要）
```

## 验收标准

1. 子模板中只保留1行data行（作为模板）
2. subtotal行保留在表格末尾
3. 正向生成时根据实际数据动态克隆data行
4. subtotal行正确显示合计数据