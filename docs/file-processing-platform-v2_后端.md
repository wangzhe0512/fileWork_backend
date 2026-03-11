# 关联交易同期资料报告生成系统 — 开发计划

## 一、业务背景

多租户关联交易同期资料报告自动化生成平台，核心流程：

**系统初始化 → 反向生成企业子模板 → 子模板在线编辑 → 正向生成当年报告 → 人工修改后更新子模板**

---

## 二、核心概念

| 概念 | 说明 |
|------|------|
| 标准模板 | 系统内置，包含 Word 模板 + 清单 Excel + BVD Excel 三件套，系统级，不绑定租户 |
| 占位符 | 格式 `{{清单模板-数据表-B3}}`，三段式：数据源-Sheet名-单元格地址 |
| 企业子模板 | 标准模板的子集，由历史报告反向生成，保留企业排版风格，支持在线编辑 |
| 正向生成 | 子模板 + 新年度 Excel 数据 → 当年报告 Word |
| 反向生成 | 历史报告 + 同年 Excel 数据 → 企业子模板（数据替换为占位符） |

---

## 三、占位符类型

| 类型标识 | 说明 | 反向处理 | 正向处理 |
|---------|------|---------|---------|
| `text` | 文本段落中的 `{{...}}` | 精确字符串匹配替换为占位符 | 占位符替换为实际值 |
| `table` | 表格单元格中的 `{{...}}` | 逐行逐列匹配替换为占位符 | 占位符替换为实际值 |
| `chart` | Word 内嵌可编辑图表（双击可编辑）| 更新图表内嵌数据表数值为占位符标记 | 用新年度 Excel 数据更新内嵌数据表 |
| `image` | Word 中的静态图片（不可编辑）| 记录图片位置，关联占位符标记 | 用新图片字节流替换旧图片 |

> 处理依据为模板/历史报告中实际存在的元素类型：可编辑图表按 `chart` 处理，静态图片按 `image` 处理。

---

## 四、数据库设计（重新设计）

### 新增 4 张表

| 表名 | 级别 | 说明 |
|------|------|------|
| `system_template` | 系统级（不绑定租户） | 标准模板三件套文件路径、状态、版本 |
| `system_module` | 系统级 | 标准模板解析出的章节模块（按占位符前缀分组）|
| `system_placeholder` | 系统级 | 占位符规则：名称、type(text/table/chart/image)、数据源、sheet、单元格地址 |
| `company_template` | 企业级（绑定租户/企业）| 反向生成的企业子模板 Word 文件，支持多版本 |

### 现有表调整

- `report` 表新增 `template_id`（关联 `company_template.id`）字段
- 旧 `template`、`placeholder`、`report_module` 表**保留不删除**，不再使用

### 迁移脚本

新增 `V3__redesign_template_system.sql`

---

## 五、功能模块与开发任务

### Task 1：数据库迁移脚本
- 新建 `src/main/resources/db/V3__redesign_template_system.sql`
- 建 `system_template`、`system_module`、`system_placeholder`、`company_template` 四张表
- `ALTER TABLE report` 新增 `template_id`、`generation_status`、`generation_error` 字段

---

### Task 2：实体类 & Mapper

**新增实体类（`src/main/java/com/fileproc/template/entity/`）**

- `SystemTemplate.java`：id, name, version, wordFilePath, listExcelPath, bvdExcelPath, status(active/archived), description, createdAt, deleted
- `SystemModule.java`：id, systemTemplateId, name, code, description, sort, deleted
- `SystemPlaceholder.java`：id, systemTemplateId, moduleCode, name, displayName, type(text/table/chart/image), dataSource(list/bvd), sourceSheet, sourceField, chartType, sort, description, deleted
- `CompanyTemplate.java`：id, tenantId, companyId, systemTemplateId, name, year, sourceReportId, filePath, fileSize, status(active/archived), createdAt, deleted

**新增 Mapper（`src/main/java/com/fileproc/template/mapper/`）**

- `SystemTemplateMapper.java`：含 `selectActiveWithAllPaths()` 自定义查询（filePath 字段 select=false 绕过）
- `SystemModuleMapper.java`
- `SystemPlaceholderMapper.java`：含 `selectByTemplateId()` 查询
- `CompanyTemplateMapper.java`：含 `selectWithFilePath()` 自定义查询

> 注意：`system_template`、`system_module`、`system_placeholder` 三张系统级表需加入 MybatisPlusConfig 的多租户插件忽略表配置

---

