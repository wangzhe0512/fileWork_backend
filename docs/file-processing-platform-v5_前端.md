# 反向引擎改造 V5 — 前端修改清单

## 背景

后端 V5 改造在 `ReverseResult` 中新增了 `unmatchedRegistryEntries` 字段，用于告知前端：注册表中已定义但本次未能自动处理的占位符（如财务整表 TABLE_CLEAR 未定位到、BVD 数据未找到等）。当前前端完全未读取该字段，用户无法感知这些占位符需要手动补充处理。

---

## 前端项目路径

```
d:/wangzhe/CodeBuddy/fileWork/file-processing-platform/
```

技术栈：Vue 3 + TypeScript + Element Plus

---

## 涉及修改的文件

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `src/types/index.ts` | 新增接口 | 新增 `RegistryEntry` 类型 |
| `src/views/report/components/UpdateReportDialog.vue` | 逻辑修改 | 读取并传递 `unmatchedRegistryEntries` |
| `src/views/report/components/UnmatchedTextDialog.vue` | UI 扩展 | 增加注册表未匹配项展示区块 |

---

## 详细修改内容

### 1. `src/types/index.ts` — 新增 RegistryEntry 接口

在文件末尾（现有 `UnmatchedLongTextEntry` 接口之后）新增：

```typescript
// 注册表未匹配条目（反向生成V5新增）
export interface RegistryEntry {
  /** 占位符标准名，如"清单模板-数据表-B1" */
  placeholderName: string
  /** 可读名称，如"企业全称" */
  displayName: string
  /** 占位符类型 */
  type: 'DATA_CELL' | 'TABLE_CLEAR' | 'LONG_TEXT' | 'BVD'
  /** 数据来源 */
  dataSource: 'list' | 'bvd'
  /** Excel Sheet 名（TABLE_CLEAR 类型可为空） */
  sheetName?: string
  /** 单元格坐标，如"B1"（TABLE_CLEAR 类型为空） */
  cellAddress?: string
}
```

---

### 2. `src/views/report/components/UpdateReportDialog.vue` — 读取 unmatchedRegistryEntries

#### 2a. 新增响应式变量（在现有 `unmatchedEntries` 声明附近，约第 114~117 行）

```typescript
// 新增：注册表未匹配条目
const registryUnmatchedEntries = ref<RegistryEntry[]>([])
```

同时在顶部 import 中引入类型：

```typescript
import type { UnmatchedEntry } from './UnmatchedTextDialog.vue'
import type { RegistryEntry } from '@/types'
```

#### 2b. 修改 handleSubmit 中的结果处理逻辑（约第 261~278 行）

**修改前：**
```typescript
// 检查是否有未匹配的长文本
const entries = res.data?.unmatchedLongTextEntries || []
if (entries.length > 0) {
  unmatchedEntries.value = entries
  currentStep.value = 3
  loading.value = false
  ElMessage.success(`反向生成完成，发现 ${entries.length} 个未匹配项需要处理`)
  showUnmatchedDialog.value = true
} else {
  // ...成功流程
}
```

**修改后：**
```typescript
// 检查是否有未匹配的长文本
const entries = res.data?.unmatchedLongTextEntries || []
// 新增：读取注册表未匹配条目
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
  // ...成功流程（不变）
}
```

#### 2c. 修改模板中 UnmatchedTextDialog 的调用（约第 66~74 行）

**修改前：**
```html
<UnmatchedTextDialog
  v-model="showUnmatchedDialog"
  :entries="unmatchedEntries"
  :template-id="generatedTemplateId"
  @ignore="handleUnmatchedIgnore"
  @ignore-all="handleUnmatchedIgnoreAll"
  @go-to-editor="handleGoToEditor"
  @confirm="handleUnmatchedConfirm"
/>
```

**修改后：**
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

### 3. `src/views/report/components/UnmatchedTextDialog.vue` — 增加注册表未匹配项展示

#### 3a. props 中新增字段（约第 77~82 行）

**修改前：**
```typescript
const props = defineProps<{
  modelValue: boolean
  entries: UnmatchedEntry[]
  templateId: string
}>()
```

**修改后：**
```typescript
import type { RegistryEntry } from '@/types'

const props = defineProps<{
  modelValue: boolean
  entries: UnmatchedEntry[]
  registryUnmatchedEntries?: RegistryEntry[]   // 新增
  templateId: string
}>()
```

#### 3b. template 中新增注册表未匹配区块（在 `</div>` 关闭 `.unmatched-list` 之后，footer 之前）

