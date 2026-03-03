---
name: code-quality-fix-round2
overview: 按 P0→P1→P2 优先级全量修复检查发现的 27 个代码质量问题，涵盖安全、事务、权限注解、参数校验、数据隔离、性能等方面。
todos:
  - id: p0-cors-swagger
    content: 修复CORS白名单配置、新建application-prod.yml关闭Swagger
    status: completed
  - id: p0-preauthorize
    content: 为ReportController、DataFileController、CompanyController等10个Controller补全@PreAuthorize权限注解
    status: completed
  - id: p0-transaction-delete
    content: ReportService.generateReport加@Transactional、CompanyService.deleteCompany修正删除顺序
    status: completed
  - id: p1-param-valid
    content: 补齐@Valid及实体字段约束：UserController、RoleController、TemplateController、ModuleController、PlaceholderController、ReportController，并修复parseModules和toggleStatus改为强类型DTO
    status: completed
  - id: p1-service-fixes
    content: 修复IP截断、租户保底过滤、N+1查询、realName JWT传递链、@Async代理失效（抽AsyncLogService）
    status: completed
    dependencies:
      - p1-param-valid
  - id: p2-all
    content: 修复P2全部11项：登录限频、密钥校验、事务、tenant过滤、NPE、文件名扩展名、批量INSERT、唯一索引SQL、IGNORE_TABLES移除sys_log
    status: completed
    dependencies:
      - p1-service-fixes
---

## 用户需求

全量修复代码审查发现的 26 个问题（P0×5、P1×10（跳过P1-08）、P2×11），方案选择：

- **Q1 CORS**：从 `application.yml` 读取 `cors.allowed-origins` 白名单（开发默认 `localhost:3000`）
- **Q2 Swagger**：新建 `application-prod.yml`，prod 环境关闭 springdoc 和 knife4j
- **Q3 权限注解**：按 `模块:操作` 格式细粒度 `@PreAuthorize`，与现有 `UserController`/`RoleController` 风格一致
- **Q4 通知发布**：跳过（P1-08 不处理）

## 核心功能

### P0 安全修复

- CORS 配置从通配符改为可配置白名单，消除跨域凭证劫持风险
- Swagger 文档通过 Profile 机制在 prod 环境完全关闭
- 10 个 Controller（ReportController、DataFileController、CompanyController、TemplateController、ModuleController、PlaceholderController、StatsController、LogController、SysConfigController、PermissionController）补全细粒度 `@PreAuthorize` 权限注解
- `ReportService.generateReport()` 加 `@Transactional` 防止部分写入
- `CompanyService.deleteCompany()` 修正为先删子表（contact）再删主表（company）

### P1 参数校验与数据安全

- `X-Forwarded-For` 截取第一个 IP，防止伪造
- `parseModules`、`toggleStatus` 等接口从 raw `Map` 改为强类型 DTO + `@Valid`
- `UserController`、`RoleController`、`TemplateController`、`ModuleController`、`PlaceholderController`、`ReportController` 全面补齐 `@Valid` 及实体字段长度约束
- `PlaceholderService`、`ModuleService`、`ReportService` 的查询方法补充显式 `tenantId` 保底过滤
- `RoleService.listAll()` 消除 N+1 查询（2次查询 + Map 分组）
- `NoticeService` 通过 JWT Claims 携带 `realName` 避免事务内多余查库
- `OperationLogAspect.saveLog()` 抽取独立 `AsyncLogService` Bean 修复 `@Async protected` 代理失效

### P2 稳定性与性能

- 登录接口（用户/超管）增加 Redis 失败次数限频（5次/5分钟）
- `JwtUtil.init()` 校验密钥长度不足 32 字符时启动失败
- `SysConfigService.updateConfig()` 补加 `@Transactional`
- `NoticeService.pageList()` 补显式 `tenantId` 过滤
- `AuthController.getUserInfo()` 加 `principal` 判空保护
- `ReportService.getFileForDownload()` 修复文件名扩展名硬编码 `.docx`
- `ContactMapper.selectByCompanyId()` 指定列名 + `AND deleted=0`
- `CompanyService.saveContacts()` 改为批量 INSERT
- 数据库新增 `report` 表 `(company_id, year, status)` 唯一索引防并发重复生成
- `MybatisPlusConfig.IGNORE_TABLES` 移除 `sys_log`

