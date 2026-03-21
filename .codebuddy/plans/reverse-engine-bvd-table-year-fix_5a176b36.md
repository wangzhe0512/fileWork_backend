---
name: reverse-engine-bvd-table-year-fix
overview: 修复反向引擎两个问题：1) 将 BVD数据表B1~B4 拆分为4条独立注册表条目，对应独立交易区间表4行数据；2) 表标题中"年度值-2"的年份文本替换为 {{清单模板-数据表-B2}}-2 格式
todos:
  - id: fix-bvd-registry-split
    content: 将 BVD 注册表 `BVD数据模板-数据表B1~B4` 拆分为 B1/B2/B3/B4 四条独立条目
    status: completed
  - id: fix-year-minus2-variant
    content: 新增 `buildYearMinusTwoVariants` 方法，并在年度字段替换分支追加减2变体替换逻辑，生成 `{{...}}-2` 占位符
    status: completed
---

## 用户需求

针对反向引擎 `ReverseTemplateEngine.java` 中独立交易区间表的两个 Bug 进行修复：

### 问题1：表格标题中起始年份缺少 `-2` 标记

- 标准模板标题格式：`（【清单模板-数据表B2】-2）至【清单模板-数据表B2】`，起始年份 = 年度值 -2
- 当前行为：`buildYearVariants` 仅生成正向变体（如 `2023年`、`2023财务年度`），不支持"年度值减2"的变体形式
- 期望行为：识别到文档中出现「年度值 -2 对应的年份」（如 B2=24→2024，则2022为2024-2）时，生成 `{{清单模板-数据表-B2}}-2` 占位符替换之

### 问题2：独立交易区间表格内4个分位数占位符全部错误

- 标准模板中4行对应4个独立占位符：`BVD数据模板-数据表 B1`（可比公司数量）、`BVD数据模板-数据表 B2`（上四分位值）、`BVD数据模板-数据表 B3`（中位值）、`BVD数据模板-数据表 B4`（下四分位值）
- 当前注册表仅一条 `BVD数据模板-数据表B1~B4`（只读B1），导致B2/B3/B4 无独立条目，替换 fallback 产生错误占位符
- 期望行为：拆分为4条独立注册表条目，分别读 BVD 数据表 B1/B2/B3/B4，生成 `{{BVD数据模板-数据表B1}}` 至 `{{BVD数据模板-数据表B4}}` 四个正确占位符

## 核心功能

- 注册表中 `BVD数据模板-数据表B1~B4` 拆分为4条独立 BVD 条目
- `buildYearVariants` 方法扩展支持「年度-2」减法变体，生成 `-2` 后缀占位符替换逻辑
- 年度字段的 DATA_CELL 替换分支中，识别出减2变体时写入 `{{清单模板-数据表-B2}}-2` 而非 `{{清单模板-数据表-B2}}`

## 技术栈

- 语言：Java（已有项目），Apache POI XWPFDocument
- 修改文件：`src/main/java/com/fileproc/report/service/ReverseTemplateEngine.java`

## 实现方案

### 修复1：BVD 注册表条目拆分（B1~B4 → 4条独立条目）

**当前问题根因**：`BVD数据模板-数据表B1~B4` 这一条仅读 B1 坐标，注册表中没有 B2/B3/B4 条目，`buildBvdEntries` 无法生成4个独立的 ExcelEntry，后续 `replaceInRunsNew` 根本没有可匹配的 B2/B3/B4 值。

**修复方案**：删除原一条 `BVD数据模板-数据表B1~B4`，拆分为4条：

```
"BVD数据模板-数据表B1" → BVD Sheet "数据表" B1（可比公司数量）
"BVD数据模板-数据表B2" → BVD Sheet "数据表" B2（上四分位值）
"BVD数据模板-数据表B3" → BVD Sheet "数据表" B3（中位值）
"BVD数据模板-数据表B4" → BVD Sheet "数据表" B4（下四分位值）
```

这样 `buildBvdEntries` 会为每个坐标读出独立值，生成4个 ExcelEntry，`replaceInRunsNew` 中 BVD 分支会分别记录为 uncertain（标准行为）。

---

### 修复2：年度 -2 变体替换逻辑

**当前问题根因**：`buildYearVariants("24")` 只生成 `[2024财务年度, 2024财年, 2024年度, 2024年, 2024]`，不包含 2022（=2024-2）的任何变体。文档标题中 `2021 到 2023` 里的 `2021`（若 B2=23，则 2021=2023-2）无法被匹配，只保留原文。

**修复方案**：

1. **扩展 `buildYearVariants`**：同时返回「年度值-2」的完整年份变体，并附带一个标识 flag 说明它是减2变体。改为返回包含元数据的结构，或分两个方法：

- `buildYearVariants(String twoDigitYear)` 保持不变（正向变体，生成 `{{phMark}}`）
- 新增 `buildYearMinusTwoVariants(String twoDigitYear)` 生成减2变体（如 `24` → `[2022财务年度, 2022财年, 2022年度, 2022年, 2022]`），这些变体匹配后生成 `{{phMark}}-2`

2. **修改 `replaceInRunsNew` 的年度字段分支**：在现有正向变体替换完成后，再追加一轮减2变体替换，命中时使用 `phMark + "-2"` 作为替换目标文本。

**关键设计细节**：

- 减2变体替换须在正向变体替换**之后**执行，防止交叉干扰
- 减2变体的 `phMark` 为 `{{清单模板-数据表-B2}}-2`（在 `{{` 和 `}}` 外面追加 `-2`）
- 减2变体命中不重复累计 matchedCount（仅附加到 matchedList 作 log 记录即可）
- 仅对年度字段（`isYearFieldEntry` 为 true 的条目）生效，其他字段不涉及

## 实现注意事项

- **不破坏现有替换顺序**：`buildExcelEntries` 中 `dataCellEntries` 按值长度降序，B2 年度值只有2位，排在靠后位置，不影响长值先替换的策略
- **BVD条目拆分后 placeholderName 需与标准模板中占位符名完全一致**：标准模板中用 `【BVD数据模板-数据表 B1】`（空格），注册表名应去掉空格匹配为 `BVD数据模板-数据表B1`（`normalizePlaceholders` 会处理内部空格）
- **减2变体：只在确实命中时才写入**，命中后打 DEBUG 日志，不抛异常
- **编译验证**：修改完成后确认 `mvn compile` 通过

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java  # [MODIFY] 两处修改：
    # 1. 第207-209行：删除 BVD数据模板-数据表B1~B4 一条，替换为 B1/B2/B3/B4 四条独立条目
    # 2. buildYearVariants 附近：新增 buildYearMinusTwoVariants 方法
    # 3. replaceInRunsNew 年度字段分支：追加减2变体替换逻辑（phMark+"-2"）
```