### Task 3：系统模板解析器 & 服务

**`SystemTemplateParser.java`（`@Component`）**

- `parseWordTemplate(filePath)` → `List<SystemPlaceholder>`：用 POI 遍历所有段落和表格单元格，正则匹配 `\{\{([^}]+)\}\}` 提取占位符，同时识别占位符所在元素类型（文本/表格/图表/图片）
- `parseExcelTemplate(filePath, type)` → `Map<sheet, List<colName>>`：扫描 Excel 所有 Sheet 列头，建立映射关系
- `buildModules(placeholders)` → `List<SystemModule>`：按占位符前缀（数据源名称）分组为模块

**`SystemTemplateService.java`**

- `uploadAndInit(wordFile, listFile, bvdFile)`：上传三件套 → 保存文件 → 调用 Parser 解析 → 批量写入 `system_template`、`system_module`、`system_placeholder` 表
- `getActive()`：获取当前激活标准模板及完整占位符列表

---

### Task 4：系统模板接口 & 企业子模板管理

**`SystemTemplateController.java`**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/system-template/init` | 上传三件套并触发解析初始化 |
| GET | `/api/system-template/active` | 获取当前激活标准模板详情 |
| GET | `/api/system-template/placeholders` | 占位符规则列表 |

**`CompanyTemplateService.java`**

- `reverseGenerate(req)`：触发反向生成，保存子模板文件，写入 `company_template` 表
- `pageList(companyId, ...)`：分页查询企业子模板列表
- `download(id)`：下载子模板文件
- `updateContent(id, content)`：在线编辑后保存更新内容

**`CompanyTemplateController.java`**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/company-template/reverse-generate` | 反向生成企业子模板 |
| GET | `/api/company-template` | 企业子模板列表 |
| GET | `/api/company-template/download/{id}` | 下载子模板文件 |
| GET | `/api/company-template/{id}/content` | 获取子模板内容（用于在线编辑） |
| PUT | `/api/company-template/{id}/content` | 保存在线编辑后的子模板内容 |
| POST | `/api/company-template/{id}/confirm-placeholders` | 确认反向生成时的待确认占位符列表 |

---

### Task 5：企业子模板在线编辑

**功能说明**

企业子模板支持在线编辑，允许用户在系统界面中直接修改子模板内容（文本段落、表格、占位符），无需下载再上传。

**实现方案**

- 后端提供 `GET /api/company-template/{id}/content` 接口：读取子模板 Word 文件，将内容序列化为结构化 JSON（段落列表、表格结构、占位符位置），返回给前端
- 后端提供 `PUT /api/company-template/{id}/content` 接口：接收前端编辑后的结构化内容，重新写入 Word 文件，保存更新到 `company_template` 表（新增版本号或更新时间）
- 在线编辑保留段落样式（字体、字号、颜色、段落对齐），不允许修改占位符格式（占位符只读，防止错误修改）
- 编辑后的版本作为新版本保存，旧版本可回退

---

### Task 6：反向生成引擎（核心）

**`ReverseTemplateEngine.java`（`@Component`）**

核心方法：`reverse(historicalReportPath, listExcelPath, bvdExcelPath, placeholders, outputPath)`

**内部流程：**

1. `buildValueToPlaceholderMap()`：用 EasyExcel 读取清单/BVD Excel，按占位符规则（数据源-Sheet-单元格）逐一取值，构建 `实际值 → 占位符名` 映射表
2. 调用 `mergeAllRunsInDocument()`（复用现有逻辑），合并 Word 段落内被 POI 拆分的 Run
3. `replaceValueInDocument()`：遍历历史报告 Word 的所有元素：
   - **文本段落**：检查每个 Run 的文本是否匹配某个占位符对应的值，匹配则替换为 `{{占位符名}}`，保留 Run 的字体/字号/颜色等样式
   - **表格单元格**：逐行逐列扫描，精确匹配后替换为占位符
   - **可编辑图表**：扫描内嵌数据表的数值，替换为占位符标记
   - **静态图片**：记录图片位置，写入占位符关联标记，不修改图片内容
4. `normalizeNumber()`：数字格式归一化（去千分位逗号、百分比转换），再与 Excel 值比较
5. 不确定匹配（相似度低或同值多占位符）→ 记录到**待确认列表**，返回前端供人工确认
6. 人工确认接口 `POST /api/company-template/{id}/confirm-placeholders`：用户确认后完成剩余替换，生成最终子模板文件

