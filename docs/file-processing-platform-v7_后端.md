# 行模板克隆方案开发计划 V7

> **方案名称：行模板克隆方案（ROW-CLONE）**
>
> 适用场景：报告 Word 模板中存在"一行模板 → 多行数据"的动态扩展表格，典型如 BVD SummaryYear 可比公司列表。后续凡提到"ROW-CLONE方案"或"行模板克隆方案"，均指本文档所描述的这套实现机制。

---

## 用户需求

### 产品背景

BVD 数据模板的 `SummaryYear` Sheet 中包含一张可比公司数据列表，行数因公司而异（少则十几行，多则几十行）。原有系统仅支持固定单元格地址占位符（`DATA_CELL`）和整块清空（`TABLE_CLEAR`），无法处理这种"按行动态克隆填充"的场景。

### 核心目标

1. 在 Word 报告模板中，识别"可比公司列表"所在表格，将其第一行数据行作为**行模板**
2. 根据 BVD 数据中实际的公司行数，克隆该行模板并逐行填充数据
3. 支持列头自动识别（扫描 Word 表格第0行）和企业级列定义配置（前端可自定义选取哪些列）
4. 统一纳入占位符注册表管理（`PlaceholderType.TABLE_ROW_TEMPLATE` 类型）

---

## 方案核心机制

### `TABLE_ROW_TEMPLATE` 占位符类型

与已有类型的对比：

| 类型 | cellAddress | 行为 |
|---|---|---|
| `DATA_CELL` | 必填（如 B1） | 单个单元格值填充 |
| `TABLE_CLEAR` | 无 | 按 titleKeywords 定位 Word 表格并清空数据行 |
| `TABLE_ROW_TEMPLATE` | **null** | 按 titleKeywords 定位 Word 表格，以第1行为模板克隆多行 |

注册表中该类型条目的特殊字段：
- `cellAddress`：**null**（不需要坐标）
- `titleKeywords`：JSON 数组，用于匹配 Word 表格标题行关键词
- `columnDefs`：JSON 数组，定义取哪些列数据（如 `["#","COMPANY","FY2023_STATUS"]`）

---

## 技术实现

### 1. 逆向引擎（`ReverseTemplateEngine`）

**静态注册表改造（V9 SQL 和 Java 硬编码）**

BVD SummaryYear 条目由原来的 `BVD` 类型 + `A1` 地址，改为：
```
type = TABLE_ROW_TEMPLATE
cellAddress = NULL
titleKeywords = ["可比公司列表","可比公司","Comparable Companies","Comparable Company"]
columnDefs = ["#","COMPANY"]   // 最小默认值，运行时从注册表动态覆盖
```

**`BVD_COLUMN_KEYWORD_MAP` 静态常量**

列头关键词 → fieldKey 的映射表（LinkedHashMap，保证匹配优先级）：

| Word 表头关键词（含） | fieldKey |
|---|---|
| `#`, `序号` | `#` |
| `company`, `公司` | `COMPANY` |
| `FY2023` + `status`/`状态` | `FY2023_STATUS` |
| `FY2023` + `revenue`/`营收` | `FY2023_REVENUE` |
| `FY2023` + `net profit`/`利润` | `FY2023_NET_PROFIT` |
| `FY2023` + `margin`/`利润率` | `FY2023_MARGIN` |
| `FY2022` + `status`/`状态` | `FY2022_STATUS` |
| `FY2022` + `revenue`/`营收` | `FY2022_REVENUE` |
| `FY2022` + `net profit`/`利润` | `FY2022_NET_PROFIT` |
| `FY2022` + `margin`/`利润率` | `FY2022_MARGIN` |
| `ncp`/`非可比` | `NCP_CURRENT` |
| `remark`/`备注` | `REMARK` |

**`buildBvdEntries` TABLE_ROW_TEMPLATE 分支**

生成纯元数据 `ExcelEntry`（无坐标），追加到结果列表末尾。逆向阶段只提取 Excel 数据，不实际操作 Word 表格。

