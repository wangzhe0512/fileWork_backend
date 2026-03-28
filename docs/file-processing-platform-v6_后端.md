# 反向引擎架构升级开发计划 V6

## 用户需求

### 产品概述

对现有报告生成系统进行架构升级，将 `ReverseTemplateEngine` 中硬编码的占位符注册表改造为"数据源结构化解析 + 数据库可配置注册表"双层架构，提升系统对不同企业模板格式的适应性。

### 核心功能

**1. 数据源结构化解析接口**

- 系统提供独立的解析接口，前端可按需触发对已上传 Excel（清单/BVD）的 Sheet 列表与字段结构解析，形成结构化数据源 Schema（包含 Sheet 名、字段坐标/名称、字段类型推断、样本值预览）
- 前端进入子模板编辑页面时，先查 `GET /data-files/{id}/schema`；无数据则调 `POST /data-files/{id}/parse-schema` 触发解析
- Schema 解析结果持久化，同一文件只解析一次（手动刷新除外），避免重复 IO 解析

**2. 占位符注册表数据库化**

- 将现有 `PLACEHOLDER_REGISTRY` 静态列表中的约40条规则迁移到数据库新表 `placeholder_registry`
- 支持两级注册表：**系统级**（`company_id=NULL`，所有企业共享默认规则）和**企业级**（`company_id=某企业ID`，特定企业覆盖或扩展，优先于系统级生效）
- 提供 CRUD 接口，支持管理员维护系统级注册表条目，支持企业级个性化配置
- `ReverseTemplateEngine.reverse()` 在运行时从数据库读取有效注册表，系统级规则保持向后兼容

**3. 两者联动**

- 新建占位符注册条目时，`sourceSheet` 和 `sourceField` 可通过 Schema 解析接口的下拉联动选择（后端提供校验）
- 反向引擎优先使用企业级注册表，回退到系统级注册表，行为与现有完全兼容

## 技术栈

与现有项目保持一致：Spring Boot + MyBatis-Plus + MySQL，Apache POI（xlsx 读取），EasyExcel（已用于清单解析），Flyway（版本化数据库迁移）。

---

## 实现方案

### 整体策略

分三个独立层次实现，层间松耦合：

1. **Schema 解析层**：新增 `DataSourceSchemaService`，解析 xlsx 文件结构并持久化到 `data_source_schema` 表，通过 `DataFileController` 挂载新接口
2. **注册表数据库化层**：新增 `placeholder_registry` 表 + `PlaceholderRegistryService`，迁移现有硬编码条目为系统级数据，提供 CRUD 接口
3. **引擎适配层**：修改 `ReverseTemplateEngine`，运行时从数据库读取注册表，逻辑不变，仅数据来源切换；保留静态列表作为 DB 不可用时的最终兜底

### 关键设计决策

**Schema 解析：按需触发 + 缓存策略**

文件上传时不自动触发解析，前端进入子模板编辑页面时先调 `GET /data-files/{id}/schema` 检查是否已有解析结果：有则直接使用；没有则自动调 `POST /data-files/{id}/parse-schema` 触发解析，等待返回后展示。解析结果持久化缓存，同一文件只解析一次（除非手动刷新）。解析结果按 `(dataFileId, sheetName)` 粒度存储到数据库，字段列表以 JSON 序列化存储（避免宽表）。

**注册表两级优先链：企业级 > 系统级**

`PlaceholderRegistryService.getEffectiveRegistry(companyId)` 合并两级规则：
- `company_id=NULL` 的条目为系统级，所有企业共享
- `company_id=某企业ID` 的条目为企业级，仅对该企业生效
- 同一 `placeholderName`，企业级覆盖系统级；企业级 `enabled=0` 则该规则被禁用
- `ReverseTemplateEngine` 在 `reverse()` 入口接收 `companyId` 参数，获取有效注册表替代静态列表；原无 companyId 场景（如测试/降级）回退静态列表

**向后兼容保障**

- 系统级注册表通过 Flyway V9 迁移脚本初始化（精确还原现有40条规则），不删除 Java 静态列表，作为 DB 查询异常时的兜底
- `reverse()` 方法签名新增重载（带 companyId），原4参数签名保留且默认使用静态列表，不破坏现有调用

---

## 实现注意事项

