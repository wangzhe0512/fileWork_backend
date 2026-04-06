---
name: upgrade-related-transaction-summary-to-row-template
overview: 将"清单模板-关联交易汇总表"占位符从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE，涉及 SQL 迁移（V23）、ReverseTemplateEngine 注册表项更新、ReportGenerateEngine 新增数据提取分支三处改动。
todos:
  - id: sql-v23
    content: 新建 V23__upgrade_related_transaction_summary_to_row_template.sql，升级关联交易汇总表占位符类型
    status: completed
  - id: reverse-and-report-engine
    content: 修改 ReverseTemplateEngine.java 注册条目并在 ReportGenerateEngine.java 新增提取方法和路由
    status: completed
    dependencies:
      - sql-v23
---

## 用户需求

将"清单模板-关联交易汇总表"占位符从 `TABLE_CLEAR_FULL` 升级为 `TABLE_ROW_TEMPLATE`，支持动态行数。

## 产品概述

关联交易汇总表的行数因企业不同而完全不同（有的只有关联采购+关联销售2行，有的包含接受/提供关联劳务、资金融通等5行以上），需要从固定行填值模式升级为动态行克隆模式。

## 核心特性

- Excel Sheet "关联交易汇总表"：行0为主表头，行1为副表头（A/B/C=A+B，需跳过），行2起为数据行
- 有效数据行判定：col0（关联交易类型）非空且不等于"合计"
- 列定义4列：关联交易类型、境外交易金额、境内交易金额、交易总额

## 技术栈

Spring Boot + Java，沿用现有 Flyway SQL 迁移 + ReverseTemplateEngine 注册表 + ReportGenerateEngine 数据提取的三层改动模式，与 V21（关联方个人信息）、V22（关联关系变化情况）完全一致。

## 实现方案

与上两次升级完全对标，新增一处差异：**行1副表头需跳过**（数据从 index=2 开始，而非 index=1），以及**col0 等于"合计"的汇总行需过滤**，不作为数据行克隆。

## 实现细节

- **SQL V23**：UPDATE `清单模板-关联交易汇总表`，`ph_type → TABLE_ROW_TEMPLATE`，`sheet_name = '关联交易汇总表'`，`column_defs/available_col_defs = ["关联交易类型","境外交易金额","境内交易金额","交易总额"]`，条件 `level='system' AND deleted=0`
- **ReverseTemplateEngine**：从 `TABLE_CLEAR_FULL` 区删除，移入 `TABLE_ROW_TEMPLATE` 分组（关联关系变化情况之前），补充 `sheetName` 和 `columnDefs/availableColDefs` 4列
- **ReportGenerateEngine**：`extractRowTemplateData` 在关联关系变化情况路由之后新增 `"关联交易汇总表"` 路由；新增 `extractRelatedTransactionSummaryData` 私有方法，行0解析表头，**从 index=2 开始**扫描数据行，col0 非空且 `!"合计".equals(col0)` 则为有效行

## 架构设计

完全复用现有 TABLE_ROW_TEMPLATE 体系，无架构变更。

## 目录结构

```
src/main/resources/db/
└── V23__upgrade_related_transaction_summary_to_row_template.sql  # [NEW] Flyway 迁移脚本

src/main/java/com/fileproc/report/service/
├── ReverseTemplateEngine.java  # [MODIFY] 第229-230行，将条目移入 TABLE_ROW_TEMPLATE 分组并补充列定义
└── ReportGenerateEngine.java   # [MODIFY] extractRowTemplateData 新增路由分支 + 新增 extractRelatedTransactionSummaryData 方法
```