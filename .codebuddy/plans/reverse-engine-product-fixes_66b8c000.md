---
name: reverse-engine-product-fixes
overview: 修复反向引擎产品层面的两个问题：1) 长文本Sheet改为白名单配置模式，避免误判；2) 长文本替换失败时主动通知，在ReverseResult中增加unmatchedEntries列表供前端提示。
todos:
  - id: add-config
    content: 在 application.yml 添加 reverse-engine.long-text-sheets 配置项
    status: completed
  - id: modify-engine
    content: 修改 ReverseTemplateEngine：注入配置、白名单判断、未匹配检测
    status: completed
    dependencies:
      - add-config
  - id: modify-controller
    content: 修改 CompanyTemplateController 返回未匹配列表给前端
    status: completed
    dependencies:
      - modify-engine
---

## 用户指令

用户要求做两点修复：

1. 长文本 Sheet 改为白名单模式
2. 长文本替换失败时主动通知

## 问题背景

### 问题1：长文本Sheet误识别

当前代码用启发式判断：

```java
// B列或A列存在长度 > 10 的值 → 认为是长文本 Sheet
if (bVal != null && bVal.toString().trim().length() > 10) {
    isLongTextSheet = true;
```

实际清单Excel中"表格类Sheet"（如`4 供应商清单`、`5 客户清单`）里，公司名称就超过10字符。这些Sheet会被误识别为长文本Sheet，里面的公司名会以`isLongText=true`方式替换——整段精确匹配，大概率匹配不到，但如果匹配到了会把整个段落替换掉，属于破坏性替换。

### 问题2：长文本替换静默失败无提示

`buildExcelEntries` 里的长文本条目如果在 Word 里一条都没匹配到，没有任何提示，用户不知道哪些内容未能自动匹配。

## 修复目标

1. **长文本Sheet白名单配置**：在 `application.yml` 添加配置项，明确指定哪些 Sheet 是长文本类型，替代不稳定的启发式判断
2. **未匹配长文本通知**：在 `ReverseResult` 中新增未匹配长文本条目列表，让前端可以提示用户哪些长文本内容未能自动匹配

## Tech Stack Selection

- 后端框架：Spring Boot + Java
- 配置管理：Spring `@Value` 注入
- 构建工具：Maven (pom.xml)

## Implementation Approach

### 整体策略

1. **配置驱动**：通过 `application.yml` 配置长文本Sheet白名单，支持灵活调整而不改代码
2. **结果增强**：扩展 `ReverseResult` 数据结构，增加未匹配长文本条目追踪
3. **最小侵入**：保持现有方法签名不变，仅修改内部判断逻辑和结果组装

### 关键设计决策

1. **配置项设计**：使用 `reverse-engine.long-text-sheets` 列表配置，支持多Sheet名称，默认值为空列表
2. **白名单匹配**：使用 `List.contains()` 进行精确匹配，区分大小写
3. **未匹配检测**：在 `reverse()` 方法末尾，对比 `longTextEntries` 和 `matchedList`，找出未匹配条目

### 性能考量

- 配置读取在Bean初始化时完成，无运行时IO开销
- 未匹配检测使用 Stream API，时间复杂度 O(n*m)，n为长文本条目数，m为匹配记录数，通常数量较小（<100），性能可接受

## Implementation Notes

1. **配置注入**：`ReverseTemplateEngine` 已标注 `@Component`，可直接使用 `@Value` 注入配置
2. **保持兼容性**：`ReverseResult` 新增字段不影响现有序列化/反序列化
3. **日志增强**：在未匹配条目较多时输出 warn 日志，便于排查

## Directory Structure

```
project-root/
├── src/main/resources/
│   └── application.yml                    # [MODIFY] 添加 reverse-engine.long-text-sheets 配置
├── src/main/java/com/fileproc/report/service/
│   └── ReverseTemplateEngine.java         # [MODIFY] 
│       # 1. 添加 @Value 注入 longTextSheetNames
│       # 2. 修改 buildExcelEntries() 使用白名单判断
│       # 3. 扩展 ReverseResult 增加 unmatchedLongTextEntries 字段
│       # 4. 修改 reverse() 方法检测未匹配长文本
└── src/main/java/com/fileproc/template/controller/
    └── CompanyTemplateController.java     # [MODIFY]
        # 修改降级分支返回，加入 unmatchedLongTextEntries 到响应Map
```