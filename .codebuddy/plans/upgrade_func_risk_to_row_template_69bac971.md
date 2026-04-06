---
name: upgrade_func_risk_to_row_template
overview: 将"清单模板-功能风险汇总表"从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE，绑定 Sheet "功能风险汇总表"，4列（序号、风险、【清单模板-数据表B5】、关联方），无合计行。
todos:
  - id: comment-v9-and-create-v29
    content: 注释 V9 第112行并新建 V29 迁移脚本，UPDATE 功能风险汇总表占位符为 TABLE_ROW_TEMPLATE，4列定义
    status: completed
  - id: update-reverse-engine
    content: 更新 ReverseTemplateEngine.java 第294行，将功能风险汇总表条目升级为 TABLE_ROW_TEMPLATE 并补全列定义
    status: completed
    dependencies:
      - comment-v9-and-create-v29
  - id: add-extract-method
    content: 在 ReportGenerateEngine.java 新增功能风险汇总表路由分支和 extractFuncRiskData 提取方法
    status: completed
    dependencies:
      - update-reverse-engine
  - id: flyway-repair
    content: 执行 mvn flyway:repair 修复 V9 checksum，验证 V29 迁移就绪
    status: completed
    dependencies:
      - add-extract-method
---

## 用户需求

将占位符 `清单模板-功能风险汇总表` 从 `TABLE_CLEAR_FULL` 升级为 `TABLE_ROW_TEMPLATE`，使其支持行模板提取逻辑。

## 产品概述

"功能风险汇总表" Sheet 结构固定：单行表头（4列）+ 3条固定数据行（序号1/2/3），无合计行。所有测试文件结构完全一致。升级后系统能按行提取该 Sheet 数据，所有行标记为 `data`。

## 核心功能

- 注释 V9 SQL 第112行原始 INSERT，添加废弃说明（→V29）
- 新建 V29 Flyway 迁移脚本，UPDATE 数据库中该占位符为 TABLE_ROW_TEMPLATE，绑定4列定义
- 更新 `ReverseTemplateEngine.java` 中该条目的类型和列定义
- 在 `ReportGenerateEngine.java` 中新增路由分支和 `extractFuncRiskData` 提取方法
- 执行 `mvn flyway:repair` 修复 V9 checksum

## 技术栈

Java Spring Boot + Flyway（现有项目，复用已有升级模式）

## 实现方案

完全复用 V28（有形资产信息）单行表头升级模式，区别：无合计行，`_rowType` 全部为 `data`。

### Sheet 数据结构

- 行0（表头）：`["序号", "风险", "【清单模板-数据表B5】", "关联方"]`
- 行1~3（数据行）：序号1/2/3，_rowType=data
- "没找到"标记行跳过，全空行跳过

### 修改点（已精确确认）

| 文件 | 位置 | 变更 |
| --- | --- | --- |
| `V9__placeholder_registry_and_schema.sql` | 第112行 | 注释废弃，加 →V29 |
| `V29__upgrade_func_risk_to_row_template.sql` | 新建 | UPDATE 脚本，4列定义 |
| `ReverseTemplateEngine.java` | 第294~295行 | TABLE_CLEAR_FULL → TABLE_ROW_TEMPLATE，补全 sourceSheet+columnDefs |
| `ReportGenerateEngine.java` | 第406行后 | 新增功能风险汇总表路由 |
| `ReportGenerateEngine.java` | extractTangibleAssetData 后 | 新增 extractFuncRiskData 方法 |


## 目录结构

```
src/main/resources/db/
├── V9__placeholder_registry_and_schema.sql         # [MODIFY] 第112行注释废弃
└── V29__upgrade_func_risk_to_row_template.sql      # [NEW] UPDATE 脚本

src/main/java/com/fileproc/report/service/
├── ReverseTemplateEngine.java   # [MODIFY] 第294行条目升级
└── ReportGenerateEngine.java    # [MODIFY] 新增路由 + extractFuncRiskData 方法
```