**占位符冲突处理：**
- 同一数值对应多个占位符 → 首次匹配优先，记录 warn 日志，加入待确认列表

---

### Task 7：重构正向生成引擎

**`ReportGenerateEngine.java`（重构现有）**

- 接受 `CompanyTemplate` 对象替代旧 `Template`
- 占位符规则来源改为查询 `system_placeholder` 表（按 `systemTemplateId`）
- 数据来源改为通过 `company_template.systemTemplateId` 关联查找对应年度 Excel
- 文本/表格/图表/图片四种类型占位符分别处理
- 其余 Run 合并、字符串替换逻辑保持现有实现不变

**`ReportService.java`（修改）**

- `generateReport()`：改为查 `company_template` 而非旧 `template` 表
- `updateReport()`：上传人工修改后文档 → 重新触发反向生成 → 生成最新版子模板

---

## 六、关键技术约束

| 约束点 | 处理方式 |
|--------|---------|
| `filePath` 字段 `@TableField(select=false)` | 查询时用自定义 `@Select` 显式列出字段，沿用 `TemplateMapper.selectActiveWithFilePath` 模式 |
| 系统级表不绑定租户 | `system_template`、`system_module`、`system_placeholder` 加入 MybatisPlusConfig 多租户忽略表 |
| POI Run 拆分问题 | 替换前先合并段落内所有 Run（现有 `mergeAllRunsInDocument` 方法复用）|
| 数字格式不一致 | 归一化：去千分位逗号 + 百分比 ×100 后再比对 |
| 占位符冲突 | 首次匹配优先 + warn 日志 + 加入待确认列表 |
| 反向生成不确定内容 | 记录待确认列表返回前端，支持人工确认后完成子模板生成 |

---

## 七、完整接口清单

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/system-template/init` | 上传三件套并解析初始化（管理员）|
| GET | `/api/system-template/active` | 获取当前激活标准模板详情 |
| GET | `/api/system-template/placeholders` | 占位符规则列表 |
| POST | `/api/company-template/reverse-generate` | 反向生成企业子模板 |
| GET | `/api/company-template` | 企业子模板列表（按企业分页查询）|
| GET | `/api/company-template/download/{id}` | 下载子模板文件 |
| GET | `/api/company-template/{id}/content` | 获取子模板结构内容（在线编辑用）|
| PUT | `/api/company-template/{id}/content` | 保存在线编辑后的子模板内容 |
| POST | `/api/company-template/{id}/confirm-placeholders` | 确认反向生成待确认占位符 |
| POST | `/api/reports/generate` | 正向生成当年报告（重构）|
| POST | `/api/reports/update` | 上传人工修改文档，更新子模板（重构）|
| GET | `/api/reports` | 报告列表 |
| GET | `/api/reports/{id}/download` | 下载报告 |

---

## 八、目录结构

```
src/main/java/com/fileproc/
├── template/
│   ├── entity/
│   │   ├── SystemTemplate.java          [NEW] 系统标准模板实体
│   │   ├── SystemPlaceholder.java       [NEW] 系统级占位符规则实体（含 type: text/table/chart/image）
│   │   ├── SystemModule.java            [NEW] 系统模块实体
│   │   └── CompanyTemplate.java         [NEW] 企业子模板实体
│   ├── mapper/
│   │   ├── SystemTemplateMapper.java    [NEW]
│   │   ├── SystemPlaceholderMapper.java [NEW]
│   │   ├── SystemModuleMapper.java      [NEW]
│   │   └── CompanyTemplateMapper.java   [NEW]
│   ├── service/
│   │   ├── SystemTemplateService.java   [NEW] 系统模板管理
│   │   ├── SystemTemplateParser.java    [NEW] 标准模板解析器
│   │   └── CompanyTemplateService.java  [NEW] 企业子模板管理（含在线编辑）
│   └── controller/
│       ├── SystemTemplateController.java  [NEW]
│       └── CompanyTemplateController.java [NEW]
│
├── report/
│   ├── service/
│   │   ├── ReportGenerateEngine.java    [MODIFY] 重构正向生成引擎
│   │   ├── ReverseTemplateEngine.java   [NEW] 反向生成引擎
│   │   └── ReportService.java           [MODIFY] 调整 generate/update 流程
│   └── controller/
│       └── ReportController.java        [MODIFY] 保持现有接口
│
└── (其他模块不变)

