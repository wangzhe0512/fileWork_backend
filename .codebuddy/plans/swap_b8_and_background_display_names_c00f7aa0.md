---
name: swap_b8_and_background_display_names
overview: 将 `清单模板-数据表-B8` 显示名改为"公司经营背景"，将 `清单模板-公司经营背景资料` 显示名改为"公司经营背景资料"（两者互换），同步修改代码和新建 V36 SQL 迁移。
todos:
  - id: fix-java-display-names
    content: 修改 ReverseTemplateEngine.java 第217行和第310行的显示名参数（两者互换）
    status: completed
  - id: add-v36-migration
    content: 新增 V36__fix_company_background_display_names.sql 迁移脚本，UPDATE 两条记录的 display_name
    status: completed
    dependencies:
      - fix-java-display-names
---

## 用户需求

修改两个占位符注册项的 `displayName` 字段：

| placeholder_name | 当前显示名 | 目标显示名 |
| --- | --- | --- |
| 清单模板-公司经营背景资料 | 公司经营背景 | 公司经营背景资料 |
| 清单模板-数据表-B8 | 公司经营背景资料 | 公司经营背景 |


## 核心功能

- 修改 `ReverseTemplateEngine.java` 中第 217 行和第 310 行两处 `RegistryEntry` 的显示名参数（两者互换）
- 新增 Flyway 迁移脚本 `V36__fix_company_background_display_names.sql`，对数据库 `placeholder_registry` 表中对应记录执行 `UPDATE display_name`，保持代码与数据库同步

## 技术栈

- Java Spring Boot（现有项目）
- Flyway 数据库迁移（现有模式，参照 V35 等迁移脚本）
- MyBatis-Plus（现有 ORM 层）

## 实现思路

仅需对两处代码行和一个新增 SQL 迁移文件做最小化改动，完全遵循项目既有的注册表条目管理模式。无需新增任何类或接口。

### 修改点一：ReverseTemplateEngine.java

- **第 217 行**：将第二个参数由 `"公司经营背景资料"` 改为 `"公司经营背景"`
- **第 310 行**：将第二个参数由 `"公司经营背景"` 改为 `"公司经营背景资料"`

### 修改点二：新增 V36 Flyway 迁移脚本

参照 V35 的写法，对两条记录各执行一条 `UPDATE`，并加 `level = 'system' AND deleted = 0` 条件保证精准匹配，防止误更新用户自定义条目。

## 目录结构

```
src/
├── main/
│   ├── java/com/fileproc/report/service/
│   │   └── ReverseTemplateEngine.java          # [MODIFY] 第217行和第310行显示名互换
│   └── resources/db/
│       └── V36__fix_company_background_display_names.sql  # [NEW] 修正两个公司背景占位符显示名的迁移脚本
```

## 关键代码结构

V36 SQL 迁移脚本内容如下：

```sql
-- V36: 修正公司经营背景相关占位符显示名（清单模板-数据表-B8 与 清单模板-公司经营背景资料 互换）
UPDATE placeholder_registry
SET display_name = '公司经营背景'
WHERE placeholder_name = '清单模板-数据表-B8'
  AND level = 'system' AND deleted = 0;

UPDATE placeholder_registry
SET display_name = '公司经营背景资料'
WHERE placeholder_name = '清单模板-公司经营背景资料'
  AND level = 'system' AND deleted = 0;
```