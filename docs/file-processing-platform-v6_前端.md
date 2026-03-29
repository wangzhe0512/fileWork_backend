# 反向引擎架构升级 V6 — 前端配合修改清单

## 背景

后端 V6 在 V5 基础上进行了两项架构升级：

1. **数据源结构化解析（Schema 解析层）**：对已上传的清单/BVD Excel 按需解析出 Sheet 列表与字段结构，持久化缓存，前端可按需触发并展示
2. **占位符注册表数据库化**：将引擎内硬编码的40条占位符规则迁移到数据库，支持系统级（所有企业共享）和企业级（个性化覆盖）两级管理，提供完整 CRUD 接口

同时，**V5 的前端修改（`file-processing-platform-v5_前端.md`）尚未落地**，需在 V6 一起补做。

---

## 前端项目路径

```
d:/wangzhe/CodeBuddy/fileWork/file-processing-platform/
```

技术栈：Vue 3 + TypeScript + Element Plus

---

## 修改总览

| 优先级 | 类型 | 文件/功能 | 说明 |
|--------|------|-----------|------|
| **必做（V5补漏）** | 类型定义 | `src/types/index.ts` | 新增 `RegistryEntry` 接口 |
| **必做（V5补漏）** | 逻辑修改 | `UpdateReportDialog.vue` | 读取反向生成结果中的 `unmatchedRegistryEntries` |
| **必做（V5补漏）** | UI 扩展 | `UnmatchedTextDialog.vue` | 展示注册表未匹配项（纯提醒，无操作按钮） |
| **必做（V6新增）** | 接口调用 | `UpdateReportDialog.vue` | 反向生成接口增加传 `companyId` 参数 |
| **建议做** | 新增页面 | 占位符注册表管理页 | 管理员维护系统级/企业级注册表 |
| 可选 | UI 增强 | 子模板编辑页 | Schema 树形展示 + 注册表新建时字段下拉联动 |

---

## 一、V5 遗留修改（必做）

### 1.1 `src/types/index.ts` — 新增 RegistryEntry 接口

在文件末尾（`UnmatchedLongTextEntry` 接口之后）新增：

```typescript
// 注册表未匹配条目（反向生成 V5 新增）
export interface RegistryEntry {
  /** 占位符标准名，如"清单模板-数据表-B1" */
  placeholderName: string
  /** 可读名称，如"企业全称" */
  displayName: string
  /** 占位符类型 */
  type: 'DATA_CELL' | 'TABLE_CLEAR' | 'TABLE_CLEAR_FULL' | 'TABLE_ROW_TEMPLATE' | 'LONG_TEXT' | 'BVD'
  /** 数据来源 */
  dataSource: 'list' | 'bvd'
  /** Excel Sheet 名（TABLE_CLEAR 类型可为空） */
  sheetName?: string
  /** 单元格坐标，如"B1"（TABLE_CLEAR 类型为空） */
  cellAddress?: string
}
```

---

### 1.2 `UpdateReportDialog.vue` — 读取 unmatchedRegistryEntries

#### 顶部 import 新增类型引入

```typescript
import type { RegistryEntry } from '@/types'
```

#### 新增响应式变量（在现有 `unmatchedEntries` 声明附近）

```typescript
const registryUnmatchedEntries = ref<RegistryEntry[]>([])
```

#### 修改 handleSubmit 结果处理逻辑

**修改前：**
```typescript
const entries = res.data?.unmatchedLongTextEntries || []
if (entries.length > 0) {
  unmatchedEntries.value = entries
  currentStep.value = 3
  loading.value = false
  ElMessage.success(`反向生成完成，发现 ${entries.length} 个未匹配项需要处理`)
  showUnmatchedDialog.value = true
} else {
  // 成功流程
}
```

**修改后：**
```typescript
const entries = res.data?.unmatchedLongTextEntries || []
const registryEntries: RegistryEntry[] = res.data?.unmatchedRegistryEntries || []
registryUnmatchedEntries.value = registryEntries

const totalUnmatched = entries.length + registryEntries.length
if (totalUnmatched > 0) {
  unmatchedEntries.value = entries
  currentStep.value = 3
  loading.value = false
  ElMessage.success(`反向生成完成，发现 ${totalUnmatched} 个未匹配项需要处理`)
  showUnmatchedDialog.value = true
} else {
  // 成功流程（不变）
}
```