**`inferColumnDefsFromWordTable`（新增方法）**

在 `clearTableBlock`（TABLE_ROW_TEMPLATE 分支）中调用：
1. 扫描 Word 表格第0行所有单元格文本
2. 对每个单元格按 `BVD_COLUMN_KEYWORD_MAP` 匹配 fieldKey
3. 成功 → 更新注册表条目的 columnDefs（仅内存，不写库）
4. 失败/表格未找到 → 使用注册表中已有的 columnDefs 兜底

### 2. 生成引擎（`ReportGenerateEngine`）

**新增路由条件**

在 `generate()` 主循环中，对占位符 `ph` 判断：
```java
if ("bvd".equals(ph.getDataSource()) && "SummaryYear".equals(ph.getSourceSheet())
    && PlaceholderType.TABLE_ROW_TEMPLATE.equals(ph.getType())) {
    // → 调用 extractSummaryYearRowData
}
```

**`getColumnDefsForPlaceholder`**

从 `PlaceholderRegistryService` 取该占位符的 columnDefs，兜底返回 `["#","COMPANY"]`。

**`extractSummaryYearRowData`**

从 BVD Excel 的 SummaryYear Sheet 提取多行数据：
- 跳过表头行（第0行）
- 遇到 `MIN/LQ/MED/UQ/MAX` 统计行时停止
- 过滤 B 列为空的行
- 按 columnDefs 指定的列顺序打包每行数据为 `Map<String, Object>`

**`buildSummaryYearFieldColMap`**

动态解析 SummaryYear 表头行，建立 fieldKey → 列索引映射。解析失败时使用内置默认映射（`#→0, COMPANY→1, FY2023_STATUS→2 ...`）。

### 3. 注册表 API（`PlaceholderRegistryController` + `PlaceholderRegistryService`）

新增两个接口，支持前端"方案C：企业级列选择"：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/placeholder-registry/bvd-table-columns?sheetName=SummaryYear&companyId=` | 返回 SummaryYear 所有可选列定义（12列），标注哪些已被企业选中 |
| POST | `/api/placeholder-registry/{id}/update-column-defs?companyId=` | 保存企业级列选择，body: `{"columnDefs":["#","COMPANY","NCP_CURRENT"]}` |

`BvdColumnDef` DTO（`PlaceholderRegistryService` 内部类）：

```java
public static class BvdColumnDef {
    String fieldKey;      // 字段标识，如 "COMPANY"
    String label;         // 展示名，如 "可比公司名称"
    int colIndex;         // 在 Excel 中对应的列索引
    boolean defaultSelected;  // 是否默认选中
}
```

`updateColumnDefs` 逻辑：按 `systemRegistryId + companyId` 查找是否已有企业级覆盖条目：
- 有 → UPDATE columnDefs
- 无 → 复制系统级条目，改 level=company，company_id=companyId，写入新行

### 4. 数据库迁移

**V9 SQL（修改已有行）**

```sql
-- SummaryYear 条目从 BVD 类型改为 TABLE_ROW_TEMPLATE
SELECT UUID(), 'system', 'BVD数据模板-SummaryYear-第一张表格',
  'BVD-SummaryYear可比公司列表', 'TABLE_ROW_TEMPLATE',
  'bvd', 'SummaryYear', NULL,
  '["可比公司列表","可比公司","Comparable Companies","Comparable Company"]',
  '["#","COMPANY"]', 460, 1, 0
