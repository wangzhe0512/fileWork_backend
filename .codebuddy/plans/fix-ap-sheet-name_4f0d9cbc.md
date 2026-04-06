---
name: fix-ap-sheet-name
overview: 修复 AP_YEAR 和 AP_Lead_Sheet_YEAR 两个占位符的 source_sheet 名称错误，将 `AP` 改为 `AP YEAR`，`AP Lead Sheet` 改为 `AP Lead Sheet YEAR`。
todos:
  - id: fix-java-registry
    content: 修改 ReverseTemplateEngine.java 静态注册表第267~268行，AP sheet名补全 YEAR 后缀
    status: completed
  - id: fix-sql-and-migration
    content: 修改 V9 SQL 第125~126行，并新建 V12 Flyway 升级脚本修复已有数据库
    status: completed
    dependencies:
      - fix-java-registry
---

## 用户需求

修复 BVD 数据模板中 AP 相关占位符的 `source_sheet` 名称配置错误。

## 问题概述

注册表（代码内静态注册表 + SQL 初始化脚本）中，两个 AP 占位符的 `sourceSheet` 字段缺少  `YEAR` 后缀，导致报告填充引擎在 BVD 文件中找不到对应 sheet，取值失败。

| 占位符名 | 配置的 sourceSheet | 实际 sheet 名 |
| --- | --- | --- |
| BVD数据模板-AP_YEAR | `AP` | `AP YEAR` |
| BVD数据模板-AP_Lead_Sheet_YEAR-13-19 | `AP Lead Sheet` | `AP Lead Sheet YEAR` |


所有企业文件（标准模板、SPX、松莉、派智能源）sheet 名称完全一致，确认是注册表配置写错。

## 核心功能

- 修正 `ReverseTemplateEngine.java` 静态注册表中 2 行 sourceSheet 字符串
- 同步修正 `V9` SQL 初始化脚本中对应的 2 条记录
- 新增 `V12` Flyway 升级脚本，修复已部署数据库中的旧数据

## 技术栈

- **后端语言**：Java（Spring Boot）
- **数据库迁移**：Flyway（已有 V9~V11，本次新增 V12）

## 实现方案

### 核心思路

纯字符串配置修正，无逻辑变动。将两处注册表配置中错误的 sheet 名称 `AP` 和 `AP Lead Sheet` 补全为 `AP YEAR` 和 `AP Lead Sheet YEAR`，同时新建 V12 Flyway 脚本修复已有数据库存量数据。

### 关键设计决策

- **不修改 sourceField**：`A1`、`A13` 坐标值正确，无需改动
- **双轨修复**：同时修改 V9 源码（保障新环境初始化正确）+ 新增 V12 脚本（修复已部署环境），与前一个 fix（V11）模式完全一致

## 目录结构

```
src/
└── main/
    ├── java/com/fileproc/report/service/
    │   └── ReverseTemplateEngine.java         # [MODIFY] 第267~268行 sourceSheet 字段补全 YEAR 后缀
    └── resources/db/
        ├── V9__placeholder_registry_and_schema.sql  # [MODIFY] 第125~126行 source_sheet 值补全 YEAR 后缀
        └── V12__fix_ap_sheet_name.sql               # [NEW] Flyway升级脚本，UPDATE已有库中2条旧记录
```