## 技术栈

Spring Boot 3.2.5 + Java 17 + MyBatis-Plus 3.5.9 + Spring Security + JJWT + Redis（SpringData Redis）+ Apache POI

## 实现方案

### 总体策略

按 P0→P1→P2 分6个批次修改，每批聚焦一类问题，避免跨文件依赖链断裂。关键依赖链：`UserPrincipal.realName` 字段需要 `JwtUtil` / `JwtAuthFilter` 协同变更，须在 P1 同一批次完成。

### P0 安全修复策略

**CORS**：`WebMvcConfig` 注入 `@Value("${cors.allowed-origins:http://localhost:3000}")`，`allowedOriginPatterns` 接受逗号分隔多域名，通过 `String.split(",")` 展开。`application.yml` 新增默认值，`application-prod.yml` 覆盖为生产域名占位符。

**Swagger**：`application-prod.yml` 设置 `springdoc.api-docs.enabled=false`、`knife4j.enable=false`，接口不注册则 Security `permitAll` 条目无副作用，无需改 SecurityConfig。

**@PreAuthorize**：遵循现有 `system:user:list` 格式，按 `模块:操作` 命名，读操作用 `:list`/`:view`，写操作用 `:create`/`:edit`/`:delete`。

### P1 参数校验策略

- raw Map → 内部静态 DTO + `@Valid` + `jakarta.validation` 注解，与 `AuthController.LoginRequest` 风格保持一致
- 实体加 `@NotBlank`/`@Size` 后 `UserController.create/update` 同时补 `@Valid`
- `@Async` 失效：抽 `AsyncLogService` 独立 `@Service`，通过 Spring 容器代理保证异步生效

### P1-10 realName 传递链

1. `JwtUtil.generateUserToken()` 增加 `realName` 参数，写入 JWT claim `"realName"`
2. `AuthService.login()` 传入 `user.getRealName()`
3. `JwtAuthFilter` 解析 token 时取 `claims.get("realName", String.class)`，传入 `UserPrincipal`
4. `UserPrincipal` 增加 `realName` 字段
5. `NoticeService.createNotice()` 改用 `principal.getRealName()`，移除 `userMapper.selectById()`

### P2 登录限频

Redis key：`login:fail:{username}:{tenantId}`（超管：`login:fail:admin:{username}`），5次失败锁定5分钟（300s TTL）。登录成功后 `del` key。`AuthService` 增加私有方法 `checkLoginRateLimit` 和 `clearLoginFailCount`，`AdminAuthService` 同样处理。

### P2-09 批量 INSERT

`ContactMapper` 新增 `batchInsert(@Param("list") List<Contact>)`，使用 MyBatis `<script>+<foreach>` 与 `NoticeUserMapper.batchInsert` 保持相同模式。

### P2-10 数据库唯一索引 + 异常处理

新增 SQL 迁移文件 `db/V2__add_report_unique_index.sql`，`ReportService.generateReport()` catch `DuplicateKeyException` 转为业务异常。

## 性能与可靠性

- N+1 优化后 `RoleService.listAll()` 从 O(N) 次查询降为 2 次
- 批量 INSERT 替代逐条 INSERT，减少数据库往返
- 异步日志真正异步化，不阻塞请求线程

## 目录结构