- **Schema 字段列表 JSON**：`fields` 列存储 `[{"address":"B1","label":"企业全称","sampleValue":"xxx","inferredType":"TEXT"}]`，使用 Jackson 序列化，避免引入额外依赖
- **注册表条目的 titleKeywords 和 columnDefs**：使用 JSON 列存储（`VARCHAR(1000)`），读取时反序列化为 `List<String>`，与 `RegistryEntry` 完全对应
- **引擎内部 RegistryEntry 类不改动逻辑**：`PlaceholderType` 和 `RegistryEntry` 改为 `public`，`PlaceholderRegistryService` 返回 `List<RegistryEntry>` 类型，引擎内部无需感知来源
- **Schema 解析复用引擎已有逻辑**：`DataSourceSchemaService` 内部独立实现 `readSheetNames` / `readSheetByIndex`（与引擎同逻辑），不跨包依赖
- **租户隔离**：企业级注册表条目通过 `tenantId + companyId` 双重隔离，系统级条目 `tenantId=NULL / companyId=NULL`

---

## 架构设计

```
DataFileController
  ├── POST /{id}/parse-schema  →  DataSourceSchemaService.parseSchema()  →  data_source_schema 表（持久化）
  └── GET  /{id}/schema        →  DataSourceSchemaService.getSchemaTree() →  从 DB 返回缓存

PlaceholderRegistryController
  ├── GET  /placeholder-registry?level=system|company&companyId=
  ├── GET  /placeholder-registry/effective?companyId=   →  PlaceholderRegistryService.getEffectiveRegistry()
  ├── POST /placeholder-registry
  ├── PUT  /placeholder-registry/{id}
  └── DELETE /placeholder-registry/{id}

CompanyTemplateController.reverse-generate
  └── ReverseTemplateEngine.reverse(histPath, listPath, bvdPath, outPath, companyId)
        ├── PlaceholderRegistryService.getEffectiveRegistry(companyId)  [企业级 > 系统级合并]
        ├── DB 异常 → 回退静态 PLACEHOLDER_REGISTRY
        └── reverseWithRegistry(...)  [使用动态注册表执行反向生成]
```

---

## 目录结构（变更文件）

```
src/main/java/com/fileproc/
├── datafile/
│   ├── controller/
│   │   └── DataFileController.java          [MODIFY] 新增 POST /{id}/parse-schema 和 GET /{id}/schema 两个接口
│   ├── entity/
│   │   └── DataSourceSchema.java            [NEW] 数据源 Schema 实体，对应 data_source_schema 表
│   │                                              字段：id/dataFileId/tenantId/sheetName/sheetIndex/fields(JSON)/parsedAt
│   ├── mapper/
│   │   └── DataSourceSchemaMapper.java      [NEW] MyBatis-Plus Mapper
│   │                                              selectByDataFileId / deleteByDataFileId / countByDataFileId
│   └── service/
│       └── DataSourceSchemaService.java     [NEW] 核心解析服务
│                                                 - parseSchema(dataFileId)：读取文件→逐Sheet解析→持久化（支持手动刷新，先删后写）
│                                                 - getSchemaTree(dataFileId)：查询已解析的 SheetNode 列表
│                                                 - hasSchema(dataFileId)：检查是否已有解析结果
│                                                 - 内置 FieldInfo / SheetNode DTO（public static class）
│                                                 - 解析策略：A-B键值对结构（数据表/行业情况）/ 首行表头结构（供应商清单/PL等）
│
├── registry/                                [NEW 包] 占位符注册表管理
│   ├── entity/
│   │   └── PlaceholderRegistry.java         [NEW] 注册表条目实体，对应 placeholder_registry 表
│   │                                              字段：id/tenantId/companyId/level/placeholderName/displayName
│   │                                                    phType/dataSource/sheetName/cellAddress
│   │                                                    titleKeywords(JSON)/columnDefs(JSON)/sort/enabled/deleted/createdAt/updatedAt
│   ├── mapper/
│   │   └── PlaceholderRegistryMapper.java   [NEW] 注册表 Mapper
│   │                                              selectSystemEntries() / selectCompanyEntries(companyId)
│   │                                              selectEffectiveEntries(companyId) / selectSystemByName(name)
│   │                                              selectCompanyByName(companyId, name)
│   ├── service/
│   │   └── PlaceholderRegistryService.java  [NEW] 核心注册表服务
│   │                                             - getEffectiveRegistry(companyId)：企业级优先，系统级兜底，返回 List<RegistryEntry>
│   │                                             - listSystemEntries() / listCompanyEntries(companyId)：分级查询
│   │                                             - saveEntry() / updateEntry() / deleteEntry()：CRUD（含校验、软删除）
│   │                                             - 企业级禁用规则：enabled=0 时从合并结果中移除该 placeholderName
│   └── controller/
│       └── PlaceholderRegistryController.java [NEW] REST 接口
│                                                   GET    /placeholder-registry?level=system|company&companyId=
│                                                   GET    /placeholder-registry/effective?companyId=
│                                                   POST   /placeholder-registry
│                                                   PUT    /placeholder-registry/{id}
│                                                   DELETE /placeholder-registry/{id}
│                                                   权限：list→registry:list，CRUD→registry:edit
│
├── report/service/
│   └── ReverseTemplateEngine.java           [MODIFY]
│                                                 - PlaceholderType 改为 public enum
│                                                 - RegistryEntry 改为 public static class
│                                                 - 注入 PlaceholderRegistryService（@Autowired required=false）
│                                                 - 新增 reverse(histPath,listPath,bvdPath,outPath,companyId) 5参数重载
│                                                   ├── 从 PlaceholderRegistryService.getEffectiveRegistry(companyId) 读取动态注册表
│                                                   ├── DB 异常或返回空 → 回退调用原4参数 reverse()
│                                                   └── 有动态注册表 → 调用 reverseWithRegistry()（内部私有方法）
│                                                 - 新增私有 reverseWithRegistry(...)：使用动态注册表执行完整反向生成流程
│                                                 - buildExcelEntries(path) → 委托 buildExcelEntries(path, registry)
│                                                 - buildBvdEntries(path, name) → 委托 buildBvdEntries(path, name, registry)
│                                                 - 原4参数 reverse() 和静态 PLACEHOLDER_REGISTRY 保留不变
│
└── template/controller/
    └── CompanyTemplateController.java       [MODIFY]
                                                  - reverse-generate 接口：降级引擎调用改为
                                                    reverseTemplateEngine.reverse(histPath, listPath, bvdPath, outPath, companyId)
                                                  - Schema 解析不在此处触发，由前端按需调用

src/main/resources/db/
└── V9__placeholder_registry_and_schema.sql  [NEW] Flyway 迁移脚本
                                                  - 创建 placeholder_registry 表（含索引）
                                                  - 创建 data_source_schema 表（含索引）
                                                  - INSERT 全部系统级默认条目（迁移现有40条硬编码规则）
```

