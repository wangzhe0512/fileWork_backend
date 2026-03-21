---
name: forward-engine-year-expand
overview: 正向引擎 B2 年度字段替换时，将2位年份（如"24"）扩展为"2024年"格式，而不是原样替换
todos:
  - id: fix-year-expand
    content: 修改 ReportGenerateEngine.java：在 text 分支写入 textValues 前调用新增的 expandYearValue 方法，将 2 位数字 B2 字段值扩展为"20XX年"格式
    status: completed
---

## 用户需求

修复正向引擎（`ReportGenerateEngine.java`）的年度字段替换逻辑：当占位符名称以 `-B2` 结尾（年度字段），且其从 Excel 提取的原始值为 2 位数字（如 `"24"`）时，在写入 `textValues` 前应将其扩展为 `"20XX年"` 格式（如 `"24"` → `"2024年"`），而非直接将原始 2 位数写入。

## 问题现象

子模板中 `{{清单模板-数据表-B2}}` 被替换为 `24`，导致报告正文出现"24（截至2412月31日）"，正确结果应为"2024年（截至2024年12月31日）"。

## 核心功能

- 在正向引擎 `text` 类型值写入 `textValues` 时，对名称以 `-B2` 结尾的占位符做年度扩展判断
- 判断条件：原始值非空且完全由 2 位数字组成（`^\d{2}）
- 扩展规则：`"24"` → `"2024年"`（前缀拼接 `"20"`，后缀追加 `"年"`）
- 其他类型占位符、非 2 位数字的 B2 值保持原有逻辑不变

## 技术栈

与现有项目一致：Java + Spring Boot，Apache POI，EasyExcel，Lombok。

## 实现思路

在 `ReportGenerateEngine.java` 第 88-90 行的 `text` 类型分支中，`extractTextValue` 取得原始值后、`put` 进 `textValues` 之前，插入一个私有辅助方法 `expandYearValue`，对年度字段做条件性扩展。逻辑集中在一处，不改动调用方，影响范围最小。

参考 `ReverseTemplateEngine.java` 中 `buildYearVariants` 的命名和实现风格，但正向引擎只需一个确定的替换结果（`"20XX年"`），无需返回变体列表。

## 实现细节

### 修改点（仅 1 处）

**文件**：`src/main/java/com/fileproc/report/service/ReportGenerateEngine.java`

**第 88-90 行**，将：

```java
if ("text".equals(ph.getType())) {
    String value = extractTextValue(rows, ph.getSourceSheet(), ph.getSourceField());
    textValues.put(ph.getName(), value != null ? value : "");
}
```

修改为：

```java
if ("text".equals(ph.getType())) {
    String value = extractTextValue(rows, ph.getSourceSheet(), ph.getSourceField());
    value = expandYearValue(ph.getName(), value);
    textValues.put(ph.getName(), value != null ? value : "");
}
```

### 新增私有方法（追加在文件末尾辅助方法区）

```java
/**
 * 年度字段扩展：占位符名以 "-B2" 结尾且值为 2 位数字时，
 * 将原始值扩展为 "20XX年" 格式（如 "24" → "2024年"）。
 */
private String expandYearValue(String placeholderName, String value) {
    if (placeholderName != null && placeholderName.endsWith("-B2")
            && value != null && value.matches("^\\d{2}$")) {
        return "20" + value + "年";
    }
    return value;
}
```

### 关键约束

- 判断条件为 `placeholderName.endsWith("-B2")` + `value.matches("^\\d{2}$")`，双重保险，不会误改其他字段
- 原始值为 `null` 时直接返回 `null`，后续的 `value != null ? value : ""` 兜底逻辑保持不变
- 不引入新依赖，不修改方法签名，不影响 `table`/`chart` 分支
- 与反向引擎的 `buildYearVariants` 逻辑保持风格一致，但独立实现，无耦合

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReportGenerateEngine.java   # [MODIFY] 第88-90行插入 expandYearValue 调用；末尾新增 expandYearValue 私有方法
```