```
src/
├── main/
│   ├── java/com/fileproc/
│   │   ├── auth/
│   │   │   ├── controller/
│   │   │   │   ├── AuthController.java              # [MODIFY] getUserInfo判空；JWT claim含realName
│   │   │   │   └── AdminAuthController.java         # [MODIFY] 无需改动（已有@Valid）
│   │   │   ├── filter/
│   │   │   │   ├── UserPrincipal.java               # [MODIFY] 增加realName字段
│   │   │   │   └── JwtAuthFilter.java               # [MODIFY] 解析realName claim，传入UserPrincipal
│   │   │   ├── service/
│   │   │   │   ├── AuthService.java                 # [MODIFY] IP截断；登录限频；generateUserToken传realName
│   │   │   │   └── AdminAuthService.java            # [MODIFY] 登录限频
│   │   │   └── util/
│   │   │       └── JwtUtil.java                     # [MODIFY] 密钥长度校验；generateUserToken加realName参数
│   │   ├── common/
│   │   │   ├── WebMvcConfig.java                    # [MODIFY] CORS改为配置文件白名单
│   │   │   ├── MybatisPlusConfig.java               # [MODIFY] IGNORE_TABLES移除sys_log
│   │   │   └── aspect/
│   │   │       ├── OperationLogAspect.java          # [MODIFY] IP截断；调用AsyncLogService
│   │   │       └── AsyncLogService.java             # [NEW] 独立@Service承载@Async saveLog方法
│   │   ├── company/
│   │   │   ├── controller/
│   │   │   │   └── CompanyController.java           # [MODIFY] 补全@PreAuthorize
│   │   │   ├── mapper/
│   │   │   │   └── ContactMapper.java               # [MODIFY] 指定列+deleted过滤；新增batchInsert
│   │   │   └── service/
│   │   │       └── CompanyService.java              # [MODIFY] 删除顺序修正；saveContacts改批量
│   │   ├── datafile/
│   │   │   └── controller/
│   │   │       └── DataFileController.java          # [MODIFY] 补全@PreAuthorize
│   │   ├── notice/
│   │   │   └── service/
│   │   │       └── NoticeService.java               # [MODIFY] pageList加tenantId；realName改用principal
│   │   ├── report/
│   │   │   ├── controller/
│   │   │   │   └── ReportController.java            # [MODIFY] 补全@PreAuthorize；parseModules改DTO+@Valid
│   │   │   └── service/
│   │   │       └── ReportService.java               # [MODIFY] generateReport加@Transactional+DuplicateKey; pageList加tenantId; 文件名扩展名修复
│   │   ├── system/
│   │   │   ├── controller/
│   │   │   │   ├── StatsController.java             # [MODIFY] 补全@PreAuthorize
│   │   │   │   ├── LogController.java               # [MODIFY] 补全@PreAuthorize
│   │   │   │   ├── SysConfigController.java         # [MODIFY] get方法加@PreAuthorize
│   │   │   │   ├── PermissionController.java        # [MODIFY] tree方法加@PreAuthorize
│   │   │   │   ├── UserController.java              # [MODIFY] create/update加@Valid
│   │   │   │   └── RoleController.java              # [MODIFY] create加@Valid
│   │   │   ├── entity/
│   │   │   │   ├── SysUser.java                     # [MODIFY] username/realName加@NotBlank/@Size；password加@JsonProperty(WRITE_ONLY)
│   │   │   │   └── SysRole.java                     # [MODIFY] name加@NotBlank @Size
│   │   │   ├── mapper/
│   │   │   │   └── RolePermissionMapper.java        # [MODIFY] 新增selectPermCodesByRoleIds批量查询
│   │   │   └── service/
│   │   │       ├── RoleService.java                 # [MODIFY] listAll改批量查询消除N+1
│   │   │       └── SysConfigService.java            # [MODIFY] updateConfig加@Transactional
│   │   ├── template/
│   │   │   ├── controller/
│   │   │   │   ├── TemplateController.java          # [MODIFY] 补全@PreAuthorize；create加@Valid
│   │   │   │   ├── ModuleController.java            # [MODIFY] 补全@PreAuthorize；create/update加@Valid
│   │   │   │   └── PlaceholderController.java       # [MODIFY] 补全@PreAuthorize；create/update加@Valid
│   │   │   ├── entity/
│   │   │   │   ├── Template.java                    # [MODIFY] name加@NotBlank @Size
│   │   │   │   ├── ReportModule.java                # [MODIFY] name/code加@NotBlank @Size
│   │   │   │   └── Placeholder.java                 # [MODIFY] name加@NotBlank @Size
│   │   │   └── service/
│   │   │       ├── PlaceholderService.java          # [MODIFY] pageList加tenantId保底
│   │   │       └── ModuleService.java               # [MODIFY] pageList加tenantId保底
│   │   └── tenant/
│   │       └── controller/
│   │           └── AdminTenantController.java       # [MODIFY] toggleStatus改DTO+@Valid+@Pattern
│   └── resources/
│       ├── application.yml                          # [MODIFY] 新增cors.allowed-origins配置
│       ├── application-prod.yml                     # [NEW] prod环境关闭swagger；生产CORS域名
│       └── db/
│           └── V2__add_report_unique_index.sql      # [NEW] report表唯一索引
```