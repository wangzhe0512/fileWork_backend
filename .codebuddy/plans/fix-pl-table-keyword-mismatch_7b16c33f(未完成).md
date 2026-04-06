---
name: fix-pl-table-keyword-mismatch
overview: 修复上一轮改动引入的两个 bug：1）独立交易区间表被 清单模板-PL 的 titleKeywords 误命中写入了 {{清单模板-PL}}；2）财务状况表被 PL含特殊因素调整 条目误命中写入了错误占位符。根本原因是 PL 的 keywords（"营业收入"/"成本加成率"等）在独立交易区间表前置段落里也出现；修复方案：精确匹配阶段对命中的表格补充独立交易区间表排除检查，同时收紧 PL 的 titleKeywords 为只在财务状况表附近出现的词。
todos:
  - id: fix-pl-keywords-and-range-table-guard
    content: 收紧清单模板-PL的titleKeywords为["财务状况"]，提取isIndependentRangeTable方法，并在Phase 2a精确匹配阶段补充独立交易区间表排除检查
    status: pending
---

## 用户需求

修复 `ReverseTemplateEngine.java` 中 `clearTableBlock` 方法的错误绑定问题，具体表现为：

- **问题1**：`清单模板-PL` 在 Phase 2a 精确匹配阶段，由于 titleKeywords 包含 `"成本加成率"`/`"营业收入"` 等词，误命中了独立交易区间表（图表1）前置段落，导致 PL 占位符被写入独立交易区间表
- **问题2**：因 PL 抢走了独立交易区间表的绑定，`清单模板-PL含特殊因素调整` 在 fallback 阶段误命中了财务状况表，写入了错误的占位符（且名称含多余空格）

## 产品概述

基于转定价报告的逆向模板引擎，将已生成的 Word 报告反向还原为带占位符的模板文档。其中 `clearTableBlock` 方法负责定位并清空财务表格，写入占位符，是核心还原链路的关键步骤。

## 核心功能修复点

- **收紧 PL 的 titleKeywords**：移除在独立交易区间表前置段落中也会出现的干扰词（`"营业收入"`、`"成本加成率"`、`"毛利率"`、`"息税前利润"`），只保留 `"财务状况"` 这一高区分度关键词
- **Phase 2a 精确匹配阶段补充独立交易区间表排除**：在关键词命中后，额外调用独立交易区间表检测逻辑（复用 `isFinancialTable` 中排除规则5的检测方式），若命中表为独立交易区间表则跳过，继续寻找下一个匹配，避免精确匹配阶段绑定错误表格

## 技术栈

- 现有 Java 项目，Apache POI 操作 Word（`XWPFDocument`/`XWPFTable`/`XWPFTableRow`/`XWPFTableCell`）
- 修改范围：单文件 `src/main/java/com/fileproc/report/service/ReverseTemplateEngine.java`

## 实现方案

### 双重保护策略

**修复点1：收紧 PL 的 titleKeywords（第163~164行）**

将 `清单模板-PL` 注册时的 `titleKeywords` 从：

```java
List.of("财务状况", "营业收入", "成本加成率", "毛利率", "息税前利润")
```

改为：

```java
List.of("财务状况")
```

理由：

- 独立交易区间表（图表1）前置段落包含"完全成本加成率"、"营业收入"，导致这些宽泛关键词误命中
- "财务状况"在财务状况表（图表5）前置段落中固定出现，而独立交易区间表前置段落不含"财务状况"，区分度高
- 最小改动，无副作用

**修复点2：Phase 2a 精确匹配阶段增加独立交易区间表排除（第1562~1576行）**

在 `headText.contains(kw)` 命中后，立即调用独立交易区间表检查，若为独立交易区间表则 `continue`（跳过本表继续遍历），避免误绑定。

独立交易区间表检测逻辑复用 `isFinancialTable` 中排除规则5的判断方式（首列含"可比公司数量"/"上四分位"/"中位值"/"下四分位"/"四分位区间"），抽取为私有辅助方法 `isIndependentRangeTable(XWPFTable table)`，便于两处复用，同时使 `isFinancialTable` 排除规则5直接调用该方法，保持 DRY。

### 关键注意事项

- `isIndependentRangeTable` 检测逻辑与 `isFinancialTable` 排除规则5完全等价，提取后两处引用，不引入新逻辑
- Phase 2a 的 `outer:` 标签控制跳出双层循环，增加独立交易区间表排除时需要用 `continue`（跳过本表）而非 `break outer`（停止查找），逻辑要正确
- `清单模板-PL含特殊因素调整` 的 titleKeywords 和注册信息无需修改，修复 PL 误绑后该问题自动消除
- 修复后需确保日志中打印"跳过独立交易区间表"的 warn 信息，方便后续排查

## 架构设计

```mermaid
graph TD
    A[Phase 2a 精确匹配循环] --> B{headText.contains(kw)?}
    B -- 否 --> A
    B -- 是 --> C{isIndependentRangeTable?}
    C -- 是 --> D[log.warn 跳过，continue 继续遍历下一表]
    D --> A
    C -- 否 --> E[绑定为 targetTable, break outer]
    E --> F[Phase 2b fallback 路径]
    F --> G{isFinancialTable?}
    G --> H[isIndependentRangeTable 排除规则5]
```

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java  # [MODIFY]
    ├── 第163~164行：清单模板-PL titleKeywords 收紧为 List.of("财务状况")
    ├── 新增私有方法 isIndependentRangeTable(XWPFTable)：
    │   提取独立交易区间表检测逻辑（首列含四分位/可比公司相关词返回true）
    ├── 第1562~1576行 Phase 2a 精确匹配循环：
    │   在关键词命中后增加 isIndependentRangeTable 检查，
    │   若为独立交易区间表则 log.warn 并 continue 跳过，继续查找下一表
    └── 第1734~1744行 isFinancialTable 排除规则5：
        改为调用 isIndependentRangeTable 复用逻辑，消除重复代码
```