---
name: reverse-engine-bug-fix
overview: 修复反向报告生成引擎的三个代码质量问题：FileInputStream资源泄漏、actualValue记录时机错误、模块信息提取方式与新引擎不匹配导致数据表字段占位符无法入库。
todos:
  - id: fix-resource-leak
    content: 修复 ReverseTemplateEngine.readSheetNames() FileInputStream 资源泄漏
    status: completed
  - id: fix-actual-value
    content: 修复 ReverseTemplateEngine.replaceInRunsNew() actualValue 记录时机
    status: completed
  - id: fix-module-extract
    content: 重写 CompanyTemplateController.initModulesAndPlaceholders() 模块提取逻辑
    status: completed
---

## 用户需求

修复上一轮代码审查发现的三个质量问题：

1. `readSheetNames()` 中 `FileInputStream` 未正确关闭（资源泄漏风险）
2. `replaceInRunsNew()` 中 `actualValue` 记录时机偏移（可能记录已修改的文本）
3. `initModulesAndPlaceholders()` 中模块信息提取方式与新引擎不匹配（导致数据表字段占位符无法入库）

## 产品概述

保持现有反向报告生成引擎功能不变，修复代码缺陷，确保：

- 资源正确释放
- 数据记录准确
- 模块/占位符持久化逻辑与新引擎生成的数据结构兼容

## 核心功能

- 修复资源泄漏问题
- 修复 actualValue 记录时机
- 重写模块信息提取逻辑，直接从 `MatchedPlaceholder` 字段聚合，不再依赖 `extractModules` 解析占位符名

## Tech Stack Selection

沿用现有项目栈：Spring Boot + Apache POI + EasyExcel，**零新依赖**。

## Implementation Approach

### 问题1：资源泄漏修复

`readSheetNames()` 方法中 `new FileInputStream(filePath)` 需用 try-with-resources 包裹，确保构造异常时也能关闭。

### 问题2：actualValue 记录时机

在 `replaceInRunsNew()` 方法中，进入替换循环前保存 `String originalText = run.getText(0)`，后续 `addMatchedRecord` 使用 `originalText` 而非 `run.getText(0)`。

### 问题3：模块信息提取重构

**根本原因**：新引擎生成的占位符名格式与旧引擎不同：

- 旧：`清单模板-数据表-B3`（可被旧正则解析）
- 新：`企业名称`（纯字段名，无分隔符）或 `行业情况-B1`（Sheet名-单元格）

`extractModules()` 使用正则 `^([^-]+)-(.*?)([A-Za-z]+\d+) 无法匹配纯字段名，导致解析为"默认模块"，进而 `codeToModuleId.get(moduleCode)` 返回 null，占位符记录被跳过。

**解决方案**：`initModulesAndPlaceholders()` 不再调用 `extractModules()`，改为直接从 `matchedList` 聚合模块信息：

```java
// 从 matchedList 直接提取模块信息（code -> name）
Map<String, String> moduleCodeToName = new LinkedHashMap<>();
for (MatchedPlaceholder matched : matchedList) {
    moduleCodeToName.put(matched.getModuleCode(), matched.getModuleName());
}
// 按出现顺序创建模块
int sort = 0;
for (Map.Entry<String, String> entry : moduleCodeToName.entrySet()) {
    CompanyTemplateModule module = moduleService.getOrCreate(
        templateId, entry.getKey(), entry.getValue(), sort++);
    codeToModuleId.put(entry.getKey(), module.getId());
}
```

### 性能考量

无性能影响，改动均为局部逻辑修正。

## Implementation Notes

- 保持 `extractModules()` 方法不动（大模型分支可能仍依赖它）
- 仅修改 `initModulesAndPlaceholders()` 内部实现
- 使用 `LinkedHashMap` 保持模块顺序与 `matchedList` 中出现顺序一致

## Directory Structure

```
src/main/java/com/fileproc/
├── report/service/
│   └── ReverseTemplateEngine.java         # [MODIFY]
│       # 1. readSheetNames: FileInputStream try-with-resources
│       # 2. replaceInRunsNew: 保存 originalText，修正 addMatchedRecord 调用
│
└── template/controller/
    └── CompanyTemplateController.java     # [MODIFY]
        # 1. initModulesAndPlaceholders: 重写模块提取逻辑，不再调用 extractModules
```