```

**V13 SQL（新建，修复已部署数据库）**

UPDATE 脚本，更新已存在记录的以下字段：
- `ph_type = 'TABLE_ROW_TEMPLATE'`
- `display_name = 'BVD-SummaryYear可比公司列表'`
- `cell_address = NULL`
- `title_keywords = '["可比公司列表","可比公司","Comparable Companies","Comparable Company"]'`
- `column_defs = '["#","COMPANY"]'`

---

## 变更文件清单

```
src/main/java/com/fileproc/
├── report/service/
│   ├── ReverseTemplateEngine.java          [MODIFY]
│   │     - 新增 BVD_COLUMN_KEYWORD_MAP 静态常量
│   │     - buildBvdEntries 新增 TABLE_ROW_TEMPLATE 分支
│   │     - clearTableBlock 新增 TABLE_ROW_TEMPLATE 分支
│   │     - 新增 inferColumnDefsFromWordTable 方法
│   │     - V9 静态注册表对应条目改为 TABLE_ROW_TEMPLATE 类型
│   └── ReportGenerateEngine.java           [MODIFY]
│         - @Autowired(required=false) PlaceholderRegistryService
│         - generate() 新增 TABLE_ROW_TEMPLATE 路由分支
│         - 新增 getColumnDefsForPlaceholder 方法
│         - 新增 extractSummaryYearRowData 方法
│         - 新增 buildSummaryYearFieldColMap 方法
├── registry/
│   ├── controller/
│   │   └── PlaceholderRegistryController.java  [MODIFY]
│   │         - 新增 GET /bvd-table-columns 接口
│   │         - 新增 POST /{id}/update-column-defs 接口
│   └── service/
│       └── PlaceholderRegistryService.java     [MODIFY]
│             - 新增 buildBvdColumnDefs 方法
│             - 新增 updateColumnDefs 方法
│             - 新增内部 DTO BvdColumnDef
src/main/resources/db/
├── V9__placeholder_registry_and_schema.sql     [MODIFY] SummaryYear 条目类型改为 TABLE_ROW_TEMPLATE
└── V13__fix_summary_year_table_type.sql        [NEW]    UPDATE 已部署记录
```

---

## 数据流说明

### 逆向生成（反向引擎）

```
ReverseTemplateEngine.reverse(histPath, listPath, bvdPath, outPath, companyId)
  └── buildBvdEntries(bvdPath, name, registry)
        └── TABLE_ROW_TEMPLATE 条目 → 生成元数据 ExcelEntry（含 columnDefs）
  └── clearTableBlock(TABLE_ROW_TEMPLATE)
        └── inferColumnDefsFromWordTable(table, entry)
              ├── 扫描 Word 表头行 → 匹配 BVD_COLUMN_KEYWORD_MAP
              └── 失败 → 使用注册表 columnDefs 兜底
```

### 正向生成（报告生成引擎）

```
ReportGenerateEngine.generate(templateDocx, dataMap, companyId)
  └── 遍历占位符
        └── ph.type == TABLE_ROW_TEMPLATE && ph.dataSource == "bvd" && ph.sourceSheet == "SummaryYear"
              ├── getColumnDefsForPlaceholder(ph.name, companyId)
              │     └── PlaceholderRegistryService 取 columnDefs，兜底 ["#","COMPANY"]
              ├── extractSummaryYearRowData(rows, columnDefs)
              │     └── 跳过表头/统计行，过滤空行，按 columnDefs 打包数据
              └── 写入 Word 表格（克隆行模板，逐行填充）
```

### 企业级列配置（前端调用）

```
GET  /api/placeholder-registry/bvd-table-columns?sheetName=SummaryYear&companyId=xxx
  → 返回 12 列 BvdColumnDef 列表，标注 defaultSelected

POST /api/placeholder-registry/{id}/update-column-defs?companyId=xxx
  body: {"columnDefs":["#","COMPANY","FY2023_STATUS","NCP_CURRENT"]}
  → 查找或创建企业级覆盖条目，保存 columnDefs
```

---

## 方案扩展性说明

本方案（ROW-CLONE）设计为通用机制，可推广至其他动态行扩展表格：

1. **新增一个 TABLE_ROW_TEMPLATE 注册表条目**（配置 titleKeywords + columnDefs）
2. 在 `BVD_COLUMN_KEYWORD_MAP` 或对应数据源的 KeywordMap 中补充列头映射
3. 在 `ReportGenerateEngine` 中追加对应的数据提取方法和路由条件
4. 按需新建 Flyway 迁移脚本

无需修改注册表框架本身（V6 架构）。
