---
name: forward-engine-header-footer-replace
overview: 正向引擎生成报告时，补充对页眉/页脚中文本占位符的替换，解决页眉占位符未被替换的问题
todos:
  - id: fix-header-footer-replace
    content: 修改 ReportGenerateEngine.java：第120行后追加调用，并在 replaceTextInTables 方法后新增 replaceHeaderFooterPlaceholders 私有方法
    status: completed
---

## 用户需求

正向引擎生成的报告中，页眉内的占位符（如 `{{清单模板-数据表-B1}}`、`{{清单模板-数据表-B2}}`）未被替换为实际数据，需要修复正向引擎使其在生成报告时同步处理页眉和页脚区域的占位符替换。

## 产品概述

文件处理平台的报告生成模块，通过正向引擎将企业子模板中的占位符替换为清单数据，生成最终报告文档。当前正向引擎仅处理正文段落和正文表格，页眉/页脚区域被遗漏。

## 核心功能

- 正向引擎生成报告时，对页眉（XWPFHeader）中的段落和表格单元格执行占位符替换
- 正向引擎生成报告时，对页脚（XWPFFooter）中的段落和表格单元格执行占位符替换
- 与现有正文替换逻辑保持完全一致，复用 `replacePlaceholdersInParagraph` 方法

## 技术栈

与现有项目一致：Java + Spring Boot，Apache POI（XWPFDocument/XWPFHeader/XWPFFooter），Lombok。

## 实现思路

在 `ReportGenerateEngine.java` 中新增一个私有方法 `replaceHeaderFooterPlaceholders`，遍历文档的所有页眉和页脚，对其中的段落和表格单元格调用现有的 `replacePlaceholdersInParagraph` 方法；然后在主流程第120行 `replaceTextInTables` 调用后追加该方法的调用。

改动极小，不引入新依赖，不修改任何现有方法。

## 实现细节

### 修改点1：主流程追加调用（第120行后）

```java
replaceTextInTables(doc, textValues);
replaceHeaderFooterPlaceholders(doc, textValues);  // 新增
```

### 修改点2：新增私有方法（insertAfter replaceTextInTables，第342行后）

```java
/**
 * 替换页眉/页脚中的文本占位符，覆盖段落和表格单元格。
 * 参考反向引擎 ReverseTemplateEngine 第910-945行的处理结构。
 */
private void replaceHeaderFooterPlaceholders(XWPFDocument doc, Map<String, String> textValues) {
    for (XWPFHeader header : doc.getHeaderList()) {
        for (XWPFParagraph paragraph : header.getParagraphs()) {
            replacePlaceholdersInParagraph(paragraph, textValues);
        }
        for (XWPFTable table : header.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph paragraph : cell.getParagraphs()) {
                        replacePlaceholdersInParagraph(paragraph, textValues);
                    }
                }
            }
        }
    }
    for (XWPFFooter footer : doc.getFooterList()) {
        for (XWPFParagraph paragraph : footer.getParagraphs()) {
            replacePlaceholdersInParagraph(paragraph, textValues);
        }
        for (XWPFTable table : footer.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph paragraph : cell.getParagraphs()) {
                        replacePlaceholdersInParagraph(paragraph, textValues);
                    }
                }
            }
        }
    }
}
```

### 关键约束

- 不引入新依赖，`XWPFHeader`/`XWPFFooter` 已在反向引擎中使用，Apache POI 已有该类
- 完全复用 `replacePlaceholdersInParagraph`，替换逻辑与正文保持一致
- 仅新增1个方法调用 + 1个私有方法，影响范围最小

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReportGenerateEngine.java   # [MODIFY] 第120行后追加 replaceHeaderFooterPlaceholders 调用；第342行后新增同名私有方法
```