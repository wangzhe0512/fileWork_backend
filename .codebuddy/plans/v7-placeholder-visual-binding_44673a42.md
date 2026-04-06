---
name: v7-placeholder-visual-binding
overview: 新增"占位符可视化自定义绑定"功能：在子模板在线编辑页面右侧数据源面板中，可视化展示清单/BVD文件的 Schema 树（Sheet/字段），并支持将文档占位符与数据源字段绑定，以及就地新建企业级注册表规则。
todos:
  - id: placeholder-binding-api
    content: 在 CompanyTemplatePlaceholderService 新增 listWithBindingStatus 和 updateBinding 方法，在 CompanyTemplateController 新增 binding-status 查询和 bind 绑定/解绑接口
    status: completed
  - id: atomic-create-bind-api
    content: 在 CompanyTemplateController 新增原子接口 create-registry-and-bind，注入 PlaceholderRegistryService，事务内完成注册表条目创建和占位符绑定
    status: completed
    dependencies:
      - placeholder-binding-api
  - id: registry-override-api
    content: 在 PlaceholderRegistryService 新增 overrideForCompany 方法，在 PlaceholderRegistryController 新增 override-for-company 接口
    status: completed
---

## 用户需求

在子模板在线编辑页面的右侧数据源面板中，实现占位符可视化自定义功能，具体包括四个层面：

## 产品概述

子模板编辑器右侧面板（原"占位符库"）升级为"数据源 + 占位符绑定"一体化面板：

- 上半区展示清单/BVD 文件的 Sheet → 字段树（系统自动解析，只读）
- 下半区展示当前子模板的占位符列表及其与数据源字段的绑定状态
- 支持就地将占位符绑定到数据源字段，并可就地新建企业级注册表规则，无需跳转其他页面

## 核心功能

1. **数据源 Schema 可视化**：调用已有 `GET /data-files/{id}/schema` 接口，在右侧面板树形展示 Sheet → 字段结构（字段名、类型标签、样本值），系统自动解析，前端只读展示

2. **占位符绑定状态查询**：提供新接口返回子模板全量占位符列表，每个占位符携带当前绑定的数据源字段（`sourceSheet`/`sourceField`）及绑定状态（已绑定/未绑定），供前端渲染绑定状态图示

3. **快速绑定操作**：用户在右侧字段节点上点击"绑定到占位符"，或在左侧占位符上选择字段完成绑定，调用专用 `bind` 接口（单次操作只更新绑定关系字段，不是全量 PUT），支持解绑

4. **就地新建企业级注册表规则并绑定**：当占位符在注册表中没有对应规则时，用户可在编辑器内通过右侧数据源字段直接新建一条企业级注册表条目，并原子性地完成注册表条目创建 + 占位符绑定两步操作

5. **企业级注册表覆盖**：提供"基于系统规则创建企业级覆盖"接口，让企业可在已有系统规则基础上创建个性化覆盖条目，而不是从零填写

## 技术栈

与现有项目完全一致：Spring Boot + MyBatis-Plus + MySQL 8.x，已有 `PlaceholderRegistryService`、`CompanyTemplatePlaceholderService`、`DataSourceSchemaService` 均可直接复用。

---

## 实现方案

### 整体策略

本次改动属于**接口层补全**，不涉及核心引擎修改：

- 现有 Schema 解析层、注册表 CRUD、占位符更新能力已全部就绪
- 核心工作是补 3 个新接口 + 1 个原子操作接口，复用现有 Service，不新增 Service 类
- 新接口挂载在现有 `CompanyTemplateController`（占位符相关）和 `PlaceholderRegistryController`（注册表相关）上，保持架构一致

### 关键设计决策

**1. 占位符绑定状态查询接口**
现有 `GET /{id}/placeholders` 已返回列表，但缺少"绑定状态"语义标注。新接口 `GET /{templateId}/placeholders/binding-status` 直接查 `company_template_placeholder`，在 Service 层聚合 `sourceSheet`/`sourceField` 是否有值得出 `bindingStatus`（bound/unbound），无需 JOIN 额外表，性能好。

**2. 专用绑定接口而非复用通用 PUT**
现有 `updateMetadata` 是全量字段更新，前端绑定操作只改 `sourceSheet`/`sourceField` 两个字段。专用 `PATCH /{templateId}/placeholders/{phId}/bind` 语义清晰、请求体轻量，防止前端误传其他字段覆盖已有数据。解绑时传 `null` 即可清空。

**3. 原子接口：新建注册表规则并绑定**
`POST /{templateId}/placeholders/{phId}/create-registry-and-bind` 在一个 `@Transactional` 方法内：先调 `PlaceholderRegistryService.saveEntry()` 创建企业级条目，再调 `CompanyTemplatePlaceholderService.updateMetadata()` 更新占位符绑定字段。两步在同一事务，任一失败全部回滚，无脏数据风险。接口挂在 `CompanyTemplateController`，注入两个已有 Service 即可。

