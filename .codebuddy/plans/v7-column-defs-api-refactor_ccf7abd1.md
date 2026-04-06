---
name: v7-column-defs-api-refactor
overview: 将列定义接口从BVD专用改为通用化：接口路径由 /bvd-table-columns 改为 /{id}/column-defs，DTO 由 BvdColumnDef 改名为 ColumnDefItem，defaultSelected 字段改为 selected，并调整 buildBvdColumnDefs 方法逻辑使其从注册表条目动态读取 columnDefs 作为默认选中依据。
todos:
  - id: refactor-service
    content: 重构 PlaceholderRegistryService：ColumnDefItem 替代 BvdColumnDef，新增 getColumnDefItems 方法，删除 buildBvdColumnDefs
    status: completed
  - id: refactor-controller
    content: 重构 PlaceholderRegistryController：新增 GET /{id}/column-defs 接口，删除 /bvd-table-columns 接口，更新头部注释
    status: completed
    dependencies:
      - refactor-service
---

## 用户需求

将现有"BVD专属"的列配置接口通用化，使其适用于所有 `TABLE_ROW_TEMPLATE` 类型的占位符，同时修正默认选中列的来源逻辑。

## 产品概述

占位符注册表中 `TABLE_ROW_TEMPLATE` 类型的条目均携带 `columnDefs` 字段（定义要输出哪些列）。前端需要一个通用接口查询某个占位符条目的所有可选列及当前选中状态，并支持按企业保存自定义列选择。默认选中列不是硬编码的，而是来自该条目（企业级优先，系统级兜底）的 `columnDefs`。

## 核心功能

- **接口路径通用化**：`GET /bvd-table-columns?sheetName=` 改为 `GET /{id}/column-defs?companyId=`，以占位符 id 为入参，不再依赖 sheetName 硬判断
- **DTO 改名**：`BvdColumnDef` → `ColumnDefItem`，`defaultSelected` 字段 → `selected`
- **默认选中逻辑修正**：从注册表条目（企业级优先/系统级兜底）的 `columnDefs` 中读取已选列，不再硬编码 `["#","COMPANY"]`
- **Service 方法重构**：`buildBvdColumnDefs(sheetName, companyId)` 改为 `getColumnDefItems(registryId, companyId)`，通过 registryId 直接查条目，校验类型必须为 `TABLE_ROW_TEMPLATE`

## 技术栈

与现有项目完全一致：Spring Boot + MyBatis-Plus，Lombok `@Data`，Jackson，现有 `R<T>` 响应封装，`BizException` 异常处理。

## 实现方案

### 核心思路

改动范围仅限 `PlaceholderRegistryService.java` 和 `PlaceholderRegistryController.java` 两个文件，不涉及任何其他模块。

**Service 层**：

1. 将 `BvdColumnDef` 内部类改名为 `ColumnDefItem`，`defaultSelected` 字段改为 `selected`
2. 新增 `getColumnDefItems(String registryId, String companyId)` 方法，替代 `buildBvdColumnDefs`：

- 通过 `selectById(registryId)` 直接取系统级条目
- 校验 `phType == TABLE_ROW_TEMPLATE`，否则抛 400
- 从注册表条目的 `columnDefs`（JSON）解析已知可选列元数据（当前仍为 BVD SummaryYear 内置12列）
- 获取有效 `columnDefs`（企业级优先：`selectCompanyByName(companyId, name)` → 其 `columnDefs`；无企业级则用系统级条目的 `columnDefs`）
- 按有效 `columnDefs` 中是否包含该 fieldKey 设置 `selected`

3. 保留 `updateColumnDefs` 方法不变（逻辑无需修改）
4. 删除旧 `buildBvdColumnDefs` 方法

**Controller 层**：

1. 删除 `GET /bvd-table-columns` 接口
2. 新增 `GET /{id}/column-defs` 接口，调用 `getColumnDefItems(id, companyId)`
3. 返回类型改为 `PlaceholderRegistryService.ColumnDefItem`
4. 更新顶部注释文档

### 关键决策

- **可选列元数据来源**：当前仍内置 BVD SummaryYear 的12列定义（hardcoded `knownCols`），因为注册表条目本身不存储"所有可选列的元数据"（label/colIndex 等），只存储"已选中的列名"。这是合理的渐进式设计，后续可在注册表增加 `allColumnsMeta` 字段扩展。
- **不引入新的查询方法**：`selectCompanyByName` 已存在，直接复用取企业级条目的 `columnDefs`，比调用 `getEffectiveRegistry` 更精准（避免全量加载所有规则）。

## 实现注意事项

- `BvdColumnDef` 改名为 `ColumnDefItem` 后，需检查 `ReportGenerateEngine` 等其他文件是否有引用该类型（当前根据对话历史，引用仅在 Controller 和 Service 内部）
- `updateColumnDefs` 方法签名和逻辑不变，只影响 Service 内部 DTO 名称，无外部影响
- Controller 顶部注释中 `/bvd-table-columns` 行需同步更新为 `/{id}/column-defs`

## 目录结构

```
src/main/java/com/fileproc/registry/
├── controller/
│   └── PlaceholderRegistryController.java  [MODIFY]
│         - 删除 GET /bvd-table-columns 接口及其注释
│         - 新增 GET /{id}/column-defs?companyId= 接口，调用 getColumnDefItems
│         - 返回类型改为 PlaceholderRegistryService.ColumnDefItem
│         - 更新文件头部注释（接口路由表）
└── service/
    └── PlaceholderRegistryService.java     [MODIFY]
          - BvdColumnDef 类改名为 ColumnDefItem
          - defaultSelected 字段改为 selected（含 getter/setter/注释）
          - 新增 getColumnDefItems(String registryId, String companyId) 方法
          - 删除 buildBvdColumnDefs(String sheetName, String companyId) 方法
          - 更新 "方案C" 区域注释标题
```