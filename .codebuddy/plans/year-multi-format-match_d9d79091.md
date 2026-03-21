---
name: year-multi-format-match
overview: 在反向引擎 DATA_CELL 分支中，对年度字段（B2）新增多格式扩展匹配逻辑：将清单中的2位年份（如"24"）自动派生出完整年份变体（"2024"、"2024年"、"2024年度"），并用词边界替换全部写入同一占位符。
todos:
  - id: fix-year-field-matching
    content: 修改 ReverseTemplateEngine.java：新增 isYearFieldEntry/buildYearVariants 方法，修复年度字段前置守卫及DATA_CELL替换分支
    status: completed
---

## 用户需求

修复"年度字段"多格式匹配替换问题：清单Excel的数据表B2单元格存储的年度值为2位短数字（如"24"），但报告正文中实际出现的是完整年份形式，如"2024"、"2024年"、"2024年度"、"2024财年"等。当前引擎以"24"直接做子串匹配会产生大量误替换（如"第24条"、"24%"等无关数字），且无法将其替换为正文中真正的年份表达式。

## 产品概述

在反向引擎（`ReverseTemplateEngine`）的 `DATA_CELL` 替换分支中，针对占位符名为 `清单模板-数据表-B2`（年度字段）、且值为2位纯数字的情况，自动扩展出"20xx年份变体列表"（`2024`、`2024年`、`2024年度`、`2024财年`），并依次对这些变体执行词边界正则替换，完全跳过原始2位数字的直接替换，从而精准匹配报告中各种年份表达形式。

## 核心功能

- **年度字段识别**：在 `DATA_CELL` 分支，通过占位符名 `清单模板-数据表-B2` + 值为2位纯数字双重条件识别年度字段
- **变体生成**：新增私有方法 `buildYearVariants(String value)` 将"24"扩展为 `["2024", "2024年", "2024年度", "2024财年"]`
- **绕过前置守卫**：将第997行的 `!text.contains(value)` 守卫改为：对年度字段额外检查 text 是否包含任一年份变体，满足任意一个则不跳过
- **逐变体词边界替换**：对生成的变体列表逐一调用 `replaceWithWordBoundary`，任意一个变体替换成功即记录为 `confirmed` 状态

## 技术栈

- 语言：Java（Spring Boot 项目，已有代码结构）
- 核心改动文件：`src/main/java/com/fileproc/report/service/ReverseTemplateEngine.java`
- 依赖：已有 `Pattern`、`Matcher`、`replaceWithWordBoundary` 方法，无需引入新依赖

## 实现方案

### 改动策略

在最小侵入原则下，仅修改 `ReverseTemplateEngine.java` 一个文件，在2处目标位置做精准手术式修改：

**改动点1 — 前置守卫放行（第995~997行）**

将原有固定的 `if (!text.contains(value)) continue;` 改为：

```java
// 原逻辑
if (!text.contains(value)) continue;

// 改为
if (!text.contains(value)) {
    // 特例：年度字段（2位数字）允许通过变体检测
    if (!isYearFieldEntry(entry)) continue;
    List<String> yearVariants = buildYearVariants(value);
    boolean anyVariantPresent = yearVariants.stream().anyMatch(text::contains);
    if (!anyVariantPresent) continue;
}
```

**改动点2 — DATA_CELL 替换分支（第1020~1044行）**

在 `DATA_CELL` 分支的入口处，检测是否为年度字段，若是则走变体替换逻辑，否则沿用原有短值词边界/长值精确替换逻辑：

```java
} else if (pType == PlaceholderType.DATA_CELL) {
    // 年度字段特殊处理
    if (isYearFieldEntry(entry)) {
        List<String> yearVariants = buildYearVariants(value);
        boolean replaced = false;
        for (String variant : yearVariants) {
            if (!text.contains(variant)) continue;
            String newText = replaceWithWordBoundary(text, variant, phMark);
            if (!newText.equals(text)) {
                text = newText;
                runModified = true;
                if (!replaced) {
                    count++;
                    replaced = true;
                    addMatchedRecord(matchedList, entry, variant, originalText, location,
                            "confirmed", paragraphIndex, tableIndex, rowIndex, cellIndex);
                }
                log.debug("[ReverseEngine] 年度变体替换[{}]: '{}' -> {}", entry.getDisplayName(), variant, phMark);
            }
        }
    } else if (value.length() < MEDIUM_VALUE_THRESHOLD) {
        // 原短值词边界逻辑不变
        ...
    } else {
        // 原长值精确替换逻辑不变
        ...
    }
}
```

**新增辅助方法**

```java
/** 判断是否为年度字段（占位符名 = 清单模板-数据表-B2 且值为2位纯数字） */
private boolean isYearFieldEntry(ExcelEntry entry) {
    return "清单模板-数据表-B2".equals(entry.getPlaceholderName())
            && entry.getValue() != null
            && entry.getValue().matches("\\d{2}");
}

/** 根据2位年份缩写构建完整年份变体列表，按最长优先排序防止部分替换干扰 */
private List<String> buildYearVariants(String twoDigitYear) {
    String fullYear = "20" + twoDigitYear;  // "24" → "2024"
    return List.of(
        fullYear + "财年",   // "2024财年" — 最长优先
        fullYear + "年度",   // "2024年度"
        fullYear + "年",     // "2024年"
        fullYear             // "2024"      — 兜底
    );
}
```

### 关键设计决策

1. **"最长优先"变体顺序**：`2024财年` → `2024年度` → `2024年` → `2024`，确保先替换更具体的形式，避免"2024年"先被替换后"2024年度"找不到匹配的问题
2. **词边界保护保留**：所有变体依然通过 `replaceWithWordBoundary` 替换，防止正文中出现 "20240101" 之类的日期被误替换
3. **count 计数唯一性**：同一个 entry 在一次 run 处理中，无论命中几个变体，`count++` 只执行一次（`replaced` flag 控制）
4. **守卫放行范围最小化**：`isYearFieldEntry` 双重条件约束，仅年度字段+2位数字值时才绕过守卫，不影响其他任何字段

## 实现说明

- **无回归风险**：改动仅在 `isYearFieldEntry` 返回 true 时生效，其余所有 `DATA_CELL`/`LONG_TEXT`/`BVD` 分支及所有其他占位符的行为完全不变
- **日志可观测**：新增 `[年度变体替换]` debug 日志，命中哪个变体一目了然，便于排查
- **边界情况**：若 B2 存储的是4位完整年份（如"2024"），`isYearFieldEntry` 返回 false，沿用原来的词边界替换，不受影响

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java   # [MODIFY] 修改3处：
                                 # 1. 第997行前置守卫增加年度字段变体放行
                                 # 2. 第1020行DATA_CELL分支头部增加年度字段判断及变体替换循环
                                 # 3. 新增 isYearFieldEntry() 和 buildYearVariants() 两个私有方法
```