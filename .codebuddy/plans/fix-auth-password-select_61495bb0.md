---
name: fix-auth-password-select
overview: 修复 AuthService 登录查询因 @TableField(select=false) 导致 password 字段为 null，BCrypt 比对报 Empty encoded password 的 bug
todos:
  - id: add-select-with-password
    content: 在 UserMapper 新增 selectForLogin 方法，SQL 显式包含 password 字段
    status: completed
  - id: fix-authservice-login
    content: 修改 AuthService.login，将 LambdaQueryWrapper 查询替换为 userMapper.selectForLogin
    status: completed
    dependencies:
      - add-select-with-password
---

## 问题根因

`SysUser.password` 字段标注了 `@TableField(select = false)`，其设计目的是接口响应不暴露密码。但 MyBatis-Plus 的 `LambdaQueryWrapper` 自动生成 SQL 时同样受此影响，SELECT 语句不包含 `password` 列，导致 `user.getPassword()` 返回 `null`，`BCryptPasswordEncoder.matches(rawPwd, null)` 触发 "Empty encoded password" 异常，登录返回 401 密码错误。

## 核心功能

- `UserMapper` 新增 `selectForLogin` 专用查询方法，手写 SQL 显式包含 `password` 字段
- `AuthService.login` 改用 `selectForLogin`，确保登录时能取到密码字段完成 BCrypt 校验
- 保留 `@TableField(select = false)` 注解不变，普通查询（列表、详情）继续不返回密码，接口安全性不受影响

## 技术栈

现有项目：Spring Boot 3 + MyBatis-Plus + BCrypt（Spring Security Crypto）

## 实现方案

MyBatis-Plus 的 `@TableField(select = false)` 只对框架自动生成的 SQL 生效，对 `@Select` 注解手写的 SQL **无效**。因此在 `UserMapper` 新增一个手写 SQL 的查询方法，显式写出 `password` 列，登录时改用此方法即可，零侵入其他逻辑。

### 方案对比

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| 去掉 `@TableField(select=false)` | 改动最少 | 破坏安全性：所有 selectById/selectList 都会返回密码 |
| 新增 `selectForLogin` 手写 SQL | 精确、零副作用、不影响其他查询 | 多一个方法 |
| `QueryWrapper.select("id,...,password")` | 不新增方法 | 字符串列名易拼错，类型不安全 |


**选择方案二**：新增 `selectForLogin`，在 `@Select` 手写 SQL 中显式包含 password 列，完全不影响其他接口的安全性。

## 实现细节

### UserMapper 新增方法

```java
@Select("SELECT id, tenant_id, username, real_name, password, role_id, status, created_at " +
        "FROM sys_user WHERE username = #{username} AND tenant_id = #{tenantId} AND deleted = 0")
SysUser selectForLogin(@Param("username") String username, @Param("tenantId") String tenantId);
```

### AuthService.login 修改（第62-66行）

将：

```java
SysUser user = userMapper.selectOne(
    new LambdaQueryWrapper<SysUser>()
        .eq(SysUser::getUsername, username)
        .eq(SysUser::getTenantId, tenantId)
);
```

替换为：

```java
SysUser user = userMapper.selectForLogin(username, tenantId);
```

## 目录结构

```
src/main/java/com/fileproc/
├── system/mapper/
│   └── UserMapper.java          # [MODIFY] 新增 selectForLogin(@Param username, tenantId) 方法，手写 SQL 显式 SELECT password
└── auth/service/
    └── AuthService.java         # [MODIFY] login 方法第62-66行改用 userMapper.selectForLogin(username, tenantId)
```