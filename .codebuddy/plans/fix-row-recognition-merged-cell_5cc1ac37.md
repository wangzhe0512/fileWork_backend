---
name: fix-row-recognition-merged-cell
overview: 修复 ReverseTemplateEngine 中 TABLE_ROW_TEMPLATE 行类型识别逻辑：Word 表格中 data/subtotal 行的第0列是合并单元格延续行（空），POI 的 getTableCells() 跳过该列，导致 scanCells.get(0) 返回供应商名称列（非空），被误识别为 group 行；最终 dataRowIdx 退化为 1（与 groupRowIdx 相同），data/subtotal 行的标记被 group 标记覆盖。修复：扫描行类型时，使用 XML-level 的实际列数（getTc() 的 gridSpan 或通过 rows.get(0) 的表头列数推算）确定第0列是否真的有内容，而不依赖 scanCells.get(0)。
todos:
  - id: fix-row-type-detection
    content: 修复 ReverseTemplateEngine.java L1718~L1745 行类型识别逻辑，用 headerColCount 对比法区分 group/data/subtotal 行
    status: completed
---

## 用户需求

修复 `ReverseTemplateEngine.java` 中 TABLE_ROW_TEMPLATE 表格的行类型识别逻辑，使逆向生成的子模板中：

- data 行第0列正确写入 `{{_tpl_清单模板-4_供应商关联采购明细}} {{_row_data}} {{_col_供应商名称}}`
- subtotal 行第0列正确写入 `{{_row_subtotal}} {{_col_供应商名称}}`
- group 行第0列正确写入 `{{_row_group}}`（不含 `{{_col_...}}`，因为 group 行第0列是合并单元格首行）

## 产品概述

当前生成的子模板表格行类型识别错误，导致 data 行和 subtotal 行的第0列缺少行标记，模板无法被报告生成引擎正确识别和填充。

## 核心功能

- **行类型识别修复**：通过比较当前行的单元格数量（`scanCells.size()`）与表头行的单元格数量（`headerColCount`）来区分行类型：
- `scanCells.size() == headerColCount` 且 col0 非空 → group 行
- `scanCells.size() < headerColCount` 且 不含小计/合计/总计 → data 行（合并单元格延续行）
- 含"小计/合计/总计"→ subtotal 行（优先判断，与原逻辑一致）

## Tech Stack

现有项目：Java 17 + Spring Boot + Apache POI（操作 Word .docx），纯逻辑修改，不引入新依赖。

## 实现思路

### 根本原因

Word 表格中关联方名称列是**纵向合并单元格**（跨多行）：

- **group 行**（行1）是合并单元格首行，有实际内容，POI `getTableCells()` 返回全部4列
- **data 行**（行2~6）和 **subtotal 行**（行7）是合并单元格延续行，POI `getTableCells()` 会**跳过**被合并的第0列，只返回3列

当前 L1728 代码：

```java
String col0Str = scanCells.isEmpty() ? "" : scanCells.get(0).getText().trim();
if (!col0Str.isEmpty()) { groupRowIdx = ri; continue; }
```

data 行的 `scanCells.get(0)` 实际是**供应商名称列**（原始第1列），`col0Str` 非空，被误判为 group 行 → `dataRowIdx = null` → 退化 `dataRowIdx = 1 = groupRowIdx`。

### 修复策略

在扫描循环前记录表头行的列数 `headerColCount = rows.get(0).getTableCells().size()`，然后在每行判断时：

- `scanCells.size() < headerColCount` → 合并单元格延续行，一定是 data 或 subtotal
- `scanCells.size() == headerColCount` 且 col0 非空 → group 行

此方案不依赖单元格内容，只依赖结构特征，更可靠。

### 已有代码兼容

`writeRowMarkers` 中的动态列映射逻辑（`sizeDiff = colDefs.size() - tplCells2.size()`）已正确处理不同行的列数差异，修复行识别后该逻辑可正常工作。

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java  # [MODIFY]
    # L1718 前：添加 headerColCount 计算
    # L1719~L1745：重写行类型判断逻辑
    #   - 增加 headerColCount 变量
    #   - group 判断改为：scanCells.size() == headerColCount && col0Str 非空
    #   - data 判断改为：scanCells.size() < headerColCount && 非 subtotal
```