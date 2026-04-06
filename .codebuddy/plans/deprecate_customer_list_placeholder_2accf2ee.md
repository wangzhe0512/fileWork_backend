---
name: deprecate_customer_list_placeholder
overview: 将 `清单模板-5_客户清单`（TABLE_CLEAR_FULL）标记为废弃：注释掉代码注册行，并新建 V34 SQL 将数据库记录软删除。
todos:
  - id: deprecate-code
    content: 在 ReverseTemplateEngine.java 中注释废弃 清单模板-5_客户清单 注册行，添加 DEPRECATED 说明
    status: completed
  - id: deprecate-db
    content: 新建 V34__deprecate_customer_list_clear_full.sql，对 清单模板-5_客户清单 执行软删除
    status: completed
    dependencies:
      - deprecate-code
---

## 用户需求

将占位符 `清单模板-5_客户清单`（TABLE_CLEAR_FULL 类型，sourceSheet=null）标记废弃，与已废弃的 `清单模板-4_供应商清单` 保持一致处理方式。

## 废弃原因

- `sourceSheet = null` 导致生成引擎读取第 0 个 Sheet（数据表），无法正确获取客户清单数据
- 实际的客户数据已由 `清单模板-5_客户清单-关联销售明细`（TABLE_ROW_TEMPLATE 类型）负责处理
- 与已废弃的 `清单模板-4_供应商清单` 原因完全一致，属于对称处理

## 核心功能

1. **代码层**：在 `ReverseTemplateEngine.java` 中将第 270~271 行的注册代码注释掉，添加 `[DEPRECATED]` 说明注释，格式与第 272~274 行保持一致
2. **数据库层**：新建 `V34__deprecate_customer_list_clear_full.sql`，对 `placeholder_name='清单模板-5_客户清单'` 做软删除（`SET deleted=1`）

## 技术栈

现有 Java Spring Boot 项目 + Flyway 数据库迁移。

## 实现方案

### 代码修改（ReverseTemplateEngine.java 第 270~271 行）

将以下代码注释掉，并在上方加 `[DEPRECATED]` 说明，与第 272~274 行已废弃的 `清单模板-4_供应商清单` 保持完全一致的格式：

**修改前：**

```java
reg.add(new RegistryEntry("清单模板-5_客户清单",            "客户清单",   PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
        List.of("客户清单", "主要客户", "前五大客户", "主要客户情况")));
// [DEPRECATED] 清单模板-4_供应商清单（TABLE_CLEAR_FULL，sourceSheet=null，生成引擎无填充逻辑，暂时废弃）
// reg.add(new RegistryEntry("清单模板-4_供应商清单", ...));
```

**修改后：**

```java
// [DEPRECATED] 清单模板-5_客户清单（TABLE_CLEAR_FULL，sourceSheet=null，生成引擎无填充逻辑，暂时废弃；客户数据由 清单模板-5_客户清单-关联销售明细 处理）
// reg.add(new RegistryEntry("清单模板-5_客户清单",            "客户清单",   PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
//         List.of("客户清单", "主要客户", "前五大客户", "主要客户情况")));
// [DEPRECATED] 清单模板-4_供应商清单（TABLE_CLEAR_FULL，sourceSheet=null，生成引擎无填充逻辑，暂时废弃）
// reg.add(new RegistryEntry("清单模板-4_供应商清单", ...));
```

### 数据库迁移（V34 新增）

新建 `V34__deprecate_customer_list_clear_full.sql`，执行软删除：

```sql
UPDATE placeholder_registry
SET deleted = 1
WHERE placeholder_name = '清单模板-5_客户清单'
  AND level = 'system' AND deleted = 0;
```

## 目录结构

```
src/
├── main/
│   ├── java/com/fileproc/report/service/
│   │   └── ReverseTemplateEngine.java         # [MODIFY] 注释废弃第 270~271 行注册代码
│   └── resources/db/
│       └── V34__deprecate_customer_list_clear_full.sql  # [NEW] 软删除数据库中的对应记录
```