---
name: reverse-engine-cross-run-placeholder-fix
overview: 修复 `ReverseTemplateEngine` 中因跨 Run 分割导致的异常占位符问题：`{{清单模板-数据` + `表-B5}}` 被截成两个 Run，无法被单 Run 规范化方法修复。需要在 `normalizeAllPlaceholdersInDocument` 中实现跨 Run 拼接检测与修复逻辑。
todos:
  - id: fix-merge-runs
    content: 改造 mergeRunsInParagraph 方法，改用 CTP.getRList() 在 XML CTRun 层合并所有文本，修复含脚注 Run 段落的跨 Run 占位符碎片问题
    status: completed
---

## 用户需求

子模板（spx_5.docx）中存在 2 处异常占位符，表现为占位符名称内部或开头含多余空格：

- `{{清单模板-数据 表-B5}}`（"数据"与"表"之间有空格，1处）
- `{{ 清单模板-数据表-B2}}`（`{{` 后有空格，1处）

之前已添加的 `normalizePlaceholders` + `normalizeAllPlaceholdersInDocument` 均无法修复，需找到根本原因并彻底解决。

## 问题根因（已确认）

原始 Word XML 中，这两处占位符对应的文本天然跨多个 `<w:r>` Run 节点存储（Word 编辑时脚注引用等特殊节点截断了 Run）。`mergeRunsInParagraph` 使用 POI 高层 API `paragraph.getRuns()`，该方法**只返回标准 Run，跳过含脚注引用（`<w:footnoteReference>`）等特殊子元素的 CTRun**，导致：

1. 含脚注的段落的 Run 合并不完整
2. 占位符文本碎片（`...数据` + `表-B5}}...`）仍分散在前后两个标准 Run 中
3. 后续 `normalizeRunsInParagraph` 也用 `getRuns()`，仍触达不到跨 Run 的碎片

## 修复目标

改造 `mergeRunsInParagraph` 方法，改用 **XML CTRun 层**（`paragraph.getCTP().getRList()`）直接操作，遍历段落所有 CTRun 节点（包括含脚注的），将所有 CTRun 的 `<w:t>` 文本合并到第一个 CTRun，清空其余 CTRun 的 `<w:t>`，从根本上解决跨特殊节点的 Run 合并缺失问题。

## 技术栈

- **项目类型**：Java Spring Boot 后端
- **关键依赖**：Apache POI 5.2.5（`poi-ooxml`），xmlbeans（POI 底层 XML 操作）
- **修改文件**：`src/main/java/com/fileproc/report/service/ReverseTemplateEngine.java`

---

## 实现方案

### 根本原因

POI 高层 API `paragraph.getRuns()` 内部实现会过滤掉含特殊子元素（如 `<w:footnoteReference>`、`<w:bookmarkStart>` 等）的 CTRun，只返回"纯文本 Run"列表。因此含脚注节点的段落 Run 合并时，脚注 CTRun 前后的两个文本 Run 虽都被合并，但它们本应连成一体的文本（跨越脚注 CTRun）却没有被拼接——因为脚注 CTRun 的存在令 POI 认为文本不连续。

### 修复方案：改用 CTRun XML 层操作

**在 `mergeRunsInParagraph` 中，改为直接操作 `paragraph.getCTP().getRList()`**，这是 xmlbeans 对 `<w:p>` 下所有 `<w:r>` 元素的完整列表（不过滤任何特殊子元素）。

具体步骤：

1. 取 `ctP.getRList()` 获取段落所有 CTRun（`List<CTR>`）
2. 拼接所有 CTRun 中 `<w:t>` 的文本到 StringBuilder
3. 将合并后文本写入第一个 CTRun 的 `<w:t>`，并设置 `xml:space="preserve"`
4. 将其余 CTRun 的所有 `<w:t>` 文本清空

此方案绕过 POI 高层 API 的过滤限制，直接在 XML 层面完整合并，保留格式（首个 CTRun 的 `<w:rPr>` 不变），清空后续 CTRun 文本（不删除 CTRun，避免破坏脚注等引用结构）。

### 关键 API（POI 5.2.5 / xmlbeans）

```java
// 取段落的 CTP（对应 <w:p>）
CTP ctP = paragraph.getCTP();

// 获取全部 CTRun（含脚注等特殊节点，不过滤）
List<CTR> ctRuns = ctP.getRList();

// 读取 CTRun 的 w:t 文本列表
for (CTText t : ctRun.getTList()) {
    sb.append(t.getStringValue());
}

// 写入第一个 CTRun 的 w:t
CTText firstT = firstRun.sizeOfTArray() > 0 ? firstRun.getTArray(0) : firstRun.addNewT();
firstT.setStringValue(merged);
firstT.setSpace(SpaceAttribute.Space.PRESERVE);  // xml:space="preserve"

// 清空其余 CTRun 的 w:t
for (CTText t : otherRun.getTList()) {
    t.setStringValue("");
}
```

### 影响范围与安全性

- **只改 `mergeRunsInParagraph`**，调用链不变（`mergeAllRunsInDocument` 仍调用它），不影响替换逻辑、表格处理、页眉页脚等其他流程
- **不删除 CTRun 节点本身**（脚注引用 `<w:footnoteReference>` 等特殊子元素仍保留），只清空 `<w:t>` 文本，保证脚注编号/格式不被破坏
- `normalizeAllPlaceholdersInDocument` 依然保留作为保底的二次清洗，与本次修复互补
- 性能：`getRList()` 仅是 XML 列表遍历，O(n) 复杂度，与原实现相同，无额外开销

---

## 实现注意事项

1. **需 import**：`org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR`、`CTText`、`CTP`、`org.apache.xmlbeans.impl.values.XmlString` → 实际在 POI 5.2.5 中通过 `import org.openxmlformats.schemas.wordprocessingml.x2006.main.*` 统一导入
2. **xml:space 处理**：合并后文本若有前后空格，必须设置 `xml:space="preserve"`，否则 Word 会截断首尾空格
3. **空段落保护**：`ctRuns.isEmpty()` 或只有1个 CTRun 时直接 return，与原逻辑一致
4. **保留原有 `normalizeAllPlaceholdersInDocument` 调用**（第409行），作为双重保障，覆盖其他可能路径的单 Run 内空格问题

---

## 目录结构

```
src/main/java/com/fileproc/report/service/
└── ReverseTemplateEngine.java   # [MODIFY] 仅修改 mergeRunsInParagraph 方法实现
                                 # 从使用 paragraph.getRuns() 改为 paragraph.getCTP().getRList()
                                 # 直接操作 CTRun / CTText XML 层，完整合并含脚注等特殊节点段落的文本
                                 # 其余方法（normalizeAllPlaceholdersInDocument、normalizePlaceholders等）保持不变
```