#### 模板中 UnmatchedTextDialog 新增 prop

```html
<UnmatchedTextDialog
  v-model="showUnmatchedDialog"
  :entries="unmatchedEntries"
  :registry-unmatched-entries="registryUnmatchedEntries"
  :template-id="generatedTemplateId"
  @ignore="handleUnmatchedIgnore"
  @ignore-all="handleUnmatchedIgnoreAll"
  @go-to-editor="handleGoToEditor"
  @confirm="handleUnmatchedConfirm"
/>
```

---

### 1.3 `UnmatchedTextDialog.vue` — 增加注册表未匹配区块

#### props 中新增字段

```typescript
import type { RegistryEntry } from '@/types'

const props = defineProps<{
  modelValue: boolean
  entries: UnmatchedEntry[]
  registryUnmatchedEntries?: RegistryEntry[]   // 新增
  templateId: string
}>()
```

#### 动态统计标题（可选但建议做）

```typescript
const totalCount = computed(() =>
  props.entries.length + (props.registryUnmatchedEntries?.length ?? 0)
)
```

```html
<el-dialog :title="`⚠️ 发现 ${totalCount} 项未自动处理`" ...>
```

footer 统计文字：

```html
<span class="unmatched-count">
  共 {{ totalCount }} 项未处理
  （长文本 {{ entries.length }} 项，占位符 {{ registryUnmatchedEntries?.length ?? 0 }} 项）
</span>
```

#### template 中新增注册表未匹配区块（在 `.unmatched-list` 关闭后、footer 之前）

```html
<!-- 注册表未匹配项：TABLE_CLEAR 未定位、BVD 数据未找到等 -->
<div v-if="registryUnmatchedEntries && registryUnmatchedEntries.length > 0" class="registry-unmatched-section">
  <el-divider content-position="left">
    <span class="section-title">⚠️ 以下占位符未能自动处理（需手动检查）</span>
  </el-divider>
  <div class="registry-unmatched-list">
    <div
      v-for="(item, index) in registryUnmatchedEntries"
      :key="index"
      class="registry-unmatched-item"
    >
      <div class="item-header">
        <div class="item-title">
          <span class="display-name">{{ item.displayName }}</span>
          <el-tag size="small" :type="getTypeBadge(item.type)">{{ getTypeLabel(item.type) }}</el-tag>
          <el-tag v-if="item.dataSource" size="small" :type="getDataSourceType(item.dataSource)">
            {{ item.dataSource === 'list' ? '清单' : 'BVD' }}
          </el-tag>
        </div>
        <div class="placeholder-name-small">{{ item.placeholderName }}</div>
      </div>
      <div class="item-hint">请在生成的子模板中手动确认此占位符是否已正确替换</div>
    </div>
  </div>
</div>
```

#### script 新增辅助方法

```typescript
const getTypeLabel = (type: string): string => {
  const map: Record<string, string> = {
    DATA_CELL: '数据格',
    TABLE_CLEAR: '财务表',
    TABLE_CLEAR_FULL: '财务表(全)',
    TABLE_ROW_TEMPLATE: '行模板',
    LONG_TEXT: '长文本',
    BVD: 'BVD'
  }
  return map[type] || type
}

const getTypeBadge = (type: string): 'danger' | 'warning' | 'info' | 'primary' => {
  const map: Record<string, 'danger' | 'warning' | 'info' | 'primary'> = {
    TABLE_CLEAR: 'danger',
    TABLE_CLEAR_FULL: 'danger',
    TABLE_ROW_TEMPLATE: 'warning',
    BVD: 'warning',
    DATA_CELL: 'info',
    LONG_TEXT: 'primary'
  }
  return map[type] || 'info'
}
```

#### style 新增样式

```scss
.registry-unmatched-section {
  margin-top: 16px;

  .section-title {
    font-size: 13px;
    color: #e6a23c;
    font-weight: 500;
  }
}

.registry-unmatched-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 8px;
}

.registry-unmatched-item {
  padding: 12px 16px;
  border: 1px solid #faecd8;
  border-radius: 6px;
  background: #fdf6ec;

  .item-header {
    .item-title {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-bottom: 4px;

      .display-name {
        font-size: 14px;
        font-weight: 600;
        color: #303133;
      }
    }

    .placeholder-name-small {
      font-size: 11px;
      color: #b4a382;
      font-family: monospace;
    }
  }

  .item-hint {
    margin-top: 6px;
    font-size: 12px;
    color: #909399;
  }
}
```