---

## 关键数据结构

### `placeholder_registry` 表

| 列名 | 类型 | 说明 |
| --- | --- | --- |
| id | VARCHAR(36) PK | UUID |
| tenant_id | VARCHAR(36) | 企业级时填值，系统级为 NULL |
| company_id | VARCHAR(36) | 企业级时填值，系统级为 NULL |
| level | VARCHAR(20) | system / company |
| placeholder_name | VARCHAR(200) | 占位符标准名（系统级唯一，企业级 company_id+name 唯一） |
| display_name | VARCHAR(200) | 可读名称 |
| ph_type | VARCHAR(30) | DATA_CELL / TABLE_CLEAR / TABLE_CLEAR_FULL / TABLE_ROW_TEMPLATE / LONG_TEXT / BVD |
| data_source | VARCHAR(20) | list / bvd |
| sheet_name | VARCHAR(100) | 来源 Sheet 名 |
| cell_address | VARCHAR(20) | 单元格坐标如 B1 |
| title_keywords | VARCHAR(1000) | JSON 数组，TABLE_CLEAR 系列专用 |
| column_defs | VARCHAR(1000) | JSON 数组，TABLE_ROW_TEMPLATE 专用 |
| sort | INT | 排序，影响引擎处理顺序 |
| enabled | TINYINT | 0/1，企业级可禁用某条系统规则 |
| deleted | TINYINT | 软删除标记 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### `data_source_schema` 表

| 列名 | 类型 | 说明 |
| --- | --- | --- |
| id | VARCHAR(36) PK | UUID |
| data_file_id | VARCHAR(36) | 关联 data_file.id |
| tenant_id | VARCHAR(36) | 租户隔离 |
| sheet_name | VARCHAR(100) | Sheet 名称 |
| sheet_index | INT | Sheet 顺序（从0开始） |
| fields | TEXT | JSON 数组，每项含 address/label/sampleValue/inferredType |
| parsed_at | DATETIME | 解析时间 |

### `fields` JSON 结构示例

```json
[
  {"address": "B1", "label": "企业全称", "sampleValue": "斯必克流体技术（上海）有限公司", "inferredType": "TEXT"},
  {"address": "B2", "label": "企业简称", "sampleValue": "斯必克", "inferredType": "TEXT"},
  {"address": "B3", "label": "所属行业", "sampleValue": "制造业", "inferredType": "TEXT"},
  {"address": "B6", "label": "行业情况", "sampleValue": "本公司所处行业...", "inferredType": "LONG_TEXT"}
]
```

---

