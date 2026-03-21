---
name: reverse-engine-fix-b4-b5-b6
overview: 修复反向模板引擎中 B4/B5/B6 等企业简称/服务机构简称未被替换的问题，以及 mergeAllRunsInDocument 未处理页眉/页脚、年度字段残留、占位符格式错误等多个问题
todos:
  - id: fix-registry-typo
    content: 修正注册表第146行占位符名：将"模板清单-关联交易汇总表"改为"清单模板-关联交易汇总表"
    status: completed
  - id: fix-data-cell-short-value
    content: 重构 DATA_CELL 短值分支：非年度字段短值改用 text.replace 精确替换，去掉词边界正则
    status: completed
  - id: fix-year-variants
    content: 修复年度变体替换：末尾含汉字的变体（如"2024年"）用精确替换，仅裸数字变体保留词边界
    status: completed
    dependencies:
      - fix-data-cell-short-value
  - id: fix-merge-header-footer
    content: 在 mergeAllRunsInDocument 末尾补充页眉/页脚段落及表格的 Run 合并逻辑
    status: completed
---

## 用户需求

修复 `ReverseTemplateEngine.java` 中四处已明确定位的 Bug，使反向模板引擎能正确将历史报告中的实际数据全部替换为占位符标记。

## 产品概述

反向模板引擎读取历史报告 Word 文档 + 当年清单 Excel，将文档中出现的实际值（企业简称、年度、服务机构简称等）批量替换为 `{{占位符}}` 标记，用于生成企业子模板。当前存在约 200+ 处漏替换及注册表名称错误，需逐一修复。

## 核心 Bug 清单

1. **B4/B5/B6 简称完全未替换**：`立信税务`（4字）、`斯必克流体技术`（6字）、`斯必克投资`（5字）长度均 < `MEDIUM_VALUE_THRESHOLD=8`，走词边界正则，但这些简称在报告中前后几乎总是紧跟汉字（如"斯必克流体技术有限公司"），被 lookahead `(?![汉字])` 拦截，导致 200+ 处漏替换。
2. **年度字段（2024）约 30+ 处未替换**：`buildYearVariants` 生成的完整变体（"2024年"、"2024年度"等）末尾是汉字，也被 `replaceWithWordBoundary` 的 lookahead 拦截；只有裸数字 "2024" 才需要词边界保护。
3. **`mergeAllRunsInDocument` 未处理页眉/页脚**：页眉/页脚内的段落 Run 未合并，导致跨 Run 拆分的文本无法被完整匹配替换。
4. **注册表第146行占位符名前缀顺序颠倒**：`"模板清单-关联交易汇总表"` 应为 `"清单模板-关联交易汇总表"`，导致该占位符在 Word 中永远无法被定位。

## 技术栈

- 语言：Java（Spring Boot），Apache POI `XWPFDocument`
- 修改范围：单文件 `src/main/java/com/fileproc/report/service/ReverseTemplateEngine.java`

---

## 实现策略

### Bug 1 & 2：重构 DATA_CELL 短值替换分支

**根本原则**：词边界正则的唯一目的是防止短**纯数字**（如裸年份 "2024"）误替换更长数字串中的片段。对于中文简称，其天然语义边界由上下文语义决定，而非字符边界，不应使用词边界正则。

**修改逻辑**（第1068～1111行，DATA_CELL 分支）：

```
isYearFieldEntry(entry) → 年度分支（已存在）：
    变体列表中，末尾带汉字的变体（如"2024年"、"2024年度"）→ 精确替换（text.replace）
    末尾不带汉字的裸数字变体（"2024"）→ 词边界替换（replaceWithWordBoundary）

非年度字段，短值（< MEDIUM_VALUE_THRESHOLD）→ 直接精确替换（text.replace，去掉词边界）
非年度字段，长值（>= MEDIUM_VALUE_THRESHOLD）→ 保持不变（已是精确替换）
```

判断变体末尾是否为汉字的辅助条件：`variant.charAt(variant.length()-1)` 是否在 `\u4e00-\u9fa5` 范围内。

**性能**：`text.replace` 是 O(n) 线性扫描，无正则开销，且现有方法已有大量精确替换逻辑，此改动不引入额外开销。

### Bug 3：补充页眉/页脚的 Run 合并

在 `mergeAllRunsInDocument` 方法末尾追加：

```java
// 页眉
for (XWPFHeader header : doc.getHeaderList()) {
    for (XWPFParagraph p : header.getParagraphs()) mergeRunsInParagraph(p);
    for (XWPFTable t : header.getTables())
        t.getRows().forEach(r -> r.getTableCells().forEach(c -> c.getParagraphs().forEach(this::mergeRunsInParagraph)));
}
// 页脚
for (XWPFFooter footer : doc.getFooterList()) {
    for (XWPFParagraph p : footer.getParagraphs()) mergeRunsInParagraph(p);
    for (XWPFTable t : footer.getTables())
        t.getRows().forEach(r -> r.getTableCells().forEach(c -> c.getParagraphs().forEach(this::mergeRunsInParagraph)));
}
```

`XWPFDocument.getHeaderList()` / `getFooterList()` 是 POI 已有 API，无需引入新依赖。

### Bug 4：修正注册表第146行

```java
// 修改前
reg.add(new RegistryEntry("模板清单-关联交易汇总表", ...));
// 修改后
reg.add(new RegistryEntry("清单模板-关联交易汇总表", ...));
```

---

## 实现注意事项

1. **不得引入新的词边界逻辑**：`replaceWithWordBoundary` 方法本身保留，年度字段裸数字变体仍调用它；但其他调用点（短值非年度字段）全部改为 `text.replace`。
2. **日志级别保持一致**：修改后的精确替换路径使用 `log.debug`，与现有代码风格一致；不升级为 `info` 以避免生产日志噪声。
3. **`isYearVariantEndingWithChinese` 判断内联即可**，无需新建方法，避免过度抽象。
4. **向后兼容**：四处修改均属于 Bug 修复（修正错误行为），不涉及接口变更，无需 feature flag。
5. **`mergeAllRunsInDocument` 是包访问级（void，无 private）**，已有测试可能覆盖，需确认页眉/页脚追加不破坏原有测试路径。

---

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java   # [MODIFY] 修复以下四处：
    │  (1) 第1068~1111行 DATA_CELL分支：短值改用精确替换；年度变体末尾含汉字者改用精确替换
    │  (2) 第1070~1088行 年度分支：按变体末尾字符决定替换方式（精确 vs 词边界）
    │  (3) 第1918~1933行 mergeAllRunsInDocument：补充页眉/页脚段落和表格的Run合并
    │  (4) 第146行 注册表：将"模板清单-关联交易汇总表"改为"清单模板-关联交易汇总表"
```

## Agent Extensions

### SubAgent

- **code-explorer**
- 用途：在实现前对 `ReverseTemplateEngine.java` 全文进行精确扫描，确认所有受影响代码段的确切行号与上下文，防止多处修改相互干扰
- 预期结果：输出四处修改点的精确代码上下文，供实现阶段直接定位替换