src/main/resources/db/
└── V3__redesign_template_system.sql     [NEW] 数据库迁移脚本
```

---

## 九、开发优先级

| 优先级 | 任务 | 说明 |
|--------|------|------|
| P0 | Task 1：数据库迁移 | 所有功能的基础 |
| P0 | Task 2：实体类 & Mapper | 依赖数据库 |
| P1 | Task 3：系统模板解析器 | 系统初始化的核心 |
| P1 | Task 4：系统模板接口 & 子模板管理基础接口 | |
| P1 | Task 6：反向生成引擎 | 业务核心难点 |
| P1 | Task 7：重构正向生成引擎 | |
| P2 | Task 5：企业子模板在线编辑 | 增强功能，可后续迭代 |

---

## 十、后端调整计划（对接前端 v2 版本）

### 调整背景

前端计划（`file-processing-platform-v2_前端.md`）对业务流程和交互方式有调整，后端需要相应改造以支持新需求。

### 调整项清单

#### Adjust 1："设为当前使用版本" 功能

**问题**：前端需要支持手动切换当前使用的子模板版本

**当前逻辑**：`saveReverseResult` 自动归档旧 active 模板（一个企业只有一个 active）

**改造方案**：
1. 修改 `saveReverseResult`：新子模板设为 `active`，**不自动归档**旧模板（允许多个 active）
2. 新增 `PUT /api/company-template/{id}/set-active` 接口
3. 实现逻辑：将该子模板设为 `active`，同时将该企业其他子模板设为 `archived`

**涉及文件**：
- `CompanyTemplateService.java` — 修改 `saveReverseResult`，新增 `setActive()` 方法
- `CompanyTemplateController.java` — 新增 `setActive` 接口

---

#### Adjust 2：占位符位置信息返回

**问题**：前端需要精确定位待确认占位符在 Word 文档中的位置，以便 OnlyOffice 高亮

**当前逻辑**：`PendingConfirmItem` 只返回占位符名称和文本内容

**改造方案**：
1. 扩展 `PendingConfirmItem` 类，新增位置字段：
   - `paragraphIndex`：段落索引
   - `runIndex`：Run 索引（段落内第几个 Run）
   - `offset`：字符偏移量
   - `elementType`：元素类型（paragraph/table/chart/image）
2. `ReverseTemplateEngine` 在扫描匹配时记录每个占位符的位置信息

**涉及文件**：
- `ReverseTemplateEngine.java` — 修改 `PendingConfirmItem` 内部类，扫描时记录位置

---

#### Adjust 3：归档流程改造

**问题**：前端描述归档流程为"生成最终报告 → 删除子模板 → 报告进历史报告"

**当前逻辑**：`archive` 接口仅改状态为 `archived`，不生成报告，不删除模板

**改造方案**：
1. 修改 `CompanyTemplateService.archive()` 逻辑：
   - 使用该子模板 + 对应年度数据生成最终报告（调用 `ReportGenerateEngine`）
   - 生成成功后，将报告状态设为 `HISTORY`
   - 物理删除子模板文件（或逻辑删除）
   - 更新子模板状态为 `archived`（或物理删除记录）
2. 需要传入 `year` 参数（确定使用哪一年的数据）

**涉及文件**：
- `CompanyTemplateService.java` — 重构 `archive()` 方法
- `CompanyTemplateController.java` — 修改 `archive` 接口，支持传入 year 参数

---

#### Adjust 4：占位符确认状态持久化

**问题**：用户可能分多次确认占位符，刷新页面后不能丢失状态

**当前逻辑**：无持久化，确认状态仅存在于单次请求中

**改造方案**：
1. 新增表 `company_template_placeholder`：
   ```sql
   CREATE TABLE company_template_placeholder (
       id VARCHAR(36) PRIMARY KEY,
       company_template_id VARCHAR(36) NOT NULL COMMENT '子模板ID',
       placeholder_name VARCHAR(100) NOT NULL COMMENT '占位符名称',
       status VARCHAR(20) NOT NULL COMMENT '状态: uncertain(待确认)/confirmed(已确认)/ignored(忽略)',
       confirmed_type VARCHAR(20) COMMENT '确认后的类型: text/table/chart/image',
       position_json TEXT COMMENT '位置信息JSON',
       created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
       updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
       INDEX idx_template_id (company_template_id)
   );
   ```
2. 新增实体类 `CompanyTemplatePlaceholder.java`
3. 新增 Mapper 和 Service 层
4. 修改 `reverse-generate` 接口：生成子模板时，初始化所有占位符状态为 `uncertain`
5. 修改 `confirm-placeholders` 接口：更新占位符状态为 `confirmed` 或 `ignored`
6. 新增 `GET /api/company-template/{id}/placeholders` 接口：返回该子模板所有占位符状态列表

**涉及文件**：
- 新增 `V4__add_template_placeholder.sql` 迁移脚本
- 新增 `CompanyTemplatePlaceholder.java` 实体类
- 新增 `CompanyTemplatePlaceholderMapper.java`
- 新增 `CompanyTemplatePlaceholderService.java`
- `CompanyTemplateController.java` — 新增查询占位符状态接口
- `ReverseTemplateEngine.java` — 反向生成时初始化占位符状态记录

---

#### Adjust 5：其他接口补充

根据前端计划，补充以下接口：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/company-template/{id}/placeholders` | 获取子模板占位符状态列表（已确认/待确认）|
| PUT | `/api/company-template/{id}/set-active` | 设为当前使用版本 |
| GET | `/api/reports/{id}/status` | 轮询报告生成状态（已完成）|
| GET | `/api/company-template/{id}/content-url` | 获取子模板文件URL供OnlyOffice使用（已完成）|