## 新增 REST 接口汇总

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/api/data-files/{id}/parse-schema` | 触发解析文件 Schema（结果持久化缓存） | `file:list` |
| GET | `/api/data-files/{id}/schema` | 查询文件 Schema（未解析返回空列表） | `file:list` |
| GET | `/api/placeholder-registry` | 查询注册表（`?level=system` 或 `?level=company&companyId=`） | `registry:list` |
| GET | `/api/placeholder-registry/effective` | 预览生效规则（企业级覆盖系统级后的最终列表，`?companyId=`） | `registry:list` |
| POST | `/api/placeholder-registry` | 新建注册表条目 | `registry:edit` |
| PUT | `/api/placeholder-registry/{id}` | 更新注册表条目（level/tenantId 不可修改） | `registry:edit` |
| DELETE | `/api/placeholder-registry/{id}` | 软删除注册表条目 | `registry:edit` |

---

## 运行时行为说明

### Schema 解析流程（前端视角）

```
进入子模板编辑页
  → GET /data-files/{id}/schema
      ├── 有数据（SheetNode 列表非空）→ 直接使用缓存展示
      └── 空列表 → POST /data-files/{id}/parse-schema
                    → 等待返回 → 展示 Sheet/字段树
```

### 反向生成注册表读取流程（引擎视角）

```
CompanyTemplateController.reverseGenerate(companyId)
  → reverseTemplateEngine.reverse(histPath, listPath, bvdPath, outPath, companyId)
      → PlaceholderRegistryService.getEffectiveRegistry(companyId)
          ├── 查询系统级条目（company_id IS NULL）
          ├── 查询企业级条目（company_id = companyId）
          ├── 合并：企业级覆盖同名系统级；enabled=0 则移除该规则
          └── 按 sort 排序，返回 List<RegistryEntry>
      ├── 成功 → reverseWithRegistry(动态注册表)  [完整反向生成流程]
      └── DB 异常 / 返回空 → 回退 reverse(histPath, listPath, bvdPath, outPath)  [静态列表]
```

---

## 已完成改造内容

### db-migration · V9 迁移脚本

- 新建 `placeholder_registry` 表（含 level/company_id/tenant_id 等字段及索引）
- 新建 `data_source_schema` 表（含 data_file_id/sheet_name/fields 等字段及索引）
- INSERT 全部系统级默认条目，精确还原 `PLACEHOLDER_REGISTRY` 静态列表中的40条规则

### schema-layer · Schema 解析层

- 新增 `DataSourceSchema` 实体（`@TableName("data_source_schema")`，`@TableId` UUID）
- 新增 `DataSourceSchemaMapper`（`selectByDataFileId` / `deleteByDataFileId` / `countByDataFileId`）
- 新增 `DataSourceSchemaService`（`parseSchema` / `getSchemaTree` / `hasSchema`，含 FieldInfo / SheetNode DTO）
  - Schema 解析策略：A-B键值对结构（数据表/行业情况）/ 首行表头结构（供应商清单/PL等）
  - 字段类型推断：TEXT / NUMBER / LONG_TEXT（按值长度和数字格式判断）
- 修改 `DataFileController`：新增 `POST /{id}/parse-schema`、`GET /{id}/schema` 接口

### registry-layer · 注册表数据库化层

- 新增 `PlaceholderRegistry` 实体（字段与 `placeholder_registry` 表完全对应）
- 新增 `PlaceholderRegistryMapper`（5个查询方法）
- 新增 `PlaceholderRegistryService`（`getEffectiveRegistry` / CRUD 方法，含重复校验、软删除）
- 新增 `PlaceholderRegistryController`（5个 REST 接口，权限 `registry:list` / `registry:edit`）

### engine-adapt · 引擎适配层

- `ReverseTemplateEngine` 修改：
  - `PlaceholderType` 改为 `public enum`
  - `RegistryEntry` 改为 `public static class`
  - 注入 `PlaceholderRegistryService`（`@Autowired(required=false)`，不影响原有启动）
  - 新增 `reverse(..., companyId)` 5参数重载（优先动态注册表，失败回退静态列表）
  - 新增私有 `reverseWithRegistry(...)` 方法（使用动态注册表的完整反向生成流程）
  - `buildExcelEntries` / `buildBvdEntries` 新增接受 `List<RegistryEntry>` 参数的重载，原方法委托新重载
  - 原4参数 `reverse()` 和 `PLACEHOLDER_REGISTRY` 静态列表完全保留

### controller-wire · 控制器接线

- `CompanyTemplateController.reverseGenerate`：降级引擎调用改为 `reverseTemplateEngine.reverse(..., companyId)`
