---
name: 修复子模板行结构-保留group+data+subtotal
overview: 修复垂直合并延续行被错误标记为delete导致data行消失的问题，确保子模板包含1个group行、1个data行、所有subtotal行
todos:
  - id: fix-vmerge-row-as-data
    content: 修改 ReverseTemplateEngine.java：删除 isVMergeContinueRow 的 delete 标记分支，移除 deleteRowIndices 相关逻辑，使延续行正确标记为 data 行
    status: completed
---

## 用户需求

用户希望反向生成的子模板**完整保留历史报告的表格行结构**，即：

- **group行**（如"境外采购"，垂直合并起始行）：保留，写 `{{_tpl_...}} {{_row_group}}`
- **data行**（垂直合并延续行，第一列是vMerge continue，即各供应商数据行）：保留，写 `{{_tpl_...}} {{_row_data}}`
- **subtotal行**（小计/合计行）：全部保留，写 `{{_tpl_...}} {{_row_subtotal}}`

精简策略：子模板中保留第1个group+第1个data（其余多余的group和data删除），subtotal全部保留。

## 核心问题

当前代码错误地将垂直合并延续行（`isVMergeContinueRow`）标记为 `{{_row_delete}}` 并删除，导致子模板中缺少 `{{_row_data}}` 行。

## 期望子模板结构

```
[表头行] 关联方名称 | 交易金额 | 占比(%)
[group行] {{_tpl_...}} {{_row_group}} {{_col_关联方名称}} | {{_col_交易金额}} | {{_col_占比}}
[data行]  {{_tpl_...}} {{_row_data}} {{_col_关联方名称}}  | {{_col_交易金额}} | {{_col_占比}}
[subtotal] {{_tpl_...}} {{_row_subtotal}} {{_col_关联方名称}} | ...
[subtotal] {{_tpl_...}} {{_row_subtotal}} {{_col_关联方名称}} | ...  （总计行）
```

## 技术方案

### 修改文件

`src/main/java/com/fileproc/report/service/ReverseTemplateEngine.java`

### 修改1：行类型判断——删除 `isVMergeContinueRow` 分支（约1775行）

垂直合并延续行的第一列是 vMerge continue（内容为空），但第2、3列有正常数据，这些行本质上就是普通 data 行，应该落入最后的 `else` 分支，标记为 `{{_row_data}}`。

```java
// 删除此分支：
} else if (isVMergeContinueRow(row)) {
    rowTypeMark = "{{_row_delete}}";
}
// 直接让它走 else → {{_row_data}}
```

### 修改2：writeRowMarkers lambda 中的"全continue行跳过"逻辑（约1703行）

当前 `firstNonMergeColIdx < 0`（全部列都是 vMerge continue）时，直接 return 跳过写入。这个判断对于第一列是 vMerge continue 的 data 行没问题（第2列不是 continue），但对于极端全合并行会跳过。

**实际上**：垂直合并延续行（data行）的第一列虽是 continue（在POI中显示为空），但第2、3列是正常单元格，`firstNonMergeColIdx` 会找到第2列（ci=1），不会触发 `< 0` 的跳过逻辑。因此**此处无需修改**，现有 `firstNonMergeColIdx` 逻辑已能正确处理。

### 修改3：精简阶段——删除 deleteRowIndices 相关逻辑（约1793-1838行）

删除以下代码：

- `List<Integer> deleteRowIndices = new ArrayList<>();`（1793行）
- `if (allStr.contains("{{_row_delete}}"))` 分支（1808-1810行）
- 删除延续行的 for 循环（1830-1838行）

### 修改4：精简策略——group和data行联动删除

原始表格结构（境外采购3条数据 + 境内采购2条数据）：

```
group(境外采购) → data data data → subtotal(境外采购合计)
group(境内采购) → data data     → subtotal(境内采购合计)
subtotal(关联采购合计)
```

精简后期望（只保留第1组 group+data，所有subtotal）：

```
group(第1个) → data(第1个) → subtotal subtotal subtotal
```

精简逻辑：**先删多余data行（保留第1个），再删多余group行（保留第1个）**，删除顺序从后往前，避免索引错乱。现有逻辑已满足此要求，无需额外修改。

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java  # [MODIFY]
    - 删除 isVMergeContinueRow 行类型判断分支（1775-1777行）
    - 删除 deleteRowIndices 声明（1793行）
    - 删除 {{_row_delete}} 识别分支（1808-1810行）
    - 删除 deleteRowIndices 删除循环（1830-1838行）
```