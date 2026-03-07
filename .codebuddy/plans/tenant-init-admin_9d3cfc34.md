---
name: tenant-init-admin
overview: 修复演示用户密码 hash 为 test@123，并在新建租户时自动初始化管理员角色和 admin 账号（密码 tenant@123）。
todos:
  - id: gen-hash-and-fix-sql
    content: 用 node bcryptjs 生成 test@123 和 tenant@123 的正确 BCrypt hash，修复 init.sql 演示用户 hash 和注释
    status: completed
  - id: init-tenant-admin
    content: 修改 TenantService.createTenant：添加 @Transactional，注入所需 Mapper 和 PasswordEncoder，插入租户后自动初始化管理员角色（含全部 action 权限）和 admin 用户（密码 tenant@123），更新 adminCount=1
    status: completed
    dependencies:
      - gen-hash-and-fix-sql
---

## 用户需求

### 修复演示用户密码 Hash

`init.sql` 中演示用户（admin、operator）的 BCrypt hash 是错误值，注释声称密码为 `Test@123`，实际 hash 对应的是 `password` 字符串。需要：

- 生成 `test@123` 的正确 BCrypt(10) hash
- 替换 user-001（admin）和 user-002（operator）的 hash 值
- 修正注释为正确的密码说明

### 新建租户自动初始化管理员

当前 `createTenant` 仅创建租户记录，`adminCount` 始终为 0，没有任何初始账号。需要：

- 新建租户后，在同一事务内自动创建"管理员"角色（含全部 action 类型权限）
- 自动创建 `admin` 用户（密码 `tenant@123`），绑定管理员角色
- 同步更新 `adminCount = 1`
- 初始化失败时整个租户创建操作回滚

## 核心功能

- `init.sql` 演示用户密码 hash 修复（test@123）
- `TenantService.createTenant` 事务内自动初始化角色 + 用户
- 新建租户返回时 `adminCount` 正确为 1

## 技术栈

现有项目：Spring Boot 3 + MyBatis-Plus + BCrypt（Spring Security Crypto）

## 实现方案

### 1. 生成正确的 BCrypt Hash

利用项目根目录已安装的 `bcryptjs`（node_modules），通过 node 命令生成：

- `test@123` → 替换 init.sql 演示用户 hash
- `tenant@123` → 用于 TenantService 自动初始化用户

### 2. TenantService 初始化逻辑

在 `createTenant` 方法上添加 `@Transactional(rollbackFor = Exception.class)`，插入租户后：

1. 查询 `sys_permission` 所有 `type='action'` 的权限 code（复用 PermissionMapper）
2. 创建 `SysRole`（name=管理员, code=admin），插入 `sys_role`（复用 RoleMapper）
3. 批量插入角色权限关联到 `sys_role_permission`（复用 RolePermissionMapper.batchInsert）
4. 创建 `SysUser`（username=admin, password=BCrypt(tenant@123)），插入 `sys_user`（复用 UserMapper）
5. 更新 `sys_tenant.admin_count = 1`（直接 set 字段）

注入依赖：`RoleMapper`、`RolePermissionMapper`、`PermissionMapper`、`UserMapper`、`PasswordEncoder`（Spring Security 已配置的 Bean）

### 性能说明

- 权限列表查询一次，批量插入，无 N+1 问题
- 全程在同一事务内，原子操作，失败完整回滚

## 目录结构

```
src/
├── main/
│   ├── java/com/fileproc/tenant/service/
│   │   └── TenantService.java          # [MODIFY] 添加事务注解 + 初始化角色/用户逻辑，注入5个新依赖
│   └── resources/db/
│       └── init.sql                    # [MODIFY] 替换演示用户密码hash为test@123正确值，修正注释
```