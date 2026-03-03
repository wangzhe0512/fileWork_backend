---
name: fix-all-quality-issues
overview: 全量修复代码检查发现的 P0/P1/P2 共 67 个问题，涵盖文件安全、越权访问、运行时崩溃、参数校验、权限注解、业务逻辑缺陷、性能优化等各维度。
todos:
  - id: fix-p0-file-exposure
    content: 修复P0文件暴露：WebMvcConfig删除静态映射；SecurityConfig删除permitAll；DataFileController/ReportController各新增受鉴权/download/{id}下载接口
    status: completed
  - id: fix-p0-upload-heading
    content: 修复P0文件类型白名单+路径穿越+ReportService extractWordHeadings style.contains("1")逻辑错误
    status: completed
  - id: fix-p1-jwt-auth
    content: 修复JwtAuthFilter权限列表崩溃；AuthService登录顺序+lastLoginAt；AdminAuthService超管状态校验；UserMapper新增updateLastLogin
    status: completed
  - id: fix-p1-user
    content: 修复UserService租户归属+空值校验+password清除；UserController所有接口加细粒度@PreAuthorize和@Valid
    status: completed
    dependencies:
      - fix-p1-jwt-auth
  - id: fix-p1-role-config-notice-ctrl
    content: 修复RoleController加@PreAuthorize；SysConfigService加@Transactional；SysConfigController加@PreAuthorize；AdminNoticeController加@PreAuthorize和@Valid
    status: completed
  - id: fix-p1-datafile-report
    content: 修复DataFileService.delete()租户归属+warn日志+注入SysConfig大小校验；ReportService deleteReport物理文件删除+@Transactional；updateReport取report自身companyId/year
    status: completed
    dependencies:
      - fix-p0-upload-heading
  - id: fix-p1-engine-template-placeholder
    content: 修复ReportGenerateEngine按sourceSheet读取+fillTableWithData截断列；TemplateService deleteTemplate关联检查；PlaceholderService create唯一性+update幂等性；ReportMapper新增countByTemplateId
    status: completed
  - id: fix-p1-notice-service
    content: 修复NoticeService deleteNotice级联+updateNotice字段合并+markRead原子更新+createNotice取realName；NoticeUserMapper新增deleteByNoticeId；NoticeMapper新增incrementReadCount
    status: completed
  - id: fix-p1-tenant-company
    content: 修复TenantService deleteTenant软删除+updateTenant code唯一性；AdminTenantController加@Valid+toggleStatus空值校验；CompanyController加@Valid+search长度限制
    status: completed
  - id: fix-p2-all
    content: P2全部：JwtUtil缓存SecretKey+区分过期；新建TokenConstants.java；AuthService buildUserInfo改HashMap；PermissionService buildTree改O(n)；CompanyService/TemplateService加租户过滤；ModuleService唯一性；WebMvcConfig CORS补TODO注释
    status: completed
    dependencies:
      - fix-p1-jwt-auth
---

## 用户需求

对后端代码按 P0→P1→P2 优先级全量修复所有检查发现的质量问题。

- **Q1 方案一**：完整修复静态文件暴露（删除静态映射 + 新增 `/download/{id}` 受鉴权接口，前端需改下载方式）
- **Q2 方案一**：细粒度 `@PreAuthorize` 权限注解（如 `hasAuthority('system:user:create')`），使已有权限体系真正生效
- **Q3 全部修复**：P0 + P1 + P2 一次性完成

## 核心修复内容

- **P0（4个）**：文件访问匿名暴露、文件类型无白名单、标题样式逻辑错误
- **P1（46个）**：JwtFilter 运行时崩溃、登录顺序安全、越权操作、缺失 @Transactional、通知/租户/报告业务逻辑缺陷、权限注解系统性缺失
- **P2（17个）**：SecretKey 缓存优化、公共常量提取、O(n²) 改 O(n)、租户过滤保底、CORS 注释等

