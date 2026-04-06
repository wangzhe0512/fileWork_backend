---
name: fix-list-table-row-template-col-infer
overview: 为清单类 TABLE_ROW_TEMPLATE 占位符补充反向模板表头自动识别逻辑：当 Word 表头列是中文字段名时，直接从注册表 availableColDefs 精确匹配，不再依赖仅适用于 BVD 的 BVD_COLUMN_KEYWORD_MAP。
todos:
  - id: modify-infer-method
    content: 改造 ReverseTemplateEngine.java：更新 inferColumnDefsFromWordTable 签名、新增 availableColDefs 二级匹配分支、同步调用点
    status: completed
---

## 用户需求

为清单类 `TABLE_ROW_TEMPLATE` 占位符（关联公司信息、供应商明细、客户明细、劳务交易表、组织结构及管理架构）补充类似 BVD 的"历史报告表头自动识别"逻辑。

## 问题背景

`ReverseTemplateEngine.inferColumnDefsFromWordTable` 方法内部仅使用 `BVD_COLUMN_KEYWORD_MAP`（英文关键词 Map）进行列头匹配。清单类占位符的 Word 表头为中文（如"关联方名称"、"主要部门"、"交易金额"），无法命中任何 key，导致 `matchCount=0`，每次都回退到注册表默认 `columnDefs`，无法自动识别历史报告表格中用户已有的列定制结果。

## 核心功能

- 改造 `inferColumnDefsFromWordTable` 方法签名，新增 `availableColDefs` 参数
- 保留原 BVD 英文关键词匹配路径（向后兼容）
- 新增第二匹配路径：当 BVD 路径 `matchCount=0` 且 `availableColDefs` 非空时，遍历 Word 表头每个单元格文字，在 `availableColDefs` 中按 equals/contains 精确匹配字段名，命中则记录对应 fieldKey
- 如果两条路径都失败，回退原有逻辑（返回默认 `columnDefs`）
- 同步更新唯一调用点（第2012行）传入 `entry.getAvailableColDefs()`

## 技术栈

- **语言**：Java（Spring Boot 后端，已有项目）
- **修改范围**：仅 `ReverseTemplateEngine.java` 一个文件，改动最小化

## 实现思路

### 高层策略

在 `inferColumnDefsFromWordTable` 内部新增第二匹配分支（availableColDefs 精确匹配），完全不影响 BVD 路径的既有行为。整体遵循"优先 BVD 关键词 → 其次 availableColDefs 直接匹配 → 最后 fallback"的三级降级链。

### 签名变更

```java
// 改造前
private List<String> inferColumnDefsFromWordTable(XWPFTable table, List<String> fallback)

// 改造后（新增 availableColDefs 参数）
private List<String> inferColumnDefsFromWordTable(XWPFTable table, List<String> fallback, List<String> availableColDefs)
```

### 三级匹配逻辑

1. **第一级（BVD 关键词匹配）**：现有 `BVD_COLUMN_KEYWORD_MAP` 遍历，cellText.toLowerCase().contains(key)，matchCount > 0 时直接返回结果，与现在完全一致
2. **第二级（availableColDefs 直接匹配）**：当 BVD 匹配 matchCount=0 且 availableColDefs 非空时启用

- 对每个 headerCell 的文字 `cellText`，在 `availableColDefs` 中先尝试 equals（trim 后精确匹配），未命中再尝试 contains（cellText 包含字段名 或 字段名包含 cellText，兜底宽松匹配）
- 命中则 fieldKey = 对应 availableColDefs 元素；未命中则 add(null)
- 若二级 matchCount > 0 返回推断结果，否则继续降级

3. **第三级（fallback）**：两级均失败时返回 fallback（注册表默认 columnDefs），与现在一致

### 调用点同步

第 2012 行调用处新增第三个参数：

```java
List<String> inferredColDefs = inferColumnDefsFromWordTable(
    targetTable, entry.getColumnDefs(), entry.getAvailableColDefs());
```

### 性能与可靠性

- `availableColDefs` 通常仅 3~11 个元素，双层 contains 嵌套遍历开销极低（O(n*m)，n/m 均为个位数）
- BVD 占位符的 `availableColDefs` 为英文（如 COMPANY、FY2023_STATUS），第一级 BVD 关键词已能命中，第二级永远不会被触发，完全不影响 BVD 路径
- 方法内部无状态，线程安全，无需额外同步
- 日志分级：二级匹配命中打 info，全部失败打 debug，与现有日志风格保持一致

## 实现注意事项

- `entry.getAvailableColDefs()` 已在 `RegistryEntry` 中存在且被 `PlaceholderRegistryService.toRegistryEntry` 正确填充，无需改动 DTO 或 Service
- 对于 `availableColDefs` 为 null 的旧注册条目（8参数构造，未传 availableColDefs），第二级直接跳过，不影响现有行为
- cellText 比对前需 trim，防止空白字符干扰
- contains 匹配顺序建议：先判断 cellText 完全 equals fieldKey，再判断 cellText contains fieldKey，兜底判断 fieldKey contains cellText，取第一个命中项，避免歧义（如"交易金额"不应匹配"占总经营成本费用比重"）

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java   # [MODIFY] 改造 inferColumnDefsFromWordTable 签名及内部逻辑；更新唯一调用点
```