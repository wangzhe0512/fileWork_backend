---
name: fix-displayname-in-placeholder-group-vo
overview: 在 PlaceholderGroupVO 的 name 字段改为从注册表 displayName 获取，修复当前 name 与 placeholderName 返回值相同的问题
todos:
  - id: fix-display-name
    content: 修复 CompanyTemplatePlaceholderService.listWithBindingStatus 中 name 字段，优先取注册表 displayName，fallback 到 first.getName()
    status: completed
---

## 用户需求

`/binding-status` 接口返回的 `PlaceholderGroupVO` 中，`name` 字段和 `placeholderName` 字段值相同，`name` 未能返回真正的可读展示名（如"企业名称"、"BVD-SummaryYear可比公司列表"）。

## 产品概述

修复 `name` 字段的数据来源，使其优先返回 `placeholder_registry` 表中对应条目的 `display_name`，当注册表中查不到时回退使用 `company_template_placeholder.name`。

## 核心功能

- 在 `listWithBindingStatus` 方法中新增 `displayNameCache`，复用已有注册表查询逻辑，同步缓存 `reg.getDisplayName()`
- 构建 `PlaceholderGroupVO` 时，将 `name` 参数由 `first.getName()` 改为优先取注册表 `displayName`，查不到则 fallback 到 `first.getName()`
- `PlaceholderGroupVO` 类本身无需新增字段，只改数据来源

## 技术栈

- Spring Boot + Java（现有项目，无新依赖）

## 实现方案

与已有的 `phTypeCache`、`registryItemIdCache` 完全一致的缓存模式：在方法开头声明 `Map<String, String> displayNameCache`，在 `levelCache.computeIfAbsent` 的 lambda 内（`reg != null` 分支）同时调用 `displayNameCache.put(name, reg.getDisplayName())`，最后构建 VO 前取出并加 fallback。

**零额外数据库查询**：复用已有的 `selectEffectiveByName` 单次调用，所有字段在同一次查询中并行缓存。

## 实现细节

- `reg.getDisplayName()` 可能为 `null`（部分记录未设置），故 fallback 逻辑需判断 `!= null && !blank`
- 不修改 `PlaceholderGroupVO` 的任何字段、构造参数、getter，改动范围最小
- 不影响其他调用方

## 目录结构

```
src/main/java/com/fileproc/template/service/
└── CompanyTemplatePlaceholderService.java  # [MODIFY] 在 listWithBindingStatus 方法中：
                                             # 1. 新增 displayNameCache 声明（第422行后）
                                             # 2. lambda 内填充 displayNameCache（第455行后）
                                             # 3. 构建 VO 前解析 resolvedName 并替换 first.getName()
```