---

## 二、V6 新增修改

### 2.1 反向生成接口传 companyId（必做）

`UpdateReportDialog.vue` 中调用 `reverse-generate` 接口时，请求体增加 `companyId` 字段（从当前页面/上下文中读取企业ID）：

**修改前：**
```typescript
const res = await api.post(`/company-templates/${templateId}/reverse-generate`, {
  histFileId,
  listFileId,
  bvdFileId
})
```

**修改后：**
```typescript
const res = await api.post(`/company-templates/${templateId}/reverse-generate`, {
  histFileId,
  listFileId,
  bvdFileId,
  companyId   // 新增：用于从数据库读取该企业的有效注册表
})
```

> `companyId` 传入后，引擎会优先使用企业级注册表规则（覆盖同名系统级规则）；不传或传 null 时降级使用静态兜底注册表，行为与之前完全一致。

---

### 2.2 占位符注册表管理页（建议做）

新增一个管理页面，路由建议：`/admin/placeholder-registry`

#### 页面功能

| 功能 | 接口 | 说明 |
|------|------|------|
| 查看系统级规则列表 | `GET /api/placeholder-registry?level=system` | 管理员只读 |
| 查看某企业规则列表 | `GET /api/placeholder-registry?level=company&companyId=` | 企业下拉选择 |
| 预览生效规则（合并后） | `GET /api/placeholder-registry/effective?companyId=` | 展示企业级覆盖系统级的最终结果 |
| 新建条目 | `POST /api/placeholder-registry` | 见下方字段说明 |
| 编辑条目 | `PUT /api/placeholder-registry/{id}` | level/tenantId 不可改 |
| 删除条目 | `DELETE /api/placeholder-registry/{id}` | 软删除，系统级条目禁止删除 |

#### 注册表条目字段（新建/编辑表单）

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `level` | select | `system` / `company` |
| `companyId` | select | level=company 时必填 |
| `placeholderName` | string | 占位符标准名（唯一键） |
| `displayName` | string | 可读名称 |
| `phType` | select | `DATA_CELL` / `TABLE_CLEAR` / `TABLE_CLEAR_FULL` / `TABLE_ROW_TEMPLATE` / `LONG_TEXT` / `BVD` |
| `dataSource` | select | `list` / `bvd` |
| `sheetName` | string | 来源 Sheet 名 |
| `cellAddress` | string | 单元格坐标，如 `B1` |
| `titleKeywords` | tag输入 | TABLE_CLEAR 系列专用，JSON 数组 |
| `columnDefs` | tag输入 | TABLE_ROW_TEMPLATE 专用，JSON 数组 |
| `sort` | number | 排序权重 |
| `enabled` | switch | 0=禁用，企业级可禁用某条系统规则 |

#### 页面布局建议

```
┌──────────────────────────────────────────────────┐
│  占位符注册表管理                                   │
│                                                  │
│  [系统级] [企业级 ▼ 企业选择] [预览生效规则]          │
│                                          [新建]  │
├──────────────────────────────────────────────────┤
│  占位符名称  |  可读名  |  类型  |  来源 | 启用 | 操作│
│  ...                                             │
└──────────────────────────────────────────────────┘
```

> 企业级条目中 `enabled=false` 的行用灰色+删除线样式表示"已禁用系统规则"

---

### 2.3 数据源 Schema 浏览（可选）

在子模板编辑页面或数据文件详情页，增加 Schema 树形展示：

#### 加载逻辑

```typescript
// 进入页面时
const schemaTree = ref([])

async function loadSchema(dataFileId: string) {
  const res = await api.get(`/data-files/${dataFileId}/schema`)
  if (res.data && res.data.length > 0) {
    schemaTree.value = res.data
  } else {
    // 未解析过，触发解析
    await api.post(`/data-files/${dataFileId}/parse-schema`)
    const res2 = await api.get(`/data-files/${dataFileId}/schema`)
    schemaTree.value = res2.data || []
  }
}
```

