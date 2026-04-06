---
name: upgrade-fund-transfer-placeholder
overview: 将 `清单模板-资金融通` 升级为 TABLE_ROW_TEMPLATE，绑定 `资金融通` Sheet；将 `清单模板-公司间资金融通` 注释删除。
todos:
  - id: sql-v9-comment
    content: 注释 V9 SQL 第108行(资金融通)和第113行(公司间资金融通) INSERT，修复语法链
    status: pending
  - id: sql-v26-new
    content: 新建 V26__upgrade_fund_transfer_to_row_template.sql，UPDATE资金融通字段并软删除公司间资金融通
    status: pending
    dependencies:
      - sql-v9-comment
  - id: update-reverse-java
    content: 更新 ReverseTemplateEngine.java：升级资金融通注册条目，注释公司间资金融通条目
    status: pending
  - id: add-extract-logic
    content: ReportGenerateEngine.java 新增资金融通路由分支及 extractFundTransferData 提取方法
    status: pending
    dependencies:
      - update-reverse-java
---

## 用户需求

1. **`清单模板-资金融通`**：从 `TABLE_CLEAR_FULL` 升级为 `TABLE_ROW_TEMPLATE`，绑定 `sheet_name=资金融通`，设置 `column_defs=["关联方","金额"]`，并新增对应的 Excel 数据提取逻辑

2. **`清单模板-公司间资金融通`**：标记删除（注释掉代码和 SQL，保留痕迹）

## Excel Sheet 结构（`资金融通` Sheet）

- 行0（index=0）：表头（关联方 | 金额）
- 行1起：数据行
- 遇到"合计"行 → 输出为 `_rowType=subtotal`
- 遇到"没找到"或空行 → 跳过/停止

## 核心功能

- V9 SQL 注释两处旧 INSERT（资金融通和公司间资金融通）
- 新建 V26 迁移脚本：UPDATE 资金融通占位符字段，软删除公司间资金融通
- `ReverseTemplateEngine.java`：升级资金融通注册条目，注释公司间资金融通
- `ReportGenerateEngine.java`：新增 `资金融通` Sheet 路由分支 + `extractFundTransferData` 提取方法

## 技术栈

- Java Spring Boot（现有项目）
- Flyway 迁移脚本（SQL）
- 现有 `extractLaborCostData` 方法作为参考模式

## 实现思路

与 V25 劳务成本归集升级完全对称：

1. V9 注释两行旧 INSERT（语法链不断）
2. 新建 V26 SQL：UPDATE 资金融通 + 软删除公司间资金融通（`deleted=1`）
3. Java 层同步更新注册表 + 新增提取方法

## 提取逻辑要点

`extractFundTransferData` 比 `extractLaborCostData` 多一个规则：

- 遇到关联方列值为 `"合计"` 或包含 `"合计"` → `_rowType=subtotal`
- 遇到值为 `"没找到"` → 直接 `continue`（跳过该行，不停止，继续读后续空行）
- 全空行 → `continue`

结构极简，无分组，无 break 终止。

## 关键细节

- `sheet_name` 列名（非 `source_sheet`）、`ph_type`（非 `placeholder_type`）、`title_keywords`（非 `match_keywords`）— 与 V25 修正保持一致
- 软删除公司间资金融通：`SET deleted=1 WHERE placeholder_name='清单模板-公司间资金融通'`
- V9 注释第113行后，第112行末尾 `UNION ALL` 直接连接第116行 `SELECT`（行业情况 B1），SQL 链正常

## 目录结构

```
src/
├── main/
│   ├── resources/db/
│   │   ├── V9__placeholder_registry_and_schema.sql          [MODIFY] 注释第108行(资金融通)和第113行(公司间资金融通) INSERT
│   │   └── V26__upgrade_fund_transfer_to_row_template.sql   [NEW]    UPDATE资金融通 + 软删除公司间资金融通
│   └── java/com/fileproc/report/service/
│       ├── ReverseTemplateEngine.java                        [MODIFY] 第284-285行升级，第294-295行注释
│       └── ReportGenerateEngine.java                         [MODIFY] 新增路由分支 + extractFundTransferData方法
```