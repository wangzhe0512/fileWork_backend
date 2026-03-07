---
name: fix-sys-user-missing-columns
overview: 修复 sys_user 表缺少 last_login_at、last_login_ip 列导致登录时 UPDATE 报 Unknown column 错误
todos:
  - id: fix-init-sql-columns
    content: 修复 init.sql 的 sys_user 建表语句，补充 updated_at、last_login_at、last_login_ip 三列
    status: completed
  - id: fix-sysuser-entity
    content: SysUser.java 补充 updatedAt、lastLoginAt、lastLoginIp 三个字段，与表结构保持一致
    status: completed
    dependencies:
      - fix-init-sql-columns
  - id: commit-and-alter-guide
    content: 提交代码并输出 ECS ALTER TABLE 补列命令
    status: completed
    dependencies:
      - fix-sysuser-entity
---

## 用户需求

登录后 ECS 报错 `Unknown column 'last_login_at' in 'field list'`，原因是 `sys_user` 表建表时缺少 `last_login_at`、`last_login_ip`、`updated_at` 三列，与 `UserMapper` 中的 SQL 不匹配。需要修复建表 SQL、同步实体字段，并提供 ECS 现有数据库的 ALTER TABLE 补列命令。

## 产品概述

修复数据库表结构与代码不一致的 bug，使登录流程（updateLastLogin + selectPageWithRole）能正常执行。

## 核心功能

- `init.sql` 的 `sys_user` 建表补充三列：`updated_at`、`last_login_at`、`last_login_ip`
- `SysUser.java` 实体同步补充对应 Java 字段，保持与 SQL 一致
- 提供 ECS 现有库的 ALTER TABLE 补列命令

## 技术栈

现有项目：Spring Boot 3 + MyBatis-Plus + MySQL

## 实现方案

`UserMapper` 中 `selectPageWithRole` 和 `updateLastLogin` 两处 SQL 引用了 `sys_user` 表中不存在的列，属于表结构与代码不同步的典型问题。直接在 `init.sql` 补列、实体同步字段即可，无需修改任何业务逻辑代码。

## 实现细节

- `init.sql` 补列位置：在 `created_at` 行之后、`deleted` 行之前插入三列
- `SysUser.java` 字段顺序：`createdAt` 之后、`deleted` 之前补充，保持与建表顺序一致
- ECS 现有数据库需手动执行 ALTER TABLE，不影响现有数据

## 目录结构

```
src/
├── main/
│   ├── java/com/fileproc/system/entity/
│   │   └── SysUser.java     # [MODIFY] 补充 updatedAt、lastLoginAt、lastLoginIp 字段
│   └── resources/db/
│       └── init.sql         # [MODIFY] sys_user 建表补充三列
```