---

### 调整后接口清单（完整版）

#### 系统标准模板接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/system-template/init` | 上传三件套并解析初始化（管理员）|
| GET | `/api/system-template/active` | 获取当前激活标准模板详情 |
| GET | `/api/system-template/placeholders` | 占位符规则列表 |

#### 企业子模板接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/company-template/reverse-generate` | 反向生成企业子模板 |
| GET | `/api/company-template` | 企业子模板列表 |
| GET | `/api/company-template/{id}` | 子模板详情 |
| PUT | `/api/company-template/{id}/set-active` | **新增：设为当前使用版本** |
| GET | `/api/company-template/{id}/download` | 下载子模板文件 |
| GET | `/api/company-template/{id}/content` | 获取子模板文件流 |
| GET | `/api/company-template/{id}/content-url` | 获取子模板URL供OnlyOffice使用 |
| GET | `/api/company-template/{id}/placeholders` | **新增：获取占位符状态列表** |
| PUT | `/api/company-template/{id}/content` | 保存在线编辑后的子模板 |
| POST | `/api/company-template/{id}/confirm-placeholders` | 确认占位符 |
| PUT | `/api/company-template/{id}/archive` | 归档子模板（**改造：先生成报告再删除**）|
| DELETE | `/api/company-template/{id}` | 删除子模板 |

#### 报告接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/reports/generate` | 生成报告（异步，立即返回pending）|
| POST | `/api/reports/update` | 更新报告（异步）|
| GET | `/api/reports/{id}/status` | **新增：轮询生成状态** |
| POST | `/api/reports/{id}/archive` | 归档报告 |
| GET | `/api/reports` | 报告列表 |
| GET | `/api/reports/{id}/download` | 下载报告 |

---

### 调整优先级

| 优先级 | 调整项 | 说明 |
|--------|--------|------|
| P0 | Adjust 1：设为当前使用版本 | 前端核心功能依赖 |
| P0 | Adjust 4：占位符确认状态持久化 | 前端核心功能依赖 |
| P1 | Adjust 2：占位符位置信息 | OnlyOffice定位需要 |
| P1 | Adjust 3：归档流程改造 | 业务闭环需要 |

---

## 十一、后端接口调整（对接前端V2反馈文档 backend-api-changes.md）

### 调整背景

根据前端V2反馈文档 `backend-api-changes.md`，后端需要进一步调整接口以支持前端新需求。

### 调整项清单

#### Adjust 6：反向生成接口改造（高优先级）

**问题**：当前接口要求上传3个文件（historicalReport + listFile + bvdFile），前端希望简化为只上传1个Word文件

**改造方案**：
1. 修改 `POST /api/company-template/reverse-generate` 接口：
   - 只接收 `historicalReport`（Word历史报告）一个文件
   - 根据 `year` 参数自动从数据管理模块查询该年度的清单模板（type='list'）和BVD数据（type='bvd'）
   - 使用 `DataFileMapper.selectWithFilePathByCompanyAndYear()` 获取文件路径
2. 错误处理：
   - 如果清单模板或BVD数据任一不存在，返回 400 错误：
     ```json
     { "code": 400, "message": "该年度清单模板或BVD数据缺失，请先到数据管理上传" }
     ```

**涉及文件**：
- `CompanyTemplateController.java` — 修改 `reverseGenerate()` 方法签名，移除 listFile/bvdFile 参数
- `CompanyTemplateService.java` — 新增根据年度查询数据文件并执行反向生成的逻辑