#### Schema 接口返回结构

```typescript
interface FieldInfo {
  address: string       // 单元格坐标，如 "B1"
  label: string         // 字段可读名，如 "企业全称"
  sampleValue: string   // 样本值
  inferredType: 'TEXT' | 'NUMBER' | 'LONG_TEXT'
}

interface SheetNode {
  sheetName: string     // Sheet 名称
  sheetIndex: number    // Sheet 顺序（从0开始）
  fields: FieldInfo[]   // 该 Sheet 的字段列表
}
```

#### UI 展示建议

使用 `el-tree` 或折叠面板（`el-collapse`）展示：

```
▶ 数据表 (Sheet 0)
    B1  企业全称       [TEXT]    斯必克流体技术...
    B2  企业简称       [TEXT]    斯必克
    B6  行业情况       [LONG_TEXT] 本公司所处行业...
▶ 供应商清单 (Sheet 1)
    A列  供应商名称    [TEXT]
    B列  采购金额      [NUMBER]
```

---

## 三、后端接口变化对照

### 反向生成接口（`POST /company-templates/{id}/reverse-generate`）

| 字段 | V5 | V6 | 说明 |
|------|----|----|------|
| 请求体 `companyId` | ❌ | ✅ 新增 | 用于读取企业级注册表，不传则降级静态兜底 |
| 响应 `unmatchedLongTextEntries` | ✅ | ✅ | 不变 |
| 响应 `unmatchedRegistryEntries` | ✅（V5新增） | ✅ | 注册表中未被引擎处理的条目列表 |

### 新增接口（V6）

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/data-files/{id}/parse-schema` | 触发解析文件 Schema |
| `GET` | `/api/data-files/{id}/schema` | 查询已解析的 Schema（未解析返回空列表） |
| `GET` | `/api/placeholder-registry` | 查询注册表（`?level=system` 或 `?level=company&companyId=`） |
| `GET` | `/api/placeholder-registry/effective` | 查询生效规则（`?companyId=`） |
| `POST` | `/api/placeholder-registry` | 新建注册表条目 |
| `PUT` | `/api/placeholder-registry/{id}` | 更新注册表条目 |
| `DELETE` | `/api/placeholder-registry/{id}` | 软删除注册表条目 |

---

## 四、弹窗 UI 最终效果（V5+V6 完成后）

```
┌─────────────────────────────────────────┐
│  ⚠️ 发现 N 项未自动处理                    │
├─────────────────────────────────────────┤
│  这些内容在 Word 文档中未找到匹配位置，     │
│  请手动检查：                             │
│                                         │
│  【长文本未匹配区块（原有）】               │
│  ┌──────────────────────────────────┐   │
│  │ 行业情况-B1  [清单]               │   │
│  │ 来源：行业情况Sheet - B1          │   │
│  │ 内容预览：xxx...                  │   │
│  │              [忽略] [去编辑器查找] │   │
│  └──────────────────────────────────┘   │
│                                         │
│  ─── ⚠️ 以下占位符未能自动处理 ───        │
│                                         │
│  【注册表未匹配区块（V5+V6）】            │
│  ┌──────────────────────────────────┐   │
│  │ 利润表  [财务表][清单]             │   │ ← 橙色背景，无操作按钮
│  │ 清单模板-利润表                    │   │
│  │ 请在子模板中手动确认此占位符...     │   │
│  └──────────────────────────────────┘   │
├─────────────────────────────────────────┤
│ 共N项未处理（长文本M项，占位符K项）        │
│                      [全部忽略] [确认继续]│
└─────────────────────────────────────────┘
```

**两种未匹配卡片的区别：**

| | 长文本未匹配（原有） | 注册表未匹配（V5/V6） |
|--|--|--|
| 卡片背景 | 白色 + 橙色左边框 | 整体橙色背景 `#fdf6ec` |
| 操作按钮 | 有「忽略」「去编辑器查找」 | **无操作按钮**，仅提示文字 |
| 内容预览 | 显示文本截断预览 | 无，显示可读名称 + 类型标签 |
| 交互性质 | 用户需决策 | 纯提醒，让用户去子模板编辑器自查 |
