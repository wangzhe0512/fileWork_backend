# 后端接口调整文档

## 概述
根据前端V2版本的业务需求调整，后端需要对接口进行以下修改。

---

## 1. 反向生成接口调整（重要）

### 接口
```
POST /api/company-template/reverse-generate
```

### 当前问题
当前接口要求上传3个文件：
- `historicalReport` - 历史报告（Word）
- `listFile` - 清单模板（Excel）
- `bvdFile` - BVD数据模板（Excel）

### 需要改为
**只上传1个文件**，其他数据根据年度自动关联：

#### 请求参数
| 参数名 | 位置 | 类型 | 必填 | 说明 |
|--------|------|------|------|------|
| companyId | query | string | 是 | 企业ID |
| year | query | number | 是 | 年度（如：2023）|
| historicalReport | body | File | 是 | 历史报告（Word文档）|
| name | query | string | 否 | 子模板名称 |
| sourceReportId | query | string | 否 | 源报告ID |

#### 业务逻辑
1. 接收 `year` 参数
2. 从**数据管理模块**查询该年度的清单模板和BVD数据
3. 如果数据不存在，返回错误：
   ```json
   {
     "code": 400,
     "message": "该年度清单模板或BVD数据缺失，请先到数据管理上传"
   }
   ```
4. 数据存在则执行反向生成逻辑

---

## 2. 子模板内容获取接口（新增）

### 接口
```
GET /api/company-template/{id}/content-url
```

### 用途
提供可直接访问的文件URL，供 OnlyOffice 编辑器加载使用。

### 响应格式
```json
{
  "code": 0,
  "data": {
    "url": "https://api.example.com/files/xxx.docx",
    "fileName": "2023年度子模板.docx",
    "fileType": "docx"
  },
  "message": "success"
}
```

### 注意事项
- URL 必须是可直接访问的公开链接（或带临时token的授权链接）
- OnlyOffice 服务器需要能访问该 URL

---

## 3. 占位符确认接口调整

### 接口
```
POST /api/company-template/{id}/confirm-placeholders
```

### 请求体调整
新增 `confirmedType` 字段，用于前端传递用户确认的类型：

```json
{
  "placeholders": [
    {
      "placeholderName": "${chart3}",
      "confirmed": true,
      "confirmedType": "chart",      // 新增字段
      "paragraphIndex": 0,
      "runIndex": 0,
      "offset": 0
    },
    {
      "placeholderName": "${text15}",
      "confirmed": true,
      "confirmedType": "text",       // 新增字段
      "paragraphIndex": 5,
      "runIndex": 2,
      "offset": 10
    }
  ]
}
```

### confirmedType 可选值
| 值 | 说明 |
|----|------|
| `text` | 文本占位符 |
| `table` | 表格占位符 |
| `chart` | 图表占位符 |
| `image` | 图片占位符 |
| `ignore` | 忽略此占位符（视为普通文本）|

---

## 4. 获取当前激活子模板接口（确认）

### 接口
```
GET /api/company-template/active
```

### 确认事项
- 确保此接口返回当前企业正在使用的子模板
- 用于生成报告时系统自动获取当前子模板

### 响应示例
```json
{
  "code": 0,
  "data": {
    "id": "template-001",
    "name": "2023年度子模板",
    "year": 2023,
    "isActive": true,
    "updatedAt": "2024-03-08T10:00:00Z"
  },
  "message": "success"
}
```

---

## 5. 数据关联说明

### 反向生成时的数据关联流程
```
用户上传历史报告 + 选择年度(2023)
          ↓
后端根据 year=2023 查询数据管理模块
          ↓
获取该年度的清单模板和BVD数据
          ↓
执行反向生成逻辑（占位符替换）
          ↓
返回生成的子模板信息
```

### 生成报告时的数据关联
```
用户选择年度(2023)
          ↓
系统自动获取：
  1. 当前激活的子模板
  2. 2023年度的清单模板和BVD数据
          ↓
执行报告生成
```

---

## 调整清单

| 序号 | 接口 | 动作 | 优先级 |
|------|------|------|--------|
| 1 | `POST /api/company-template/reverse-generate` | 修改 | 高 |
| 2 | `GET /api/company-template/{id}/content-url` | 新增 | 高 |
| 3 | `POST /api/company-template/{id}/confirm-placeholders` | 调整 | 中 |
| 4 | `GET /api/company-template/active` | 确认 | 中 |

---

## 时间计划建议

1. **第一阶段**：完成反向生成接口调整（阻塞前端开发）
2. **第二阶段**：新增 content-url 接口
3. **第三阶段**：调整占位符确认接口

---

## 前端联系人
如有疑问，请联系前端开发人员确认细节。
