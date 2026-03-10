# 标准模板管理页面 - 后端接口需求文档

## 文档说明

本文档描述「标准模板管理」页面所需的后端接口，供后端开发人员参考实现。

---

## 1. 接口路径统一变更

所有 `system-template` 相关接口需要从 `/api/system-template/...` 迁移到 `/api/admin/system-template/...`，以统一超管后台接口前缀。

| 序号 | 接口名称 | 原路径 | 新路径 | 方法 |
|------|----------|--------|--------|------|
| 1 | 初始化模板 | `/api/system-template/init` | `/api/admin/system-template/init` | POST |
| 2 | 获取激活模板 | `/api/system-template/active` | `/api/admin/system-template/active` | GET |
| 3 | 模板列表 | `/api/system-template/list` | `/api/admin/system-template/list` | GET |
| 4 | 模块列表 | `/api/system-template/modules` | `/api/admin/system-template/modules` | GET |
| 5 | 占位符列表 | `/api/system-template/placeholders` | `/api/admin/system-template/placeholders` | GET |

---

## 2. 新增接口

### 2.1 获取模板详情

**接口地址**：`GET /api/admin/system-template/{id}`

**接口说明**：获取指定标准模板的详细信息

**请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|--------|------|------|------|------|
| id | string | path | 是 | 模板ID |

**响应示例**：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": "template-001",
    "name": "2024年度标准模板",
    "version": "v2.0",
    "wordFilePath": "/files/template.docx",
    "listExcelPath": "/files/list.xlsx",
    "bvdExcelPath": "/files/bvd.xlsx",
    "status": "active",
    "description": "2024年度标准报告模板",
    "createdAt": "2024-03-01T10:00:00Z",
    "updatedAt": "2024-03-08T15:30:00Z"
  },
  "ok": true
}
```

**字段说明**：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | string | 模板唯一标识 |
| name | string | 模板名称 |
| version | string | 版本号 |
| wordFilePath | string | Word模板文件路径 |
| listExcelPath | string | 清单Excel文件路径 |
| bvdExcelPath | string | BVD数据Excel文件路径 |
| status | string | 状态：active(激活)/inactive(未激活) |
| description | string | 模板描述 |
| createdAt | string | 创建时间 |
| updatedAt | string | 更新时间 |

---

### 2.2 设置激活模板

**接口地址**：`POST /api/admin/system-template/{id}/set-active`

**接口说明**：将指定的标准模板设置为当前激活状态（原激活模板自动变为未激活）

**请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|--------|------|------|------|------|
| id | string | path | 是 | 模板ID |

**响应示例**：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": "template-001",
    "name": "2024年度标准模板",
    "status": "active"
  },
  "ok": true
}
```

---

### 2.3 删除标准模板

**接口地址**：`DELETE /api/admin/system-template/{id}`

**接口说明**：删除指定的标准模板（逻辑删除，仅允许删除未激活的模板）

**请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|--------|------|------|------|------|
| id | string | path | 是 | 模板ID |

**响应示例**：

```json
{
  "code": 0,
  "message": "success",
  "data": null,
  "ok": true
}
```

**业务规则**：
- 只能删除状态为 `inactive`（未激活）的模板
- 当前激活的模板不允许删除
- 采用逻辑删除（软删除），将 `deleted` 字段标记为 1

---

## 3. 现有接口调整

### 3.1 获取模板列表

**接口地址**：`GET /api/admin/system-template/list`

**调整说明**：
- 路径变更为 `/api/admin/system-template/list`
- 返回所有历史模板列表（包括已激活和未激活的）
- 无需分页，返回全部数据

**响应示例**：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "id": "template-001",
      "name": "2024年度标准模板",
      "version": "v2.0",
      "status": "active",
      "createdAt": "2024-03-01T10:00:00Z",
      "updatedAt": "2024-03-08T15:30:00Z"
    },
    {
      "id": "template-002",
      "name": "2023年度标准模板",
      "version": "v1.0",
      "status": "inactive",
      "createdAt": "2023-03-01T10:00:00Z",
      "updatedAt": "2023-12-01T15:30:00Z"
    }
  ],
  "ok": true
}
```

---

### 3.2 获取模块列表

**接口地址**：`GET /api/admin/system-template/modules`

**调整说明**：
- 路径变更为 `/api/admin/system-template/modules`
- **参数名兼容**：同时支持 `templateId` 和 `systemTemplateId` 两个参数名

**请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|--------|------|------|------|------|
| templateId | string | query | 否 | 模板ID（前端使用）|
| systemTemplateId | string | query | 否 | 模板ID（文档原有）|

**注意**：两个参数至少传一个，优先使用 `templateId`

**响应示例**：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "id": "module-001",
      "systemTemplateId": "template-001",
      "name": "公司基本信息",
      "code": "company_info",
      "description": "包含公司名称、地址、联系方式等基本信息",
      "sort": 1,
      "deleted": 0
    },
    {
      "id": "module-002",
      "systemTemplateId": "template-001",
      "name": "财务数据",
      "code": "financial_data",
      "description": "收入、成本、利润等财务相关数据",
      "sort": 2,
      "deleted": 0
    }
  ],
  "ok": true
}
```

---

### 3.3 获取占位符列表

**接口地址**：`GET /api/admin/system-template/placeholders`

