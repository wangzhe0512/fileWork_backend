---
name: fix-rowTemplateSheets-hardcode
overview: 将 ReportGenerateEngine 中硬编码的 rowTemplateSheets Set 改为从注册表动态生成：凡 ph_type=TABLE_ROW_TEMPLATE 且非 BVD SummaryYear 的条目，其 sheetName 自动进入路由集合，彻底消除新增占位符时需要手动修改代码的问题。同步将 extractRowTemplateData 内部的 sheetName 硬编码分支改为通用逻辑（或按注册表元数据路由）。
todos:
  - id: dynamic-row-template-sheets
    content: 将 ReportGenerateEngine.java 第 82-84 行的硬编码 rowTemplateSheets Set 改为从 placeholders 动态推断，并补充 Collectors import
    status: completed
---

## 用户需求

修复 `ReportGenerateEngine.java` 中 `rowTemplateSheets` 硬编码问题，使其符合注册表驱动的设计原则。

## 产品概述

当前代码在第 82-84 行用 `Set.of(...)` 硬编码了所有清单类 `TABLE_ROW_TEMPLATE` 的 Sheet 名集合。每当新增一类清单占位符时，必须手动修改此 Set，违背了"注册表驱动、零代码扩展"的设计原则。

## 核心功能

- **动态构建 rowTemplateSheets**：删除硬编码 `Set.of(...)`，改为从已有的 `placeholders` 参数列表中动态推断：凡是 `type=table` + `dataSource=list` + `sourceSheet` 非空的占位符，其 `sourceSheet` 即为需要走 `extractRowTemplateData` 路径的 Sheet 名。
- **零侵入**：不新增方法参数、不新增类、不修改数据库，仅修改 `generate()` 方法内的一行构建逻辑，保持现有路由分支（BVD SummaryYear 单独 if、清单行模板 else-if、普通表格 else）完全不变。
- **向后兼容**：替换前后功能完全等价，已有清单（供应商、客户、劳务、关联公司、组织结构）的行为不变，新增清单类占位符注册后自动生效，无需修改引擎代码。

## 技术栈

沿用现有项目技术栈：Java 17 + Spring Boot + MyBatis-Plus + Lombok，不引入任何新依赖。

## 实现方案

### 核心思路

利用 `generate()` 方法已有的 `placeholders: List<Placeholder>` 入参，通过 Stream 过滤动态构建 `rowTemplateSheets`，替换第 84 行的 `Set.of(...)` 硬编码。

**过滤条件分析**（基于已确认的 `Placeholder` 字段）：

| 条件 | 原因 |
| --- | --- |
| `"table".equals(ph.getType())` | 清单类行模板的 `type` 字段均为 `table` |
| `"list".equals(ph.getDataSource())` | 清单类数据来源均为 `list`，排除 BVD 占位符 |
| `ph.getSourceSheet() != null` | 防止空值污染 Set |


**为何不需要额外排除 BVD SummaryYear**：代码第 111 行已有 `if ("bvd".equals(dataSource) && "SummaryYear".equals(ph.getSourceSheet()))` 的优先判断，BVD 类型已被 `dataSource=list` 条件天然排除。

### 关键决策

**选择从 `placeholders` 推断而非调用 `placeholderRegistryService.getEffectiveRegistry()`**：

- `placeholders` 已在方法入参中，零额外 IO，性能最优
- `getEffectiveRegistry()` 返回 `RegistryEntry`（注册表全集，未必与当前模板绑定的占位符一致），存在多读数据的风险
- 语义更准确：`rowTemplateSheets` 应仅覆盖当前生成任务涉及的占位符，而非全系统所有注册表条目

### 性能与可靠性

- Stream 过滤为一次线性遍历 O(n)，`n` 为占位符数量（通常 < 50），无性能开销
- 构建结果为 `HashSet`，后续 `contains()` 均为 O(1)，与原 `Set.of()` 性能相同
- `placeholders` 为空时 Set 为空集，路由逻辑退化为普通表格路径，行为安全

## 修改细节

**ReportGenerateEngine.java 第 82-84 行**，将：

```java
// 行模板类型 Sheet 名集合（用于路由到 extractRowTemplateData）
// "6 劳务交易表" 同时对应劳务支出和收入两个占位符，由 extractRowTemplateData 内部按占位符名区分
Set<String> rowTemplateSheets = Set.of("4 供应商清单", "5 客户清单", "6 劳务交易表", "2 关联公司信息", "1 组织结构及管理架构");
```

替换为：

```java
// 行模板类型 Sheet 名集合：从当前占位符列表动态推断，无需硬编码
// 凡是 type=table + dataSource=list 的占位符，其 sourceSheet 即为行模板路径的 Sheet 名
// BVD SummaryYear 已由上方独立 if 分支处理，dataSource=list 条件天然将其排除
Set<String> rowTemplateSheets = placeholders.stream()
        .filter(ph -> "table".equals(ph.getType()) && "list".equals(ph.getDataSource())
                && ph.getSourceSheet() != null)
        .map(Placeholder::getSourceSheet)
        .collect(Collectors.toSet());
```

需确认 `java.util.stream.Collectors` 已在 import 中（当前文件 import `java.util.*` 已覆盖 `java.util.stream.*` 所需的 `Collectors`，实际需检查是否已显式 import `Collectors`）。

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReportGenerateEngine.java   # [MODIFY] 仅修改第 82-84 行，将硬编码 Set.of() 替换为动态 Stream 推断
```