**4. 企业级覆盖接口**
`POST /placeholder-registry/{id}/override-for-company` 挂在 `PlaceholderRegistryController`，Service 层读取系统级条目 → 复制所有字段 → 设 `level=company`/`companyId` → 调已有 `saveEntry()` 写库。`saveEntry()` 已有重复校验，同名企业级条目存在时返回 409，前端可提示"已有覆盖条目，是否去编辑"。

### 性能与可靠性

- 所有新接口均复用已有 Mapper，无新增 SQL 查询；绑定状态判断在内存完成，单次查询 O(n)
- 原子接口使用 Spring `@Transactional`，与现有事务模式一致
- 无缓存引入，保持与现有架构风格一致

---

## 架构设计

```
前端编辑器右侧面板
  ├── Schema 树（只读）
  │     └── GET /data-files/{id}/schema  →  DataSourceSchemaService（已有）
  │
  ├── 占位符绑定状态列表（新）
  │     └── GET /company-template/{id}/placeholders/binding-status
  │               └── CompanyTemplatePlaceholderService.listWithBindingStatus()  [新方法]
  │
  ├── 快速绑定/解绑（新）
  │     └── PATCH /company-template/{templateId}/placeholders/{phId}/bind
  │               └── CompanyTemplatePlaceholderService.updateBinding()  [新方法]
  │
  ├── 就地新建注册表规则并绑定（新，原子）
  │     └── POST /company-template/{templateId}/placeholders/{phId}/create-registry-and-bind
  │               └── [事务] PlaceholderRegistryService.saveEntry()
  │                       + CompanyTemplatePlaceholderService.updateBinding()
  │
  └── 企业级覆盖系统规则（新）
        └── POST /placeholder-registry/{id}/override-for-company?companyId=
                  └── PlaceholderRegistryService.overrideForCompany()  [新方法]
```

---

## 目录结构

```
src/main/java/com/fileproc/
├── template/
│   ├── controller/
│   │   └── CompanyTemplateController.java   [MODIFY]
│   │       新增3个接口：
│   │       - GET  /{id}/placeholders/binding-status  返回带绑定状态的占位符列表
│   │       - PATCH /{templateId}/placeholders/{phId}/bind  单独更新绑定字段（sourceSheet/sourceField）
│   │       - POST /{templateId}/placeholders/{phId}/create-registry-and-bind  原子：新建注册表条目+绑定占位符
│   │       注入 PlaceholderRegistryService（@Autowired，Controller 层已有注入先例）
│   │
│   └── service/
│       └── CompanyTemplatePlaceholderService.java  [MODIFY]
│           新增2个方法：
│           - listWithBindingStatus(templateId): 查询全量占位符，聚合绑定状态字段（bound/unbound）
│           - updateBinding(phId, templateId, sourceSheet, sourceField): 专用绑定/解绑方法，仅更新这两个字段
│           新增1个内部 DTO：
│           - PlaceholderBindingVO: 扩展 CompanyTemplatePlaceholder 加 bindingStatus 字段
│
├── registry/
│   ├── controller/
│   │   └── PlaceholderRegistryController.java  [MODIFY]
│   │       新增1个接口：
│   │       - POST /placeholder-registry/{id}/override-for-company?companyId=
│   │         基于指定系统级条目，为指定企业创建企业级覆盖条目
│   │
│   └── service/
│       └── PlaceholderRegistryService.java  [MODIFY]
│           新增1个方法：
│           - overrideForCompany(systemEntryId, companyId): 复制系统级条目→设level=company→调saveEntry()
│           已有重复校验（checkDuplicate）自动处理同名冲突，无需额外逻辑
```

---

## 关键接口定义

### 1. 带绑定状态的占位符列表

```
GET /api/company-template/{id}/placeholders/binding-status

响应：
[
  {
    "id": "ph-001",
    "placeholderName": "${companyName}",
    "name": "企业全称",
    "moduleId": "mod-001",
    "sourceSheet": "数据表",
    "sourceField": "企业全称",
    "bindingStatus": "bound",   // bound | unbound
    "type": "text",
    "status": "confirmed"
  }
]
```

### 2. 快速绑定/解绑

```
PATCH /api/company-template/{templateId}/placeholders/{phId}/bind

请求体：
{
  "sourceSheet": "数据表",    // null 表示解绑
  "sourceField": "企业全称"   // null 表示解绑
}
```

### 3. 原子：新建注册表规则并绑定（就地新建）

```
POST /api/company-template/{templateId}/placeholders/{phId}/create-registry-and-bind

请求体：
{
  "companyId": "company-001",
  "displayName": "自定义字段",
  "phType": "DATA_CELL",
  "dataSource": "list",
  "sheetName": "数据表",
  "cellAddress": "B15"
  // placeholderName 从 phId 对应的占位符记录自动读取，不由前端传入
}

响应：
{
  "registryEntry": { ...新建的注册表条目 },
  "placeholder":   { ...更新后的占位符记录 }
}
```

### 4. 企业级覆盖系统规则

```
POST /api/placeholder-registry/{id}/override-for-company?companyId=xxx

// {id} 为系统级条目ID，复制其所有字段创建企业级覆盖条目
// 响应：新建的企业级条目
```