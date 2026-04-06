---
name: table-clear-full-remove-empty-rows
overview: 在 TABLE_CLEAR_FULL 整表清空后，删除表格中第2行起的所有多余空行，只保留第一行（含占位符）
todos:
  - id: delete-extra-rows-after-clear-full
    content: 在 TABLE_CLEAR_FULL 分支清空循环结束后追加倒序删除多余行逻辑，并重新打包
    status: pending
---

## 用户需求

在 `TABLE_CLEAR_FULL`（非财务整表全清空）策略处理完成后，表格中除第一行外的所有多余空行需要被删除，仅保留第一行（含占位符的行），其余行全部从表格中移除。

## 产品概述

反向模板引擎生成子模板时，`TABLE_CLEAR_FULL` 类型的表格（组织结构、客户清单等非财务整表）在清空内容后，子模板中应只保留 1 行作为占位符行，不应保留原始报告中所有的空行，以保证子模板的整洁性。

## 核心功能

- `TABLE_CLEAR_FULL` 清空完成后，通过 Apache POI 底层 XML API (`getCTTbl().removeTr(i)`) 删除第 1 行之后的全部行
- 仅保留索引 0 的第一行（含占位符 `{{...}}`），从最后一行向前逐一删除，避免索引错位

## 技术栈

- Apache POI 5.2.5（已引入 `poi-ooxml`）
- `XWPFTable.getCTTbl().removeTr(int index)` — 底层 CTTbl XML 行删除 API
- 修改文件：`ReverseTemplateEngine.java`（单文件单处修改）

## 实现方案

在 `TABLE_CLEAR_FULL` 分支的现有清空循环执行完毕后，紧接着追加删除多余行的逻辑：

1. 获取当前行数 `int totalRows = targetTable.getRows().size()`
2. 从末尾行（`totalRows - 1`）向前循环到索引 `1`，逐一调用 `targetTable.getCTTbl().removeTr(i)` 删除
3. 倒序删除可避免正序删除时索引偏移问题

## 实现说明

- **精确范围**：仅在 `TABLE_CLEAR_FULL` 分支内追加，不影响 `TABLE_CLEAR`（财务表）路径
- **倒序删除**：从 `rows.size()-1` 到 `1`，保留 index=0 的首行，避免索引错位
- **已有 import**：`org.apache.poi.xwpf.usermodel.*` 已导入，`getCTTbl()` 返回的 `CTTbl` 类来自 `poi-ooxml-schemas`，无需额外 import（POI 5.2.5 已内置）
- **不影响 cellsCleared 统计**：删除行操作在统计后执行，不干扰日志与匹配记录

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java  # [MODIFY] 在 TABLE_CLEAR_FULL 分支清空循环结束后，追加倒序删除多余行逻辑
```