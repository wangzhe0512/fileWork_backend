---
name: disable-vendor-list-placeholder
overview: 将 `清单模板-4_供应商清单`（TABLE_CLEAR_FULL）占位符注释掉（保留痕迹），涉及 V9 SQL 和 ReverseTemplateEngine.java 两处。
todos:
  - id: comment-sql
    content: 注释 V9__placeholder_registry_and_schema.sql 第91行供应商清单 INSERT，加废弃原因说明
    status: completed
  - id: comment-java
    content: 注释 ReverseTemplateEngine.java 第266-267行供应商清单注册条目，加废弃原因说明
    status: completed
---

## 用户需求

将 `清单模板-4_供应商清单` 占位符标记删除（注释方式，保留痕迹）。

## 背景

该占位符类型为 `TABLE_CLEAR_FULL`，`sourceSheet=null`，当前报告生成引擎对其不填充任何数据，实际无任何效果。采用注释方式而非硬删除，便于未来恢复。

## 核心操作

- 注释 `V9__placeholder_registry_and_schema.sql` 第91行的 INSERT 语句
- 注释 `ReverseTemplateEngine.java` 第266-267行的注册条目
- 两处均加上废弃说明注释，说明废弃原因

> 注意：V9 SQL 已在数据库中应用过，注释 INSERT 不影响已有数据库记录；ReverseTemplateEngine 注册表注释后，运行时不再匹配该占位符，逆向引擎不会为 Word 表格打上该占位符标记，即可达到失效目的。

## 技术方案

### 修改策略

采用代码注释方式，两处修改均保留原始代码并加废弃说明，不引入任何新文件或迁移脚本。

### SQL 结构分析

第90行末尾有 `UNION ALL`，第91行末尾也有 `UNION ALL`。将第91行注释掉后：

- 第90行的 `UNION ALL` 需要从第90行末尾移除（因为后续 INSERT 被注释，`UNION ALL` 悬空会报语法错误）

**正确处理方式**：将第90行末尾的 `UNION ALL` 去掉，并注释第91行，同时在第93行的下一个 SELECT 前确保 `UNION ALL` 存在

实际上查看结构：

```
第90行: SELECT ... 200, 1, 0 UNION ALL
第91行: SELECT ... 210, 1, 0 UNION ALL   ← 注释掉此行
第92行: (空行)
第93行: -- TABLE_ROW_TEMPLATE 注释
第94行: SELECT ... (下一条数据)
```

第91行末尾的 `UNION ALL` 连接着后续 SELECT，注释掉第91行后，第90行末尾的 `UNION ALL` 直接连接到第94行 SELECT，SQL 结构仍然正确。

### 数据库已有记录处理

本次仅注释代码，不写迁移脚本，数据库中已有记录维持原样。ReverseTemplateEngine 注册表删除该条目后，运行时不再识别和打标，效果等同于禁用。

## 目录结构

```
src/
├── main/
│   ├── java/com/fileproc/report/service/
│   │   └── ReverseTemplateEngine.java                  [MODIFY] 注释第266-267行注册条目
│   └── resources/db/
│       └── V9__placeholder_registry_and_schema.sql     [MODIFY] 注释第91行 INSERT
```