---

#### Adjust 7：content-url 接口确认（高优先级）

**问题**：前端需要可直接访问的文件URL供OnlyOffice编辑器加载使用

**当前状态**：接口 `GET /api/company-template/{id}/content-url` 已实现

**确认内容**：
- 响应格式是否符合前端要求：
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
- URL 必须是可直接访问的公开链接（或带临时token的授权链接）

**涉及文件**：
- `CompanyTemplateController.java` — 验证 `getContentUrl()` 实现

---

#### Adjust 8：占位符确认接口调整（中优先级）

**问题**：前端需要在确认占位符时传递确认后的类型（text/table/chart/image/ignore）

**改造方案**：
1. 修改 `POST /api/company-template/{id}/confirm-placeholders` 接口请求体：
   - 保留 `confirmed` 布尔字段
   - **新增** `confirmedType` 字段，可选值：text/table/chart/image/ignore
   ```json
   {
     "placeholders": [
       {
         "placeholderName": "${chart3}",
         "confirmed": true,
         "confirmedType": "chart",
         "paragraphIndex": 0,
         "runIndex": 0,
         "offset": 0
       },
       {
         "placeholderName": "${text15}",
         "confirmed": true,
         "confirmedType": "text",
         "paragraphIndex": 5,
         "runIndex": 2,
         "offset": 10
       }
     ]
   }
   ```
2. 后端处理逻辑：
   - `confirmed=true` + `confirmedType='ignore'` → 状态设为 `ignored`
   - `confirmed=true` + `confirmedType` 为其他值 → 状态设为 `confirmed`，并记录类型到 `confirmed_type` 字段
   - `confirmed=false` → 保持 `uncertain` 状态

**涉及文件**：
- `ReverseTemplateEngine.java` — 修改 `PendingConfirmItem` 内部类，添加 `confirmedType` 字段
- `CompanyTemplatePlaceholderService.java` — 修改 `confirmPlaceholders()` 方法，支持处理 `confirmedType`

---

#### Adjust 9：active 接口确认（中优先级）

**问题**：前端需要确认获取当前激活子模板接口是否符合要求

**当前状态**：接口 `GET /api/company-template/active` 已实现

**确认内容**：
- 响应格式是否符合前端要求：
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
- 用于生成报告时系统自动获取当前子模板

**涉及文件**：
- `CompanyTemplateController.java` — 验证 `listActive()` 实现

---

### 调整后接口清单（完整版V2）

#### 系统标准模板接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/system-template/init` | 上传三件套并解析初始化（管理员）|
| GET | `/api/system-template/active` | 获取当前激活标准模板详情 |
| GET | `/api/system-template/placeholders` | 占位符规则列表 |

#### 企业子模板接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/company-template/reverse-generate` | 反向生成企业子模板（**改造：只上传Word，自动关联年度数据**）|
| GET | `/api/company-template` | 企业子模板列表 |
| GET | `/api/company-template/{id}` | 子模板详情 |
| PUT | `/api/company-template/{id}/set-active` | 设为当前使用版本 |
| GET | `/api/company-template/{id}/download` | 下载子模板文件 |
| GET | `/api/company-template/{id}/content` | 获取子模板文件流 |
| GET | `/api/company-template/{id}/content-url` | 获取子模板URL供OnlyOffice使用 |
| GET | `/api/company-template/{id}/placeholders` | 获取占位符状态列表 |
| PUT | `/api/company-template/{id}/content` | 保存在线编辑后的子模板 |
| POST | `/api/company-template/{id}/confirm-placeholders` | 确认占位符（**调整：新增confirmedType字段**）|
| PUT | `/api/company-template/{id}/archive` | 归档子模板（先生成报告再删除）|
| DELETE | `/api/company-template/{id}` | 删除子模板 |

#### 报告接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/reports/generate` | 生成报告（异步，立即返回pending）|
| POST | `/api/reports/update` | 更新报告（异步）|
| GET | `/api/reports/{id}/status` | 轮询生成状态 |
| POST | `/api/reports/{id}/archive` | 归档报告 |
| GET | `/api/reports` | 报告列表 |
| GET | `/api/reports/{id}/download` | 下载报告 |

---

### 调整优先级（V2补充）

