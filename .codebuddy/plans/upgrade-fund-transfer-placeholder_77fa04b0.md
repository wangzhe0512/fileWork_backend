---
name: upgrade-fund-transfer-placeholder
overview: 将 `清单模板-资金融通` 升级为 TABLE_ROW_TEMPLATE，绑定 `资金融通` Sheet；`清单模板-公司间资金融通` 保留不动。
todos:
  - id: sql-v9-comment
    content: 注释 V9 SQL 第108行资金融通 INSERT，加废弃说明（→V26升级）
    status: completed
  - id: sql-v26-new
    content: 新建 V26__upgrade_fund_transfer_to_row_template.sql，UPDATE ph_type/sheet_name/title_keywords/column_defs/available_col_defs
    status: completed
    dependencies:
      - sql-v9-comment
  - id: update-reverse-java
    content: 更新 ReverseTemplateEngine.java 第284-285行：升级资金融通为TABLE_ROW_TEMPLATE，补充column_defs
    status: completed
  - id: add-extract-logic
    content: ReportGenerateEngine.java 新增资金融通路由分支及extractFundTransferData提取方法（合计行subtotal+没找到行跳过）
    status: completed
    dependencies:
      - update-reverse-java
---

## 用户需求

1. **`清单模板-资金融通`**：从 `TABLE_CLEAR_FULL` 升级为 `TABLE_ROW_TEMPLATE`，绑定 `sourceSheet=资金融通`，设置 `column_defs=["关联方","金额"]`
2. **`清单模板-公司间资金融通`**：保留不动，本次不处理

## Excel Sheet 结构（资金融通，4个测试文件一致）

- 行0：表头（关联方 | 金额）
- 行1-2：数据行（可能为空）
- 行3：`合计` 行（subtotal）
- 行4：`没找到`（特殊标记，跳过）

## 核心功能

- V9 SQL 第108行注释废弃，新建 V26 迁移脚本执行 UPDATE
- `ReverseTemplateEngine.java` 注册条目升级为 TABLE_ROW_TEMPLATE
- `ReportGenerateEngine.java` 新增 `资金融通` 路由分支及 `extractFundTransferData` 提取方法（含合计行 subtotal 识别、"没找到"行跳过逻辑）

## 技术栈

- Java Spring Boot（现有项目）
- Flyway SQL 迁移（V26）
- 现有 `extractLaborCostData` 方法作为实现参考模式

## 实现方案

### 数据提取逻辑（extractFundTransferData）

在 `extractLaborCostData` 基础上扩展：

- 行0：动态解析表头构建 colIdx→字段名 Map
- 行1起逐行处理：
- 第一列（关联方）值含"合计" → `_rowType=subtotal`
- 第一列值为"没找到" → `continue` 跳过
- 全空行 → `continue` 跳过
- 其余 → `_rowType=data`

### 迁移脚本列名（已验证）

| 字段 | 正确列名 |
| --- | --- |
| 类型 | `ph_type` |
| Sheet | `sheet_name` |
| 关键词 | `title_keywords` |
| 列定义 | `column_defs` / `available_col_defs` |


## 目录结构

```
src/main/resources/db/
├── V9__placeholder_registry_and_schema.sql   [MODIFY] 注释第108行资金融通INSERT
└── V26__upgrade_fund_transfer_to_row_template.sql  [NEW] UPDATE资金融通占位符字段

src/main/java/com/fileproc/report/service/
├── ReverseTemplateEngine.java  [MODIFY] 第284-285行升级资金融通注册条目
└── ReportGenerateEngine.java   [MODIFY] 新增资金融通路由分支+extractFundTransferData方法
```