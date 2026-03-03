---
name: fix-quality-issues
overview: 按优先级修复5个代码质量问题：P0 OperationLogAspect 异步线程丢失上下文、P1 getRolePermissions 缺少 checkedIds、P2 StatsService 缺少 userCount、P3 MybatisPlusConfig 忽略表缺少 sys_log、P4 NoticeService 循环单条 insert 性能问题。
todos:
  - id: fix-p0-async-context
    content: 修复 OperationLogAspect：主线程采集 userId/userName/tenantId/ip 并传参给 @Async saveLog()
    status: completed
  - id: fix-p1-checked-ids
    content: RolePermissionMapper 新增 selectPermIdsByRoleId，RoleService.getRolePermissions 追加 checkedIds 字段
    status: completed
  - id: fix-p2-p3-simple
    content: StatsService 补充 userCount 统计；MybatisPlusConfig 忽略表追加 sys_log
    status: completed
  - id: fix-p4-batch-insert
    content: NoticeUserMapper 新增 batchInsert，NoticeService 改为批量调用；RoleService.updateRolePermissions 同步改批量
    status: completed
---

## 用户需求

对后端代码检查发现的 5 个问题，按 P0-P4 优先级全部修复。

## 产品概述

修复操作日志异步上下文丢失、角色权限回显缺字段、统计缺用户数、多租户插件配置遗漏表、通知批量写入性能等共 5 个问题，提升代码的正确性与稳定性。

## 核心功能

- **P0 修复**：OperationLogAspect 异步线程 ThreadLocal 丢失导致日志 userId/tenantId 为空
- **P1 修复**：RoleService.getRolePermissions 返回结果缺少 checkedIds 字段，前端权限树勾选回显失败
- **P2 修复**：StatsService.getStats() 注入了 userMapper 但未统计用户数，返回数据不完整
- **P3 修复**：MybatisPlusConfig 忽略表列表缺少 sys_log，异步写日志可能被错误注入租户条件
- **P4 修复**：NoticeService 发布通知时循环单条 INSERT，大用户量时性能差

## 技术栈

Spring Boot 3.2.5 + Java 17 + MyBatis-Plus 3.5.9，沿用现有代码规范。

## 实现方案

### P0 — OperationLogAspect 异步上下文修复

在 `afterReturning()` 主线程阶段提前采集所有需要的上下文值（`userId`、`userName`、`tenantId`、`ip`），以参数形式传入 `@Async saveLog()`，彻底避免异步线程读 ThreadLocal。

### P1 — checkedIds 字段补充

在 `RolePermissionMapper` 新增 `selectPermIdsByRoleId`（`JOIN sys_permission`），`getRolePermissions` 返回 Map 中同时携带 `permissions`（code 列表）和 `checkedIds`（id 列表）。

### P2 — userCount 补充

`StatsService.getStats()` 直接调用已注入的 `userMapper.selectCount(null)` 并加入返回 Map。

### P3 — sys_log 加入忽略表

`MybatisPlusConfig.IGNORE_TABLES` 追加 `"sys_log"`，防止异步日志写入时租户插件错误注入条件。

### P4 — 批量 INSERT

`NoticeUserMapper` 新增使用 MyBatis `<script>+<foreach>` 的 `batchInsert` 方法，`NoticeService` 中将 `forEach(insert)` 改为单次批量调用，同时 `RoleService.updateRolePermissions` 中同样存在的 `list.forEach(insert)` 一并改为批量插入。

## 目录结构

```
src/main/java/com/fileproc/
├── common/
│   ├── MybatisPlusConfig.java          # [MODIFY] IGNORE_TABLES 追加 "sys_log"
│   └── aspect/
│       └── OperationLogAspect.java     # [MODIFY] afterReturning 主线程采集上下文并传参，saveLog 签名增加4个参数
├── system/
│   ├── mapper/
│   │   └── RolePermissionMapper.java   # [MODIFY] 新增 selectPermIdsByRoleId 方法
│   └── service/
│       ├── RoleService.java            # [MODIFY] getRolePermissions 返回 Map 追加 checkedIds；updateRolePermissions 改批量插入
│       └── StatsService.java           # [MODIFY] getStats() 增加 userCount 统计
└── notice/
    ├── mapper/
    │   └── NoticeUserMapper.java       # [MODIFY] 新增 batchInsert 方法
    └── service/
        └── NoticeService.java          # [MODIFY] 发布通知改为调用 batchInsert
```