| 优先级 | 调整项 | 说明 |
|--------|--------|------|
| P0 | Adjust 6：反向生成接口改造 | 阻塞前端开发 |
| P0 | Adjust 7：content-url接口确认 | 阻塞前端开发 |
| P1 | Adjust 8：占位符确认接口调整 | 功能完善 |
| P1 | Adjust 9：active接口确认 | 功能完善 |

---

## Adjust 10：分离"当前使用"与"归档"状态

### 问题背景

当前 `company_template` 表的 `status` 字段同时承担两种职责：
1. 生命周期状态：`active`（正常）/ `archived`（已归档）
2. 当前使用状态：通过 `setActive` 接口将其他模板 `status` 改为 `archived` 来实现

这导致业务逻辑混乱，需要解耦。

### 改造方案

**新增 `is_current` 字段**：
- `status`：仅表示生命周期状态（`active`/`archived`）
- `is_current`：布尔值，表示是否为当前使用版本（用于生成报告时的默认选择）

**业务规则**：
1. **切换当前使用**：`setActive(id)` 只更新 `is_current`，不改变 `status`；切换范围限定在**同一企业同一年度**内
2. **反向生成默认当前使用**：新反向生成的子模板默认 `is_current=true`（不影响其他年度同企业模板）
3. **归档独立流程**：归档操作（生成报告 → 删除文件 → `status=archived`）同时将该模板 `is_current=false`，不影响其他模板
4. **报告生成选模板**：自动选模板时按 `companyId + year + is_current=true` 查询

### 涉及文件

| 文件 | 修改内容 |
|------|----------|
| `src/main/resources/db/V5__add_is_current.sql` | 新增迁移脚本，添加 `is_current` 字段，默认 `false`；现有 `status=active` 记录中按企业+年度每组最新一条设为 `true` |
| `CompanyTemplate.java` | 新增 `isCurrent` 字段（`Boolean` 类型） |
| `CompanyTemplateMapper.java` | 所有自定义 `@Select` SQL 字段列表补充 `is_current`；新增 `selectCurrentByCompanyAndYear` 方法（按 `companyId+tenantId+year+is_current=true` 查询，含 `file_path`） |
| `CompanyTemplateService.java` | `setActive`：改为只更新 `is_current`，先将同企业同年度其他模板 `is_current=false`，再将目标模板 `is_current=true`，不修改 `status`；`saveReverseResult`：设置 `is_current=true`；`archive`：归档时设置 `is_current=false` |
| `ReportAsyncService.java` | `asyncGenerateFile` 中自动选模板逻辑从 `selectLatestActiveByCompany` 改为调用 `selectCurrentByCompanyAndYear`（按 `year+is_current=true` 精确匹配） |

---

## 十二、企业子模板模块化管理（本次修改）

### 修改背景

为满足更细粒度的模板管理需求，需要在企业子模板层面引入**模块（Module）**概念，将占位符按业务模块进行分组管理。模块信息从Excel Sheet名称自动提取，实现模板的结构化管理和维护。

### 修改内容

#### 1. 数据库层调整

**新建 `company_template_module` 表**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VARCHAR(36) | 主键 |
| `company_template_id` | VARCHAR(36) | 所属子模板ID |
| `code` | VARCHAR(50) | 模块编码（从Sheet名转换） |
| `name` | VARCHAR(100) | 模块显示名称（Sheet原名） |
| `sort` | INT | 排序序号 |
| `created_at` | DATETIME | 创建时间 |

**扩展 `company_template_placeholder` 表**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `module_id` | VARCHAR(36) | 所属模块ID |
| `name` | VARCHAR(100) | 占位符显示名称 |
| `type` | VARCHAR(20) | 类型：text/table/chart/image/ignore |
| `data_source` | VARCHAR(50) | 数据源：list/bvd |
| `source_sheet` | VARCHAR(50) | 来源Sheet名 |
| `source_field` | VARCHAR(50) | 来源字段/单元格 |
| `description` | TEXT | 描述说明 |
| `sort` | INT | 排序序号 |

**迁移脚本**：`V6__add_company_template_module.sql`（采用幂等SQL，支持重复执行）

---

#### 2. 代码层实现

**新增实体类**：

| 文件 | 说明 |
|------|------|
| `CompanyTemplateModule.java` | 模块实体类 |
| `CompanyTemplatePlaceholder.java` | 扩展后的占位符实体类 |

**新增 Mapper 接口**：

| 文件 | 说明 |
|------|------|
| `CompanyTemplateModuleMapper.java` | 模块数据访问层 |
| `CompanyTemplatePlaceholderMapper.java` | 占位符数据访问层 |