**调整说明**：
- 路径变更为 `/api/admin/system-template/placeholders`
- **新增参数**：支持按 `moduleId` 过滤占位符
- **参数名兼容**：同时支持 `templateId` 和 `systemTemplateId`

**请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|--------|------|------|------|------|
| templateId | string | query | 否 | 模板ID（前端使用）|
| systemTemplateId | string | query | 否 | 模板ID（文档原有）|
| moduleId | string | query | 否 | 模块ID（新增）|

**注意**：
- `templateId` 和 `systemTemplateId` 至少传一个
- 传入 `moduleId` 时，只返回该模块下的占位符
- 不传 `moduleId` 时，返回该模板下所有占位符

**响应示例**：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "id": "placeholder-001",
      "systemTemplateId": "template-001",
      "moduleCode": "company_info",
      "name": "${companyName}",
      "displayName": "公司名称",
      "type": "text",
      "dataSource": "list",
      "sourceSheet": "基本信息",
      "sourceField": "company_name",
      "chartType": null,
      "sort": 1,
      "description": "公司全称",
      "deleted": 0
    },
    {
      "id": "placeholder-002",
      "systemTemplateId": "template-001",
      "moduleCode": "financial_data",
      "name": "${revenueChart}",
      "displayName": "收入趋势图",
      "type": "chart",
      "dataSource": "bvd",
      "sourceSheet": "财务数据",
      "sourceField": "revenue",
      "chartType": "line",
      "sort": 2,
      "description": "年度收入趋势图表",
      "deleted": 0
    }
  ],
  "ok": true
}
```

---

## 4. 数据模型定义

### 4.1 SystemTemplate（标准模板）

```java
public class SystemTemplate {
    private String id;              // 模板ID
    private String name;            // 模板名称
    private String version;         // 版本号
    private String wordFilePath;    // Word文件路径
    private String listExcelPath;   // 清单Excel路径
    private String bvdExcelPath;    // BVD数据Excel路径
    private String status;          // 状态：active/inactive
    private String description;     // 描述
    private LocalDateTime createdAt; // 创建时间
    private LocalDateTime updatedAt; // 更新时间
    private Integer deleted;        // 删除标记：0-正常，1-已删除
}
```

### 4.2 SystemModule（模块）

```java
public class SystemModule {
    private String id;              // 模块ID
    private String systemTemplateId; // 所属模板ID
    private String name;            // 模块名称
    private String code;            // 模块代码
    private String description;     // 模块描述
    private Integer sort;           // 排序号
    private Integer deleted;        // 删除标记
}
```

### 4.3 SystemPlaceholder（占位符）

```java
public class SystemPlaceholder {
    private String id;              // 占位符ID
    private String systemTemplateId; // 所属模板ID
    private String moduleCode;      // 所属模块代码
    private String name;            // 占位符名称（如 ${companyName}）
    private String displayName;     // 显示名称
    private String type;            // 类型：text/table/chart/image
    private String dataSource;      // 数据源：list/bvd
    private String sourceSheet;     // 来源Sheet名称
    private String sourceField;     // 来源字段名
    private String chartType;       // 图表类型（当type=chart时）
    private Integer sort;           // 排序号
    private String description;     // 描述
    private Integer deleted;        // 删除标记
}
```

---

## 5. 业务规则

### 5.1 激活状态规则

1. **唯一激活**：系统中同一时间只能有一个激活的标准模板
2. **切换激活**：调用 `set-active` 接口时，原激活模板自动变为 `inactive`
3. **首次初始化**：`init` 接口上传的模板自动设为激活状态

### 5.2 模板版本规则

1. 版本号格式建议：`v{主版本}.{次版本}`，如 `v1.0`、`v2.1`
2. 版本号由前端上传时传入，后端负责存储和返回
3. 同一版本号可以多次上传（视为不同模板实例）

### 5.3 删除规则

1. 标准模板**不支持物理删除**，只能逻辑删除（`deleted=1`）
2. 已逻辑删除的模板不在列表中显示
3. 当前激活的模板**不能**被逻辑删除

---

## 6. 实现优先级

| 优先级 | 接口 | 说明 |
|--------|------|------|
| P0（高） | 路径前缀变更 | 5个现有接口迁移到 `/api/admin` 前缀 |
| P0（高） | 获取模板详情 | 新增接口，页面查看详情必需 |
| P0（高） | 设置激活模板 | 新增接口，激活切换功能必需 |
| P0（高） | 删除模板 | 新增接口，删除功能必需 |
| P1（中） | 参数名兼容 | `modules` 和 `placeholders` 接口兼容新旧参数名 |
| P1（中） | moduleId过滤 | `placeholders` 接口支持按模块过滤 |

---

## 7. 前端调用示例

### 7.1 获取模板列表

```javascript
GET /api/admin/system-template/list
```

### 7.2 获取模块列表（新参数名）

```javascript
GET /api/admin/system-template/modules?templateId=template-001
```

### 7.3 获取占位符列表（按模块过滤）

```javascript
GET /api/admin/system-template/placeholders?templateId=template-001&moduleId=module-001
```

### 7.4 设置激活模板

```javascript
POST /api/admin/system-template/template-002/set-active
```

### 7.5 删除模板

```javascript
DELETE /api/admin/system-template/template-002
```

---

## 8. 联系人

- 前端开发：[填写姓名]
- 需求确认：[填写姓名]
- 预计联调时间：[填写日期]
