---
name: backend-api-adjustments-for-frontend-v2
overview: 根据前端V2反馈文档(backend-api-changes.md)调整后端接口：1)反向生成接口改为只上传Word，自动关联年度数据；2)确认content-url接口；3)confirm-placeholders新增confirmedType字段；4)确认active接口
todos:
  - id: modify-reverse-generate
    content: 改造反向生成接口：只上传Word文件，自动查询年度数据
    status: completed
  - id: modify-confirmed-type
    content: 调整占位符确认接口：支持confirmedType字段
    status: completed
    dependencies:
      - modify-reverse-generate
  - id: verify-interfaces
    content: 验证content-url和active接口实现
    status: completed
    dependencies:
      - modify-confirmed-type
---

## 产品概述

根据前端反馈文档 `backend-api-changes.md`，后端需要调整以下接口以支持前端V2版本：

## 核心功能需求

### 1. 反向生成接口改造（高优先级）

- **接口**: `POST /api/company-template/reverse-generate`
- **调整内容**: 从上传3个文件改为只上传1个Word文件，清单和BVD数据根据year参数自动从数据管理模块查询
- **错误处理**: 如果年度数据不存在，返回400错误提示用户先到数据管理上传

### 2. content-url接口确认（高优先级）

- **接口**: `GET /api/company-template/{id}/content-url`
- **调整内容**: 验证现有实现是否符合前端要求（返回可直接访问的URL供OnlyOffice使用）

### 3. 占位符确认接口调整（中优先级）

- **接口**: `POST /api/company-template/{id}/confirm-placeholders`
- **调整内容**: 新增 `confirmedType` 字段，保留 `confirmed` 布尔字段
- **confirmedType可选值**: text/table/chart/image/ignore

### 4. active接口确认（中优先级）

- **接口**: `GET /api/company-template/active`
- **调整内容**: 验证现有实现是否符合要求

## Tech Stack Selection

- **Framework**: Spring Boot 3.2.5 + Java 17
- **Database**: MySQL + MyBatis-Plus
- **Existing Modules**: datafile模块（DataFileService/DataFileMapper）已支持按年度查询清单和BVD数据

## Implementation Approach

### 1. 反向生成接口改造

**策略**: 修改 `CompanyTemplateController.reverseGenerate()` 方法签名，移除 `listFile` 和 `bvdFile` 参数，改为通过 `DataFileService` 根据 `companyId` 和 `year` 查询对应的数据文件。

**关键逻辑**:

- 查询 `type='list'` 和 `type='bvd'` 的数据文件
- 如果任一文件不存在，抛出 `BizException(400, "该年度清单模板或BVD数据缺失...")`
- 使用 `DataFileMapper.selectWithFilePathByCompanyAndYear()` 获取文件路径

### 2. confirm-placeholders接口调整

**策略**: 扩展 `PendingConfirmItem` 内部类和 `confirmPlaceholders` 方法，支持 `confirmedType` 字段。

**关键逻辑**:

- `confirmed=true` + `confirmedType='ignore'` → 状态设为 `ignored`
- `confirmed=true` + `confirmedType` 为其他值 → 状态设为 `confirmed`，并记录类型
- `confirmed=false` → 保持 `uncertain` 状态

### 3. 接口确认

- `content-url` 和 `active` 接口已存在，需要验证响应格式是否符合前端要求

## Implementation Notes

- **数据管理模块**: 复用现有的 `datafile` 模块，无需新建表
- **错误提示**: 严格使用前端文档指定的错误消息格式
- **兼容性**: `confirmedType` 为新增字段，不影响现有逻辑

## Directory Structure

```
src/main/java/com/fileproc/
├── template/
│   ├── controller/
│   │   └── CompanyTemplateController.java      # [MODIFY] 改造reverse-generate接口
│   └── service/
│       └── CompanyTemplatePlaceholderService.java # [MODIFY] 支持confirmedType
├── report/
│   └── service/
│       └── ReverseTemplateEngine.java          # [MODIFY] PendingConfirmItem添加confirmedType字段
└── datafile/
    ├── service/
    │   └── DataFileService.java                # [EXISTING] 复用查询方法
    └── mapper/
        └── DataFileMapper.java                 # [EXISTING] 复用selectWithFilePathByCompanyAndYear
```