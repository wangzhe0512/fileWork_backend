---
name: tenant-code-auto-generate
overview: 后端新建租户时自动生成 code（UUID 前8位），前端去掉租户编码输入框，同时保留编辑时可修改 code 的能力。
todos:
  - id: remove-code-validation
    content: 修改 SysTenant.java，去掉 code 字段的 @NotBlank 和 @Pattern 注解
    status: completed
  - id: auto-gen-code
    content: 修改 TenantService.java，createTenant 自动生成唯一编码，updateTenant 补充 code 格式校验
    status: completed
    dependencies:
      - remove-code-validation
---

## 用户需求

新建租户时，**租户编码由后端自动生成**，用户无需手动输入。编辑租户时保留编码可修改的能力（现有逻辑不变）。

## 产品概述

当前新增租户弹窗中"租户编码"为必填项，用户需手动输入符合格式的编码（小写字母+数字），使用门槛高。改造后，新增租户时只需填写租户名称和描述，后端自动生成唯一编码（UUID 前8位小写），对用户完全透明。

## 核心功能

- 后端 `createTenant` 方法：当请求体中 `code` 为空时，自动生成 UUID 前8位作为租户编码，并保证唯一性（冲突时重新生成）
- `SysTenant` 实体：去掉 `code` 字段的 `@NotBlank` 和 `@Pattern` 必填校验，改为可选传入
- 新增租户 API 行为：`code` 字段变为可选，前端可以不传，后端兜底生成

## 技术栈

- 现有项目：Spring Boot 3 + MyBatis-Plus + Jakarta Validation
- 修改范围：纯后端，2个文件

## 实现思路

1. **`SysTenant.java`**：移除 `code` 字段的 `@NotBlank` 和 `@Pattern` 注解，保留 `@Size` 限制（编辑时若传入仍需校验格式，故 `@Pattern` 也需去掉，格式校验下沉到 Service 层）
2. **`TenantService.java`**：在 `createTenant` 中，若 `tenant.getCode()` 为空，自动生成 UUID 前8位（小写）；生成后做唯一性校验，冲突则重试（最多3次），极低概率冲突可忽略

## 实现细节

- UUID 前8位生成：`UUID.randomUUID().toString().replace("-","").substring(0,8)`，结果为8位小写十六进制字符串，满足现有 `^[a-z0-9_-]+ 格式
- `@Pattern` 注解从实体移除后，编辑接口（`updateTenant`）的格式校验改在 Service 层用正则手动校验，防止非法格式写入
- 不修改 Controller，`@Valid` 保留，仅实体注解变更

## 目录结构

```
src/main/java/com/fileproc/tenant/
├── entity/
│   └── SysTenant.java       # [MODIFY] 去掉 code 字段的 @NotBlank 和 @Pattern 注解
└── service/
    └── TenantService.java   # [MODIFY] createTenant 自动生成 code；编辑时补充格式校验
```