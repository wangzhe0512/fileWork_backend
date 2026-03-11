# 前端接口变更说明 - isCurrent 字段

## 变更背景

企业子模板的"当前使用"状态与"归档"状态原来混在一起（通过 `status` 字段管理），现在分离为两个独立概念：

- `status`：生命周期状态（`active` / `archived`）
- `isCurrent`：是否为当前使用版本（`true` / `false`）

---

## 1. 模板列表接口 `GET /api/company-template`

### 响应字段变化

新增 `isCurrent` 字段（布尔值）：
- `true`：当前使用版本
- `false`：非当前使用版本

`status` 字段含义不变：
- `active`：正常状态
- `archived`：已归档

`isActive` 字段保留（由 `status === 'active'` 计算得出）

### 示例响应

```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "id": "xxx",
        "name": "2024年子模板",
        "year": 2024,
        "status": "active",
        "isCurrent": true,      // 新增：当前使用版本
        "isActive": true,       // status === 'active'
        "createdAt": "2024-03-10 10:00:00",
        "updatedAt": "2024-03-10 10:00:00"
      },
      {
        "id": "yyy",
        "name": "2024年子模板-旧版",
        "year": 2024,
        "status": "active",
        "isCurrent": false,     // 同一年度的其他模板
        "isActive": true
      },
      {
        "id": "zzz",
        "name": "2023年子模板",
        "year": 2023,
        "status": "active",
        "isCurrent": true,      // 其他年度的当前使用模板不受影响
        "isActive": true
      }
    ]
  }
}
```

---

## 2. 前端显示逻辑调整

| 显示项 | 之前逻辑 | 现在逻辑 |
|--------|----------|----------|
| "当前使用"标签 | `status === 'active'` | `isCurrent === true` |
| 状态文本 | `status` 字段 | `status` 字段（active/archived）|

### 代码示例

```javascript
// 之前：判断是否显示"当前使用"
const isCurrentUse = template.status === 'active';

// 现在：判断是否显示"当前使用"
const isCurrentUse = template.isCurrent === true;

// 状态显示不变
const statusText = template.status === 'active' ? '正常' : '已归档';
```

---

## 3. "设为当前使用"接口 `PUT /api/company-template/{id}/set-active`

### 行为变化

| 场景 | 之前行为 | 现在行为 |
|------|----------|----------|
| 切换当前使用 | 将其他模板 `status` 改为 `archived`（即归档） | 只将同企业**同一年度**的其他模板 `isCurrent` 设为 `false`，不影响 `status`，也不影响其他年度的模板 |

### 前端调用方式

**无需修改**，按之前的方式调用即可：

```javascript
// 调用方式不变
await fetch(`/api/company-template/${templateId}/set-active`, {
  method: 'PUT'
});
```

---

## 4. 反向生成接口 `POST /api/company-template/reverse-generate`

### 行为变化

- 新模板默认 `isCurrent = true`
- 不影响同企业其他年度模板的 `isCurrent` 状态

### 前端调用方式

**无需修改**。

---

## 5. 归档接口 `PUT /api/company-template/{id}/archive`

### 行为变化

- 归档时自动将该模板 `isCurrent` 设为 `false`
- 不影响其他模板

### 前端调用方式

**无需修改**。

---

## 6. 生成报告时的模板选择

### 行为变化

后端现在按 `companyId + year + isCurrent=true` 自动选择模板。

### 前端影响

- 如果前端传递了 `templateId`，则使用指定的模板
- 如果前端未传递 `templateId`，后端会自动查找该年度 `isCurrent=true` 的模板
- 无需修改现有调用逻辑

---

## 总结

前端需要做的修改：

1. **列表页**："当前使用"标签改用 `isCurrent` 字段判断
2. **状态显示**：继续使用 `status` 字段显示生命周期状态
3. 其他接口调用方式不变

---

## 修改检查清单

- [ ] 模板列表页："当前使用"标签使用 `isCurrent` 字段
- [ ] 模板列表页：状态列使用 `status` 字段
- [ ] 测试"设为当前使用"功能，确认同一年度互斥、不同年度不影响
- [ ] 测试反向生成功能，确认新模板默认当前使用
- [ ] 测试归档功能，确认归档后当前使用状态清除
