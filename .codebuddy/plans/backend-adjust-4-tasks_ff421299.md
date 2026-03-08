---
name: backend-adjust-4-tasks
overview: 执行后端4个Adjust任务：1)设为当前使用版本 2)占位符位置信息 3)归档流程改造 4)占位符确认状态持久化
todos:
  - id: adjust1-set-active
    content: Adjust 1：修改 saveReverseResult 不自动归档，新增 setActive 接口
    status: completed
  - id: adjust2-position
    content: Adjust 2：扩展 PendingConfirmItem，补充位置信息字段
    status: completed
  - id: adjust3-archive
    content: Adjust 3：改造归档流程，生成报告后删除子模板
    status: completed
    dependencies:
      - adjust1-set-active
  - id: adjust4-placeholder-table
    content: Adjust 4：新建 V4 迁移脚本和 CompanyTemplatePlaceholder 实体类
    status: completed
  - id: adjust4-placeholder-service
    content: Adjust 4：新建 Service 和 Mapper，修改反向生成和确认接口
    status: completed
    dependencies:
      - adjust4-placeholder-table
---

## 用户需求

根据前端 v2 版本计划，后端需要完成 4 个 Adjust 任务：

1. **Adjust 1**：新增"设为当前使用版本"功能（`PUT /company-template/{id}/set-active`）
2. **Adjust 2**：`PendingConfirmItem` 补充位置信息（paragraphIndex, runIndex, offset 等）
3. **Adjust 3**：改造归档流程（生成报告 → 删除子模板）
4. **Adjust 4**：新增 `company_template_placeholder` 表，持久化占位符确认状态

## 当前代码状态

- 基础架构已完成（V3 迁移脚本、实体类、Mapper、异步生成等）
- `saveReverseResult` 当前会自动归档旧 active 模板（需要改造）
- `PendingConfirmItem` 当前只有基础字段（需要扩展）
- `archive` 方法当前仅改状态（需要改造）
- `company_template_placeholder` 表不存在（需要新建）

## 技术栈

- Java 17 + Spring Boot 3
- MyBatis-Plus + MySQL
- POI（Word/Excel 处理）
- 现有代码规范：Lombok、`@RequiredArgsConstructor`、`BizException` 异常处理

## 实现方案

### Adjust 1：设为当前使用版本

1. 修改 `CompanyTemplateService.saveReverseResult()`：去掉自动归档旧模板逻辑，允许多个 active
2. 新增 `CompanyTemplateService.setActive()`：将指定模板设为 active，其他设为 archived
3. 新增 `CompanyTemplateController.setActive()` 接口

### Adjust 2：占位符位置信息

1. 扩展 `ReverseTemplateEngine.PendingConfirmItem`，新增：

- `paragraphIndex`：段落索引
- `runIndex`：Run 索引
- `offset`：字符偏移
- `elementType`：元素类型（paragraph/table/chart/image）

2. 修改 `ReverseTemplateEngine` 扫描逻辑，记录位置信息

### Adjust 3：归档流程改造

1. 修改 `CompanyTemplateService.archive()`：

- 调用 `ReportGenerateEngine` 生成最终报告
- 报告状态设为 HISTORY
- 物理删除子模板文件
- 逻辑删除子模板记录（或改状态为 archived）

### Adjust 4：占位符确认状态持久化

1. 新建 `V4__add_template_placeholder.sql` 迁移脚本
2. 新建 `CompanyTemplatePlaceholder` 实体类
3. 新建 `CompanyTemplatePlaceholderMapper`
4. 新建 `CompanyTemplatePlaceholderService`
5. 修改 `reverse-generate`：初始化占位符状态记录
6. 修改 `confirm-placeholders`：更新状态
7. 新增 `GET /company-template/{id}/placeholders` 接口

## 目录结构

```
src/main/resources/db/
└── V4__add_template_placeholder.sql          [NEW]

src/main/java/com/fileproc/template/
├── entity/
│   └── CompanyTemplatePlaceholder.java       [NEW]
├── mapper/
│   └── CompanyTemplatePlaceholderMapper.java [NEW]
├── service/
│   ├── CompanyTemplatePlaceholderService.java [NEW]
│   └── CompanyTemplateService.java           [MODIFY]
├── controller/
│   └── CompanyTemplateController.java        [MODIFY]
└── (其他已有文件)

src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java                [MODIFY]