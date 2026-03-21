---
name: year-multi-format-match
overview: 修复 replaceWithWordBoundary 方法中后置边界错误（lookbehind→lookahead），使年度变体替换真正生效；同时对年度字段兜底"2024"变体增加保护，避免误替换数字串中的"2024"。
todos:
  - id: fix-word-boundary-regex
    content: 修复 ReverseTemplateEngine.java 第1121-1122行，将末尾 boundary 改为 lookahead suffix
    status: completed
---

## 用户需求

修复 `ReverseTemplateEngine.java` 中 `replaceWithWordBoundary` 方法的正则表达式错误，导致年度字段（如 B2="24" 展开为 "2024"、"2024年"、"2024年度"、"2024财年"）在 Word 报告中完全无法被替换为占位符标记的 Bug。

## 问题描述

当前第1121-1122行代码：

- `boundary` 变量被同时用于前缀和后缀
- 后缀位置使用了 `(?<!...)` lookbehind（负向后顾），而不是 `(?!...)` lookahead（负向先顾）
- 对于任何以数字结尾的值（"2024"、"2024年度"等年度变体），末尾 lookbehind 永远匹配失败，导致全部替换无效

## 修复目标

- 将后缀 boundary 从 lookbehind 改为 lookahead，使模式变为：`前缀_lookbehind + Pattern.quote(value) + 后缀_lookahead`
- 修复后，年度字段 B2="24" 展开的所有变体能正确被词边界替换为占位符 `{{清单模板-数据表-B2}}`
- 对原有中文词（公司名称、简称等）的词边界替换行为保持不变

## 核心功能

- 单文件单行逻辑修复：拆分 `boundary` 为 `prefix`（lookbehind）和 `suffix`（lookahead）两个独立变量
- 年度字段所有4种变体（财年/年度/年/纯数字）均可被正确替换
- 其他调用 `replaceWithWordBoundary` 的路径（短值词边界替换、公司名称等）行为正确

## 技术栈

- Java（Spring Boot 项目，已有代码），Apache POI（XWPFDocument）
- 正则表达式：`java.util.regex.Pattern` / `String.replaceAll`

## 实现方案

### 核心修改（第1121-1122行）

当前错误代码将同一个 lookbehind 字符串用于模式的两端：

```
String boundary = "(?<![\\u4e00-\\u9fa5A-Za-z0-9])";
String pattern = boundary + Pattern.quote(value) + boundary;
//                                                  ^^^^^^^^ 这里应该是 lookahead，写成了 lookbehind
```

正则引擎在 `Pattern.quote(value)` 匹配结束之后，检查 `(?<![...0-9])` 时，"当前位置的前一字符"正是 value 的最后一个字符（对于 "2024" 就是 '4'，属于数字），因此 lookbehind 断言失败，整个 pattern 不匹配，替换无效。

### 修复方案

将 `boundary` 拆分为：

- `prefix`：`(?<![\\u4e00-\\u9fa5A-Za-z0-9])` — 值前面不能是汉字/字母/数字（lookbehind，不变）
- `suffix`：`(?![\\u4e00-\\u9fa5A-Za-z0-9])` — 值后面不能是汉字/字母/数字（lookahead，修正）

```java
String prefix = "(?<![\\u4e00-\\u9fa5A-Za-z0-9])";
String suffix = "(?![\\u4e00-\\u9fa5A-Za-z0-9])";
String pattern = prefix + Pattern.quote(value) + suffix;
```

### 行为验证

| 文本 | 值 | 修复前 | 修复后 |
| --- | --- | --- | --- |
| `"2024"` | `"2024"` | 不替换 | 替换 |
| `"2024年度"` | `"2024年度"` | 不替换 | 替换 |
| `"2024年"` | `"2024年"` | 不替换 | 替换 |
| `"2024财年"` | `"2024财年"` | 不替换 | 替换 |
| `"松莉科技"` | `"松莉"` | 不替换（正确） | 不替换（正确，lookahead 阻断"科"） |
| `"（松莉）"` | `"松莉"` | 不替换（错误） | 替换（正确） |
| `"截至2024年12月31日"` | `"2024年"` | 不替换 | 替换（正确，年度字段应在任何位置替换） |


### 影响范围

`replaceWithWordBoundary` 被以下路径调用：

- 第1034行：年度字段变体替换（本次主要修复目标）
- 第1049行：DATA_CELL 短值词边界替换（中文公司名/简称等，修复后行为更正确）

两条路径均受益于本次修复，不存在副作用风险。

## 实现说明

- **最小化改动**：仅修改 `replaceWithWordBoundary` 方法内的2行代码，不涉及任何其他方法或逻辑
- **向后兼容**：方法签名、调用方式、日志格式全部保持不变
- **无性能影响**：正则编译开销与原来相同，仅改变字符类位置语义

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java  # [MODIFY] 修复 replaceWithWordBoundary 方法第1121-1122行
                                #   将 boundary 拆分为 prefix(lookbehind) + suffix(lookahead)
                                #   使年度字段及所有短值词边界替换能正确命中
```