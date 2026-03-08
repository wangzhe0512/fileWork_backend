---
name: system-template-backend-enhancement
overview: 根据前端需求文档，对 SystemTemplate 后端进行增强：新增获取模板详情和设置激活模板接口，调整列表接口返回格式，支持参数名兼容和 moduleId 过滤。
todos:
  - id: "1"
    content: 修改 SystemTemplateController：调整 /list 返回格式，新增 /{id} 和 /{id}/set-active 接口，兼容参数名
    status: completed
  - id: "2"
    content: 修改 SystemTemplateService：新增 getById、setActive、listAllTemplates 方法，状态值改为 inactive
    status: completed
  - id: "3"
    content: 修改 SystemTemplateMapper：新增 selectAllTemplates 查询方法
    status: completed
  - id: "4"
    content: 修改 SystemPlaceholderMapper：新增 selectByTemplateIdAndModuleCode 方法
    status: completed
  - id: "5"
    content: 修改 SystemTemplate 实体：更新状态字段注释为 active/inactive
    status: completed
---

## 产品概述

根据前端需求文档，对系统标准模板管理后端进行增强，支持超管后台的标准模板管理页面功能。

## 核心功能

1. **调整模板列表接口**：`/list` 返回所有历史模板列表（含 active 和 inactive），而非仅返回当前激活模板
2. **新增获取模板详情接口**：`GET /{id}` 获取指定模板完整信息
3. **新增设置激活模板接口**：`POST /{id}/set-active` 切换激活状态，原激活模板自动变为 inactive
4. **参数名兼容**：`modules` 接口同时支持 `templateId` 和 `systemTemplateId` 参数
5. **moduleId 过滤**：`placeholders` 接口支持按 `moduleId` 过滤占位符
6. **状态值统一**：将 `archived` 统一改为 `inactive`，与前端文档保持一致

## Tech Stack

- Java 17 + Spring Boot 3.2.5
- MyBatis-Plus 3.5.9
- MySQL

## Implementation Approach

基于现有代码结构进行扩展：

1. Controller 层新增接口方法，保持与现有代码风格一致
2. Service 层新增业务方法，复用现有查询逻辑
3. Mapper 层新增 SQL 方法，使用注解方式保持简洁
4. 状态值从 `archived` 改为 `inactive`，涉及 Service 和 Entity 注释更新

## Implementation Notes

- 列表查询无需分页，直接返回全部数据（模板数量通常 < 20）
- 设置激活模板需要事务保证（先置旧为 inactive，再置新为 active）
- 参数兼容通过 `required = false` 实现，代码中做优先级处理
- moduleId 过滤通过新增 Mapper 方法实现，保持与现有代码风格一致

## Directory Structure

```
src/main/java/com/fileproc/template/
├── controller/
│   └── SystemTemplateController.java    [MODIFY] 新增3个接口，调整2个接口
├── service/
│   └── SystemTemplateService.java       [MODIFY] 新增4个业务方法，修改状态值
├── mapper/
│   ├── SystemTemplateMapper.java        [MODIFY] 新增列表查询方法
│   ├── SystemModuleMapper.java          [MODIFY] 新增按moduleId查询占位符
│   └── SystemPlaceholderMapper.java     [MODIFY] 新增按moduleCode查询方法
└── entity/
    └── SystemTemplate.java              [MODIFY] 更新状态注释 inactive
```

## Key Code Structures

```java
// SystemTemplateController 新增接口
@GetMapping("/{id}")
public R<SystemTemplate> getById(@PathVariable String id)

@PostMapping("/{id}/set-active")
public R<SystemTemplate> setActive(@PathVariable String id)

// 修改后的 list 接口返回 List<SystemTemplate>
@GetMapping("/list")
public R<List<SystemTemplate>> listAll()

// modules 接口参数兼容
@GetMapping("/modules")
public R<List<SystemModule>> listModules(
    @RequestParam(value = "templateId", required = false) String templateId,
    @RequestParam(value = "systemTemplateId", required = false) String systemTemplateId)

// placeholders 接口支持 moduleId 过滤
@GetMapping("/placeholders")
public R<List<SystemPlaceholder>> listPlaceholders(
    @RequestParam(value = "templateId", required = false) String templateId,
    @RequestParam(value = "systemTemplateId", required = false) String systemTemplateId,
    @RequestParam(value = "moduleId", required = false) String moduleId)
```