```html
<!-- 注册表未匹配项（V5新增：TABLE_CLEAR未定位、BVD未找到等） -->
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
          <el-tag size="small" :type="getTypeBadge(item.type)">
            {{ getTypeLabel(item.type) }}
          </el-tag>
          <el-tag v-if="item.dataSource" size="small" :type="getDataSourceType(item.dataSource)">
            {{ getDataSourceLabel(item.dataSource) }}
          </el-tag>
        </div>
        <div class="placeholder-name-small">{{ item.placeholderName }}</div>
      </div>
      <div class="item-hint">
        请在生成的子模板中手动确认此占位符是否已正确替换
      </div>
    </div>
  </div>
</div>
```

#### 3c. script 中新增辅助方法

```typescript
/**
 * 获取占位符类型标签文字
 */
const getTypeLabel = (type: string): string => {
  const map: Record<string, string> = {
    DATA_CELL: '数据格',
    TABLE_CLEAR: '财务表',
    LONG_TEXT: '长文本',
    BVD: 'BVD'
  }
  return map[type] || type
}

/**
 * 获取占位符类型标签颜色
 */
const getTypeBadge = (type: string): 'danger' | 'warning' | 'info' | 'primary' => {
  const map: Record<string, 'danger' | 'warning' | 'info' | 'primary'> = {
    TABLE_CLEAR: 'danger',
    BVD: 'warning',
    DATA_CELL: 'info',
    LONG_TEXT: 'primary'
  }
  return map[type] || 'info'
}
```

#### 3d. style 中新增样式

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

#### 3e. dialog 标题改为动态统计（可选优化）

将弹窗标题从：
```
⚠️ 以下长文本未自动匹配
```
改为：
```typescript
// 动态计算总未匹配数
const totalCount = computed(() =>
  props.entries.length + (props.registryUnmatchedEntries?.length ?? 0)
)
```
```html
<el-dialog :title="`⚠️ 发现 ${totalCount} 项未自动处理`" ...>
```

footer 中的统计文字也同步更新：
```html
<span class="unmatched-count">
  共 {{ totalCount }} 项未处理
  （长文本 {{ entries.length }} 项，占位符 {{ registryUnmatchedEntries?.length ?? 0 }} 项）
</span>
```

---

## UI 交互设计说明

### 弹窗整体布局（从上到下）

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
│  【注册表未匹配区块（V5新增）】            │
│  ┌──────────────────────────────────┐   │
│  │ 利润表  [财务表][清单]             │   │  ← 橙色背景卡片
│  │ 清单模板-利润表                    │   │
│  │ 请在子模板中手动确认此占位符...     │   │
│  └──────────────────────────────────┘   │
│  ┌──────────────────────────────────┐   │
│  │ 关联企业名称  [BVD][BVD]          │   │
│  │ BVD数据模板-数据表-B1              │   │
│  │ 请在子模板中手动确认此占位符...     │   │
│  └──────────────────────────────────┘   │
├─────────────────────────────────────────┤
│ 共N项未处理（长文本M项，占位符K项）        │
│                      [全部忽略] [确认继续]│
└─────────────────────────────────────────┘
```

### 两种未匹配卡片的差异

| | 长文本未匹配（原有） | 注册表未匹配（V5新增） |
|--|--|--|
| **卡片背景** | 白色 + 橙色左边框 | 整体橙色背景（`#fdf6ec`） |
| **操作按钮** | 有「忽略」「去编辑器查找」 | **无操作按钮**，仅提示文字 |
| **内容预览** | 显示文本内容截断预览 | 无（显示可读名称 + 类型标签） |
| **类型标签** | 显示来源（清单/BVD） | 显示类型（财务表/BVD/数据格）+ 来源 |
| **交互性质** | 需用户决策（忽略或去查找） | 纯提醒，告知用户去子模板编辑器自查 |

> 注册表未匹配项本质是**纯提醒性质**——告知用户"这些占位符引擎没有自动处理到，请去子模板编辑器里自查"，不需要用户在弹窗中做任何操作，因此没有操作按钮。

---

## 后端返回字段变化对照

| 字段 | V4 | V5 | 前端处理 |
|------|----|----|---------|
| `unmatchedLongTextEntries` | ✅ 存在 | ✅ 存在 | 不变，继续读取 |
| `unmatchedRegistryEntries` | ❌ 不存在 | ✅ 新增 | **需新增读取和展示** |

---

## 修改优先级

| 优先级 | 内容 | 影响 |
|--------|------|------|
| **必做** | `types/index.ts` 新增 `RegistryEntry` 接口 | 类型安全 |
| **必做** | `UpdateReportDialog.vue` 读取 `unmatchedRegistryEntries` | 数据不丢失 |
| **必做** | `UnmatchedTextDialog.vue` 展示注册表未匹配项 | 用户可感知 |
| 可选 | dialog 标题改为动态统计 | 体验优化 |
