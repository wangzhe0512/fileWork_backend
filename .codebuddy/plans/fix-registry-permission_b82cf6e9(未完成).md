---
name: fix-registry-permission
overview: 新增 V10 数据库迁移脚本，将 registry:list/registry:edit 权限写入 sys_permission 表，并给所有现有管理员角色补充授权；同时修复 TenantService 中新建租户时的权限同步逻辑（当前逻辑本身无误，但需移除手动插入的脏数据并统一用迁移脚本管理）。
todos:
  - id: create-v10-migration
    content: 新建 V10__add_registry_permissions.sql，清理脏数据、规范插入权限定义、补全所有管理员角色授权
    status: pending
---

## 用户需求

修复占位符注册表接口（`/placeholder-registry/effective`）持续返回 403 权限不足的问题。

## 问题根因

1. **V9 迁移脚本遗漏**：V9 新增了 `PlaceholderRegistryController`，使用了 `registry:list` / `registry:edit` 两个权限，但没有在 `sys_permission` 表中定义这两条权限记录
2. **历史数据脏乱**：用户通过手工 SQL 插入了这两条权限（UUID 作为 id），并仅给 `role-001` 授权，而实际登录用户的角色是通过 `TenantService.initTenantAdmin()` 创建的（UUID 格式角色 id），没有获得授权
3. **系统性缺陷**：新增权限时，现有租户管理员角色不会自动同步新权限

## 核心功能

- 新建 `V10` Flyway 迁移脚本，规范化插入 `registry:list` / `registry:edit` 权限定义，并补全所有现有管理员角色的授权
- 清理脏数据（删除之前手工插入的 UUID id 的权限记录），用固定 `perm-a-xx` 格式 id 重新插入
- 补全所有 `code='admin'` 的角色（包括 role-001 和所有 UUID 格式管理员角色）对这两个权限的授权

## 技术栈

- **数据库迁移**：Flyway SQL 迁移脚本（与现有 V1~V9 保持一致）
- **数据库**：MySQL 8.x

## 实施方案

### 核心策略

新建 `V10__add_registry_permissions.sql` 迁移脚本，一次性处理以下三件事：

1. **清理脏数据**：删除之前手工以 UUID 为 id 插入的 `registry:list` / `registry:edit` 权限记录（通过 `code` 字段精确定位，id 不确定所以按 code 删）
2. **规范化插入权限定义**：用固定 id `perm-a-20` / `perm-a-21` 插入到 `sys_permission`（`init.sql` 最大到 `perm-a-19`，V10 顺序接续）
3. **补全现有角色授权**：给所有 `code='admin'` 的管理员角色补充这两个权限的授权，用 `NOT IN` 子查询防止重复插入

### 关键决策

- **用固定 id 而非 UUID**：与 `init.sql` 的 `perm-a-xx` 命名规范保持一致，便于后续排查和维护
- **先删后插**：由于之前手工插入的记录 id 是 UUID，直接 `INSERT IGNORE` 无法覆盖（主键不同），需先按 `code` 删除再重插
- **`sys_role_permission` 补全用 `NOT EXISTS` 子查询**：防止对已有授权的角色重复插入（`role-001` 已有授权的情况）
- **不修改 `TenantService` 代码**：该逻辑本身正确（查全量 action 权限授权），只要 `sys_permission` 里有正确的记录，新建租户时会自动包含；本次只需修数据

## 实现细节

### `sys_permission` 表 perm-a 编号现状

- `init.sql` 定义了 `perm-a-01` ~ `perm-a-19`（19个操作权限）
- 数据库里已有通过手工 UUID 插入的 `registry:list` / `registry:edit`，需先删除
- V10 使用 `perm-a-20` / `perm-a-21`，顺序接续

### `sys_role_permission` 补全逻辑

- 给所有 `sys_role` 中 `code='admin'` 的角色补权限
- 使用 `INSERT INTO ... SELECT` + `NOT EXISTS` 子查询防重复
- `id` 字段用 `UUID()` 生成

## 目录结构

```
src/main/resources/db/
└── V10__add_registry_permissions.sql  # [NEW] 修复 registry 权限定义及现有管理员角色授权补全
```