**新增 Service 层**：

| 文件 | 说明 |
|------|------|
| `CompanyTemplateModuleService.java` | 模块管理业务逻辑 |
| `CompanyTemplatePlaceholderService.java` | 占位符管理业务逻辑 |

---

#### 3. 反向生成引擎改造

**`ReverseTemplateEngine.java` 增强**：

- **新增 `ModuleInfo` 内部类**：用于存储提取的模块信息（code, name, sort）
- **Sheet名 → 模块编码转换规则**：
  ```java
  sheetName.trim().replaceAll("[\\s\\-]+", "_").replaceAll("_+", "_").toLowerCase()
  ```
  例如："基本 信息" → "基本_信息"
- **模块提取逻辑**：`extractModules(List<String>)` 从占位符前缀去重提取模块列表
- **占位符同步**：反向生成时自动创建模块和占位符记录

---

#### 4. Controller 新增接口

**`CompanyTemplateController.java` 新增 4 个接口**：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/company-template/{templateId}/modules` | 获取子模板模块列表 |
| GET | `/api/company-template/{templateId}/modules/{moduleId}/placeholders` | 获取模块下的占位符列表 |
| PUT | `/api/company-template/{templateId}/placeholders/{placeholderId}` | 更新单个占位符信息 |
| POST | `/api/company-template/sync-placeholders` | 批量同步占位符（从已有数据重新提取） |

**请求/响应示例**：

```json
// GET /modules 响应
{
  "code": 0,
  "data": [
    {
      "id": "module-001",
      "code": "基本信息",
      "name": "基本信息",
      "sort": 1,
      "placeholderCount": 15
    }
  ]
}

// PUT /placeholders/{id} 请求
{
  "name": "企业名称",
  "type": "text",
  "dataSource": "list",
  "sourceSheet": "基本信息",
  "sourceField": "B3",
  "description": "企业注册名称"
}

// POST /sync-placeholders 请求
{
  "templateId": "template-001"
}
```

---

#### 5. Flyway 数据库迁移配置

**依赖配置**（`pom.xml`）：
```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

**配置文件**（`application.yml`）：
```yaml
flyway:
  enabled: true
  locations: classpath:db
  baseline-on-migrate: true
  validate-on-migrate: true
```

**迁移脚本清单**：

| 版本 | 文件 | 说明 |
|------|------|------|
| V1 | `V1__init.sql` | 初始建表 |
| V2 | `V2__update.sql` | 字段调整 |
| V3 | `V3__redesign_template_system.sql` | 模板系统重新设计 |
| V4 | `V4__add_company_template_placeholder.sql` | 占位符表 |
| V5 | `V5__add_is_current.sql` | 添加is_current字段 |
| V6 | `V6__add_company_template_module.sql` | 模块表及占位符表扩展 |

---

### 涉及文件清单

| 类型 | 文件路径 |
|------|----------|
| **新增** | `src/main/java/com/fileproc/template/entity/CompanyTemplateModule.java` |
| **新增** | `src/main/java/com/fileproc/template/entity/CompanyTemplatePlaceholder.java` |
| **新增** | `src/main/java/com/fileproc/template/mapper/CompanyTemplateModuleMapper.java` |
| **新增** | `src/main/java/com/fileproc/template/mapper/CompanyTemplatePlaceholderMapper.java` |
| **新增** | `src/main/java/com/fileproc/template/service/CompanyTemplateModuleService.java` |
| **新增** | `src/main/java/com/fileproc/template/service/CompanyTemplatePlaceholderService.java` |
| **新增** | `src/main/resources/db/V6__add_company_template_module.sql` |
| **修改** | `src/main/java/com/fileproc/report/service/ReverseTemplateEngine.java` |
| **修改** | `src/main/java/com/fileproc/template/controller/CompanyTemplateController.java` |
| **修改** | `pom.xml` — 添加 flyway-mysql 依赖 |
| **修改** | `src/main/resources/application.yml` — 添加 Flyway 配置 |

---

### 关键技术点

1. **幂等SQL设计**：使用 `INFORMATION_SCHEMA` 查询 + `PREPARE/EXECUTE` 动态执行，确保迁移脚本可重复执行
2. **模块编码生成**：标准化 Sheet 名称为模块编码（去空格、横杠转下划线、小写化）
3. **占位符匹配规则**：按 `module.code + placeholder_name` 唯一标识进行同步匹配
4. **批量同步逻辑**：清空旧数据 → 重新解析提取 → 批量插入新数据
