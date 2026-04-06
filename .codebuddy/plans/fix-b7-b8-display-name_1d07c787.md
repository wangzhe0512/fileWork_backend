---
name: fix-b7-b8-display-name
overview: 将注册表中 B7、B8 两条占位符的 display_name 与清单 Excel 文件中 A 列标签对齐：B7 从"集团简介"改为"集团情况描述"，B8 从"公司概况"改为"公司经营背景资料"。
todos:
  - id: fix-v9-and-create-v15
    content: 修改 V9 SQL 第62、63行 display_name，并新建 V15 迁移脚本
    status: completed
---

## 用户需求

将后端占位符注册表中两条记录的展示名称与清单模板 Excel 文件数据表 Sheet A 列标签保持一致：

- `清单模板-数据表-B7`：display_name 从 `集团简介` 改为 `集团情况描述`
- `清单模板-数据表-B8`：display_name 从 `公司概况` 改为 `公司经营背景资料`

已通过三份清单 Excel 文件（spxv2、松莉2023、远化23）核实，A7 均为"集团情况描述"，A8 均为"公司经营背景资料"。

## 变更范围

- `V9__placeholder_registry_and_schema.sql` 第62、63行：修改对应 display_name
- 新建 `V15__fix_b7_b8_display_name.sql`：UPDATE 已部署数据库中两条记录，与 V14 脚本风格保持一致

## 核心特征

- 仅涉及展示用字段 `display_name`，不影响取值逻辑、占位符名称、单元格地址等任何功能字段
- Java 代码无需修改

## 技术方案

### V9 SQL 修改

- 第62行：`'集团简介'` → `'集团情况描述'`
- 第63行：`'公司概况'` → `'公司经营背景资料'`

### V15 迁移脚本（新建）

参照 V14 脚本风格，两条 UPDATE 语句分别修正已部署数据库中 B7、B8 的 display_name。

## 目录结构

```
src/main/resources/db/
├── V9__placeholder_registry_and_schema.sql   [MODIFY] 第62行改为'集团情况描述'，第63行改为'公司经营背景资料'
└── V15__fix_b7_b8_display_name.sql           [NEW]    UPDATE B7、B8 两条记录的 display_name
```