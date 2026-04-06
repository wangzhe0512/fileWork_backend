---
name: upgrade-labor-cost-to-row-template
overview: 将 `清单模板-劳务成本费用归集` 从 TABLE_CLEAR_FULL 升级为 TABLE_ROW_TEMPLATE，重命名为 `清单模板-劳务成本归集`，并新增对应的 Excel 数据提取逻辑。
todos:
  - id: sql-v9-comment
    content: 注释 V9 SQL 第106行劳务成本费用归集 INSERT，加废弃原因说明
    status: completed
  - id: sql-v25-new
    content: 新建 V25__upgrade_labor_cost_to_row_template.sql，UPDATE placeholder_registry 更新名称、类型、sourceSheet、column_defs、关键词
    status: completed
    dependencies:
      - sql-v9-comment
  - id: update-reverse-engine
    content: 更新 ReverseTemplateEngine.java 第279-280行注册条目（类型/sourceSheet/column_defs/关键词）
    status: completed
  - id: add-extract-logic
    content: ReportGenerateEngine.java 新增劳务成本归集 Sheet 路由分支及 extractLaborCostData 提取方法
    status: completed
    dependencies:
      - update-reverse-engine
---

## 用户需求

将占位符 `清单模板-劳务成本费用归集` 升级改造：

- **重命名**：`清单模板-劳务成本费用归集` → `清单模板-劳务成本归集`
- **类型升级**：`TABLE_CLEAR_FULL`（无数据填充）→ `TABLE_ROW_TEMPLATE`（动态克隆行）
- **绑定 Sheet**：`劳务成本归集`（4个测试文件 Sheet 名完全一致）
- **column_defs**：`["劳务内容","分配方法","总成本费用","所需承担的比例"]`
- **匹配关键词**：`["劳务成本归集","成本归集","劳务成本费用归集"]`

## Excel Sheet 结构

`劳务成本归集` Sheet 结构极简：

- 行0（index=0）：表头行（劳务内容 | 分配方法 | 总成本费用 | 所需承担的比例）
- 行1起：纯数据行，无分组标题、无小计行、无 break 逻辑

## 核心功能

- 数据库迁移：V25 脚本更新 placeholder_registry 记录的名称、类型、sourceSheet、column_defs、关键词
- 逆向引擎：ReverseTemplateEngine 注册表更新，Word 中表标题含"劳务成本归集"等关键词的表格被正确识别并打 TABLE_ROW_TEMPLATE 标记
- 生成引擎：新增 `劳务成本归集` Sheet 的专用数据提取方法 `extractLaborCostData`，行0为表头动态解析列名，行1起逐行输出数据（全部标记为 `data` 类型）

## 技术栈

与现有项目完全一致：Spring Boot + MyBatis-Plus + Flyway 迁移 + EasyExcel 读取 + Java 服务层。

## 实现方案

### 数据库层

V9 SQL 第106行原始 INSERT 改为注释（保留历史痕迹，不影响运行，因为数据库已应用过 V9）。

新建 `V25__upgrade_labor_cost_to_row_template.sql`，执行 UPDATE 语句更新 placeholder_registry 表中该条记录的6个字段，与 V20-V23 迁移脚本模式一致。

### ReverseTemplateEngine

第279-280行注册条目全量更新：

- 类型改为 `TABLE_ROW_TEMPLATE`
- 新增 `sourceSheet = "劳务成本归集"`
- 新增 `defaultColDefs` 4列
- 更新关键词列表

注意：需确保该条目**在 TABLE_CLEAR_FULL 的劳务成本/资金融通等之前注册**，防止关键词被后续条目抢先匹配（现有代码注释已提示此机制）。

### ReportGenerateEngine

在 `extractRowTemplateData` 方法的 if-else 路由链中，**在供应商/客户通用路径之前**插入新分支：

```java
if ("劳务成本归集".equals(sheetName)) {
    return extractLaborCostData(rows);
}
```

新建 `extractLaborCostData` 方法：

- 行0（index=0）动态解析表头，构建 `colNameMap`（列索引→字段名）
- 行1起逐行扫描，跳过全空行，每行输出 `_row_type=data` 的字段 Map
- 字段名直接来自 colNameMap，与 column_defs 一一对应
- 复用现有 `toPlainString` 工具方法处理数值格式

该方法结构参考 `extractLaborServiceData` 的骨架，但逻辑更简单（无行次段划分、无分组/小计判断）。

## 目录结构

```
src/
├── main/
│   ├── java/com/fileproc/report/service/
│   │   ├── ReverseTemplateEngine.java          [MODIFY] 第279-280行：更新劳务成本归集注册条目（类型/sourceSheet/column_defs/关键词）
│   │   └── ReportGenerateEngine.java           [MODIFY] extractRowTemplateData 新增劳务成本归集分支；新增 extractLaborCostData 方法
│   └── resources/db/
│       ├── V9__placeholder_registry_and_schema.sql   [MODIFY] 第106行 INSERT 改注释，加废弃说明
│       └── V25__upgrade_labor_cost_to_row_template.sql  [NEW] UPDATE placeholder_registry 更新6个字段
```