---
name: bvd-summaryyear-second-table
overview: 为文档图表23（可比企业完全成本加成率四分位区间表）在 `replaceBvdRangeTable` 中新增识别和占位符写入逻辑，数据来源对应 BVD SummaryYear 第二张表格 E列（报告年度）的 MIN/LQ/MED/UQ/MAX 5个值，注册5个独立 BVD 占位符。
todos:
  - id: fix-registry
    content: 修正注册区：删除 SummaryYear-第二张表格(A20)，新增5个 MIN/LQ/MED/UQ/MAX 占位符（E14-E18）
    status: completed
  - id: refactor-replace-bvd
    content: 重构 replaceBvdRangeTable：区分数据表区间表和SummaryYear区间表，对图表23按行标签匹配写入5个占位符
    status: completed
    dependencies:
      - fix-registry
---

## 用户需求

文档图表23（可比企业完全成本加成率四分位区间表）没有生成任何 BVD 占位符。该表在 BVD 数据模板中对应 SummaryYear 工作表第二张统计区（行13-18），数据为报告年度（E列）的 MIN/LQ/MED/UQ/MAX 五行数值。

## 问题现状

1. 注册表中 `BVD数据模板-SummaryYear-第二张表格` 坐标指向 `SummaryYear!A20`，但 A20 是空单元格，`buildBvdEntries` 读到空值直接跳过，该条目永远不会被创建
2. `replaceBvdRangeTable` 识别到图表23后，只按行序顺序写入 `数据表-B1~B4` 四个占位符（4行），与图表23实际的5行（含最高值/最低值）及其数据来源（SummaryYear E列）均不匹配

## 核心功能

- 在注册表中新增5个精确坐标的 BVD 占位符，对应 SummaryYear 报告年度列的 MIN/LQ/MED/UQ/MAX
- 修改 `replaceBvdRangeTable` 方法，区分两种区间表类型，对图表23（SummaryYear区间表）按行标签匹配写入正确的5个占位符
- 数据表区间表（首列含"可比公司数量"）保留原有 B1~B4 逻辑不变

## 技术栈

现有项目：Java + Spring Boot + Apache POI（Word/Excel 处理）

## 实现方案

### 修改一：注册区修正（第262行）

**删除**错误条目：

```
BVD数据模板-SummaryYear-第二张表格  →  SummaryYear A20（空单元格，永远跳过）
```

**新增5条**：

| 占位符名称 | 显示名 | Sheet | 坐标 | 含义 |
| --- | --- | --- | --- | --- |
| `BVD数据模板-SummaryYear-MIN` | BVD-SummaryYear最低值 | SummaryYear | E14 | 报告年度 MIN |
| `BVD数据模板-SummaryYear-LQ` | BVD-SummaryYear下四分位 | SummaryYear | E15 | 报告年度 LQ |
| `BVD数据模板-SummaryYear-MED` | BVD-SummaryYear中位值 | SummaryYear | E16 | 报告年度 MED |
| `BVD数据模板-SummaryYear-UQ` | BVD-SummaryYear上四分位 | SummaryYear | E17 | 报告年度 UQ |
| `BVD数据模板-SummaryYear-MAX` | BVD-SummaryYear最高值 | SummaryYear | E18 | 报告年度 MAX |


`buildBvdEntries` 已有通用的坐标读取逻辑，注册区坐标修正后自动生效，无需改动该方法。

### 修改二：`replaceBvdRangeTable` 方法重构

**当前逻辑缺陷**：

- 遍历所有表格，用"四分位区间"或"可比公司数量"识别 BVD 表
- 识别到后统一按行序写入 `数据表-B1~B4`，处理完第一个表后 `break`

**新逻辑**：识别阶段区分两种表，分支处理：

```
遍历所有表格：
  识别条件A（数据表区间表）：首列任意行含"可比公司数量"
    → 原有逻辑：跳过"四分位区间"表头行，按行序写 数据表-B1~B4（4行）
  识别条件B（SummaryYear区间表）：首列任意行含"最高值"或"最低值"
    → 新逻辑：按行标签关键词精确匹配，写对应 SummaryYear 占位符
       最高值   → BVD数据模板-SummaryYear-MAX
       上四分位值 → BVD数据模板-SummaryYear-UQ
       中位值   → BVD数据模板-SummaryYear-MED
       下四分位值 → BVD数据模板-SummaryYear-LQ
       最低值   → BVD数据模板-SummaryYear-MIN
    → sourceSheet="SummaryYear"，sourceField 按占位符名对应（E14~E18）
两种表均可处理（去掉全局 break，改为各自标志位控制只处理一次）
```

**行标签匹配优于行序匹配**：图表23行顺序固定为"最高→上四分→中位→下四分→最低"，但用关键词匹配更健壮，防止未来行顺序调整时出错。

### 注意事项

- `isIndependentRangeTable` 已包含"最高值/上四分位/中位值/下四分位"关键词，可正确阻止 TABLE_CLEAR 误绑，无需修改
- 两种区间表各自只处理第一个匹配到的（分别设置 `dataTableDone` / `summaryTableDone` 标志位），防止重复处理
- SummaryYear 区间表的识别条件选用"最高值"或"最低值"（图表23首列独有，数据表区间表没有这两行），与数据表区间表的"可比公司数量"形成互斥

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java   # [MODIFY]
    ├── 注册区 第252-265行：删除 SummaryYear-第二张表格(A20)，新增5个 MIN/LQ/MED/UQ/MAX 条目
    └── replaceBvdRangeTable 第1464-1532行：重构为区分两种区间表的分支处理逻辑
```