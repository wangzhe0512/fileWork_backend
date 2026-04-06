---
name: fix-segment-data-sheet-name
overview: 为两个 TABLE_CLEAR_FULL 占位符补全 sheet_name，并同步修正 ReverseTemplateEngine.java 中代码与数据库不一致的问题
todos:
  - id: sql-v31
    content: 新建 V31__fix_segment_financial_sheet_name.sql，UPDATE 补全 清单模板-3_分部财务数据 的 sheet_name
    status: completed
  - id: fix-reverse-engine
    content: 修改 ReverseTemplateEngine.java：第303行补 sheet_name，第305行改为 LONG_TEXT 6参数构造
    status: completed
    dependencies:
      - sql-v31
---

## 用户需求

修复两个占位符的历史遗留问题，使代码与数据库保持一致：

### 问题一：`清单模板-3_分部财务数据`

- **现状**：数据库 `sheet_name=NULL`，代码也是 `null`，两者一致但都缺失
- **修复目标**：新增 V31 SQL 补全 `sheet_name='3 分部财务数据'`，同步修改 ReverseTemplateEngine.java 代码（第303行 `null` → `"3 分部财务数据"`）
- **类型维持**：`TABLE_CLEAR_FULL` 不变（多级表头复杂财务表，不适合升级为 TABLE_ROW_TEMPLATE）

### 问题二：`清单模板-公司经营背景资料`

- **现状**：V16 SQL 已将数据库改为 `LONG_TEXT`（`sheet_name='公司经营背景资料'`, `cell_address='A1'`），但代码第305行仍是 `TABLE_CLEAR_FULL`，代码与数据库不一致
- **修复目标**：只改 ReverseTemplateEngine.java 代码，将注册类型从 `TABLE_CLEAR_FULL`（7参数构造方法）改为 `LONG_TEXT`（6参数构造方法），无需新增 SQL

## 核心功能

- 新建 V31 SQL 脚本，UPDATE `清单模板-3_分部财务数据` 补全 sheet_name
- 修改 ReverseTemplateEngine.java 两处注册条目，使代码与数据库对齐

## 技术栈

- **语言**：Java（Spring Boot 项目），SQL（Flyway 迁移脚本）
- **修改文件**：
- `src/main/resources/db/V31__fix_segment_financial_sheet_name.sql`（新建）
- `src/main/java/com/fileproc/report/service/ReverseTemplateEngine.java`（修改第303~306行）

## 实现方案

### V31 SQL（问题一）

参照 V28/V29/V30 模式，使用带 `AND ph_type='TABLE_CLEAR_FULL'` 条件的 UPDATE，防止重复执行时误改：

```sql
UPDATE placeholder_registry
SET sheet_name = '3 分部财务数据', updated_at = NOW()
WHERE placeholder_name = '清单模板-3_分部财务数据'
  AND ph_type = 'TABLE_CLEAR_FULL'
  AND deleted = 0;
```

### ReverseTemplateEngine.java 修改（问题一 + 问题二）

**问题一**（第303行）：7参数构造，第5参数 `null` → `"3 分部财务数据"`

```java
// 修改前
reg.add(new RegistryEntry("清单模板-3_分部财务数据", "分部财务数据", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
        List.of("分部财务", "分部数据", "分部财务数据")));
// 修改后
reg.add(new RegistryEntry("清单模板-3_分部财务数据", "分部财务数据", PlaceholderType.TABLE_CLEAR_FULL, "list", "3 分部财务数据", null,
        List.of("分部财务", "分部数据", "分部财务数据")));
```

**问题二**（第305行）：从 7参数 TABLE_CLEAR_FULL 改为 6参数 LONG_TEXT（去掉 titleKeywords 参数）

```java
// 修改前
reg.add(new RegistryEntry("清单模板-公司经营背景资料", "公司经营背景", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
        List.of("经营背景", "公司背景", "背景资料", "经营情况")));
// 修改后
reg.add(new RegistryEntry("清单模板-公司经营背景资料", "公司经营背景", PlaceholderType.LONG_TEXT, "list", "公司经营背景资料", "A1"));
```

## 实现注意事项

- V31 SQL 文件名格式须严格遵循 `V{n}__{描述}.sql` 命名规范（Flyway）
- 两个 `null` 位置：7参数构造方法签名为 `(name, display, type, source, sheetName, cellAddress, titleKeywords)`，第5参数是 sheetName，第6参数是 cellAddress（TABLE_CLEAR_FULL 不用 cellAddress，保持 null）
- `公司经营背景资料` 改为 LONG_TEXT 后，titleKeywords 自动为 null（6参数构造方法默认），无需保留关键词列表
- 两处修改范围极小，不影响其他任何占位符逻辑

## 目录结构

```
src/
├── main/
│   ├── resources/db/
│   │   └── V31__fix_segment_financial_sheet_name.sql   # [NEW] UPDATE 补全 清单模板-3_分部财务数据 的 sheet_name
│   └── java/com/fileproc/report/service/
│       └── ReverseTemplateEngine.java                  # [MODIFY] 第303行补 sheet_name，第305行改为 LONG_TEXT 6参数
```