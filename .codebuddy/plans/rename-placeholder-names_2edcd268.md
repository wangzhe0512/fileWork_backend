---
name: rename-placeholder-names
overview: 将6个占位符名称统一改名以对齐 Sheet 名命名规范，涉及 ReverseTemplateEngine.java、V9 SQL、新建 V24 迁移脚本三处改动。
todos:
  - id: create-v24-sql
    content: 新建 V24__rename_placeholder_names.sql，包含6条 UPDATE 迁移语句
    status: completed
  - id: modify-reverse-engine-and-v9
    content: 修改 ReverseTemplateEngine.java（6处名称）和 V9 建表 SQL（6处名称）
    status: completed
    dependencies:
      - create-v24-sql
---

## 用户需求

将注册表中6个命名不规范的占位符名称统一重命名，使其与 Excel Sheet 名命名规范对齐。

## 改名对照表

| 当前名称 | 新名称 |
| --- | --- |
| `清单模板-4_供应商关联采购明细` | `清单模板-4_供应商清单-关联采购明细` |
| `清单模板-5_客户关联销售明细` | `清单模板-5_客户清单-关联销售明细` |
| `清单模板-6_劳务支出明细` | `清单模板-6_劳务交易表-劳务支出明细` |
| `清单模板-6_劳务收入明细` | `清单模板-6_劳务交易表-劳务收入明细` |
| `清单模板-主要产品-A列中所列所有产品` | `清单模板-主要产品` |
| `清单数据模板-公司间资金融通交易总结` | `清单模板-公司间资金融通` |


## 核心功能

- **ReverseTemplateEngine.java**：修改运行时注册表的 6 个 `placeholder_name` key
- **V9 建表 SQL**：同步修改历史建表脚本保持可读性一致
- **新建 V24 迁移脚本**：对数据库存量数据执行 UPDATE，将旧名改为新名，确保生产数据库对齐

## 技术栈

现有 Java Spring Boot 项目，使用 Flyway 数据库版本管理（V9、V23 等递增版本号），无需引入新技术。

## 实现方案

### 策略

三处改动同步进行，确保运行时注册表（Java）、历史建表脚本（V9 SQL）、数据库存量数据（V24 迁移脚本）三者一致：

1. `ReverseTemplateEngine.java`：直接字符串替换6处 `placeholder_name`
2. `V9__placeholder_registry_and_schema.sql`：同步修改，保持代码可读性
3. 新建 `V24__rename_placeholder_names.sql`：6条 UPDATE 语句，带 `level='system' AND deleted=0` 条件

### 注意事项

- `ReportGenerateEngine.java` 按 `sheetName` 路由，不按 `placeholder_name` 路由，无需修改
- `PlaceholderRegistryService.java` 中若有按 `placeholder_name` 硬编码的查询分支，需一并检查
- V24 执行后，Java 侧与数据库侧同步更新，运行时无断层风险

## 目录结构

```
src/
├── main/
│   ├── java/com/fileproc/report/service/
│   │   └── ReverseTemplateEngine.java         # [MODIFY] 修改6处 placeholder_name 字符串
│   └── resources/db/
│       ├── V9__placeholder_registry_and_schema.sql   # [MODIFY] 同步修改历史建表脚本中的6处名称
│       └── V24__rename_placeholder_names.sql          # [NEW] 6条 UPDATE 语句，迁移数据库存量数据
```