---
name: fix-placeholder-group-vo-phtype
overview: 在 PlaceholderGroupVO 中新增 phType 字段，返回原始注册表类型值（如 TABLE_ROW_TEMPLATE），供前端精确判断占位符类型。
todos:
  - id: add-phtype-to-group-vo
    content: 在 PlaceholderGroupVO 中新增 phType 字段和 getter，更新构造函数签名；在 listWithBindingStatus 方法中新增 phTypeCache 并复用注册表查询同时缓存 phType，传入 PlaceholderGroupVO 构造
    status: completed
---

## 用户需求

前端 `PlaceholderBindingVO` 定义了 `phType` 字段，但后端接口 `/placeholders/binding-status` 实际返回的 `PlaceholderGroupVO` JSON 中只有 `type`（值为 `"table"` / `"text"`），缺少 `phType` 原始值（如 `TABLE_ROW_TEMPLATE`），导致前端判断 `item.phType === 'TABLE_ROW_TEMPLATE'` 永远为 `undefined`，相关按钮永远不显示。

## 产品概述

后端接口补充返回 `phType` 原始值字段，使前端能正确区分占位符的具体类型，解决按钮显示逻辑失效的问题。

## 核心特性

- `PlaceholderGroupVO` 新增 `phType` 字段，返回注册表原始类型值（`TABLE_ROW_TEMPLATE` / `TABLE_CLEAR` / `LONG_TEXT` 等）
- 保持现有 `type` 字段（`table` / `text`）不变，向后兼容
- 查不到注册表时 `phType` 返回 `null`，不影响已有功能

## 技术栈

Spring Boot + MyBatis-Plus，现有后端 Java 项目，修改单一文件。

## 实现方案

在 `listWithBindingStatus` 方法中，已有 `levelCache` 通过 `placeholderRegistryMapper.selectEffectiveByName` 查注册表，目前只取了 `level` 字段。`phType` 原始值同样在该 `PlaceholderRegistry` 对象中，可在同一次查询时一并缓存，**无需任何额外数据库查询**，零性能开销。

## 实现细节

1. 新增 `phTypeCache`（`Map<String, String>`），与 `levelCache` 在同一个 `computeIfAbsent` lambda 中同时填充
2. `PlaceholderGroupVO` 新增 `phType` 字段（`private final String phType`）、构造参数、getter
3. 构建 `PlaceholderGroupVO` 时从 `phTypeCache` 取值，查不到则传 `null`
4. 不修改 `mapPhType`、`type` 字段、数据库表结构，完全向后兼容

## 目录结构

```
src/main/java/com/fileproc/template/service/
└── CompanyTemplatePlaceholderService.java  # [MODIFY] 三处修改：phTypeCache逻辑、PlaceholderGroupVO字段/构造/getter、构造调用传入phType
```