## 技术栈

Spring Boot 3.2.5 + Java 17 + MyBatis-Plus 3.5.9 + Spring Security，沿用现有代码规范。

## 实现方案

按问题优先级分批修改，每批关联文件集中处理，避免遗漏。新增接口、Mapper 方法、工具类均遵循现有代码风格。

## 目录结构

```
src/main/java/com/fileproc/
├── auth/
│   ├── config/SecurityConfig.java          # [MODIFY] 删除 /files/** permitAll
│   ├── filter/JwtAuthFilter.java           # [MODIFY] 修复权限列表 UnsupportedOperationException
│   ├── service/AuthService.java            # [MODIFY] 登录顺序+lastLoginAt+HashMap防null
│   ├── service/AdminAuthService.java       # [MODIFY] 超管状态校验+引用TokenConstants
│   └── util/
│       ├── JwtUtil.java                    # [MODIFY] @PostConstruct缓存SecretKey+区分过期
│       └── TokenConstants.java             # [NEW] 公共黑名单前缀常量
├── common/
│   └── WebMvcConfig.java                   # [MODIFY] 删除addResourceHandlers静态映射+CORS注释
├── system/
│   ├── mapper/UserMapper.java              # [MODIFY] 新增updateLastLogin方法
│   ├── service/UserService.java            # [MODIFY] 租户归属+空值校验+password清除
│   ├── service/SysConfigService.java       # [MODIFY] getConfig加@Transactional
│   ├── service/PermissionService.java      # [MODIFY] buildTree改O(n) Map分组
│   ├── controller/UserController.java      # [MODIFY] 所有接口加@PreAuthorize+@Valid
│   ├── controller/RoleController.java      # [MODIFY] 所有接口加@PreAuthorize+@Valid
│   └── controller/SysConfigController.java # [MODIFY] update加@PreAuthorize
├── company/
│   ├── service/CompanyService.java         # [MODIFY] pageList/search/getById加租户过滤
│   └── controller/CompanyController.java   # [MODIFY] create/update加@Valid+search限长
├── datafile/
│   ├── service/DataFileService.java        # [MODIFY] 文件类型白名单+路径穿越+大小校验+delete租户归属+warn日志
│   └── controller/DataFileController.java  # [MODIFY] 新增/download/{id}受鉴权接口
├── template/
│   ├── service/TemplateService.java        # [MODIFY] deleteTemplate关联检查+pageList租户过滤
│   ├── service/PlaceholderService.java     # [MODIFY] create唯一性+update影响行数幂等
│   └── service/ModuleService.java          # [MODIFY] create唯一性校验
├── report/
│   ├── service/ReportService.java          # [MODIFY] @Transactional+deleteReport物理文件+uploadReport白名单+extractWordHeadings精确匹配+updateReport取report字段
│   ├── service/ReportGenerateEngine.java   # [MODIFY] readAllSheets按sourceSheet读取+fillTableWithData截断列
│   ├── mapper/ReportMapper.java            # [MODIFY] 新增countByTemplateId方法
│   └── controller/ReportController.java    # [MODIFY] 新增/download/{id}受鉴权接口
├── notice/
│   ├── service/NoticeService.java          # [MODIFY] deleteNotice级联+updateNotice字段合并+markRead原子+createNotice realName
│   ├── mapper/NoticeUserMapper.java        # [MODIFY] 新增deleteByNoticeId方法
│   └── mapper/NoticeMapper.java            # [MODIFY] 新增incrementReadCount原子更新方法
├── notice/
│   └── controller/AdminNoticeController.java  # [MODIFY] 所有接口加@PreAuthorize+@Valid
└── tenant/
    ├── service/TenantService.java          # [MODIFY] deleteTenant软删除+updateTenant code唯一性
    └── controller/AdminTenantController.java # [MODIFY] create/update加@Valid+toggleStatus空值校验
```