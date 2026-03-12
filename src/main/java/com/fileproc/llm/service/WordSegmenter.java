package com.fileproc.llm.service;

import com.fileproc.llm.config.OllamaProperties;
import com.fileproc.llm.dto.WordSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Word 文档分段器
 * <p>
 * 将 XWPFDocument 按章节标题切分为若干片段，每个片段封装为 {@link WordSegment}，
 * 包含纯文本内容（用于喂给大模型）和段落/表格索引（用于执行替换）。
 * <p>
 * 分段策略：
 * 1. 检测标题段落（Heading1/Heading2 样式，或字号>=16且加粗）
 * 2. 每次遇到标题，开始新的片段
 * 3. 若某片段超过 maxSegmentChars 字符，则按段落数强制截断
 * 4. 表格跟随其所在位置归入当前片段
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WordSegmenter {

    private final OllamaProperties properties;

    /**
     * 将 Word 文档切分为段落片段
     *
     * @param doc 已加载的 XWPFDocument
     * @return 片段列表
     */
    public List<WordSegment> segment(XWPFDocument doc) {
        // 先合并同一段落内碎片化的 Run，确保文本完整
        mergeRunsInDocument(doc);

        List<WordSegment> segments = new ArrayList<>();
        List<IBodyElement> allElements = doc.getBodyElements();

        WordSegment current = newSegment(0);
        int paraGlobalIdx = 0;
        int tableGlobalIdx = 0;

        for (IBodyElement element : allElements) {
            if (element instanceof XWPFParagraph paragraph) {
                boolean isHeading = isHeadingParagraph(paragraph);
                String paraText = paragraph.getText();

                if (isHeading && !current.getParagraphIndexes().isEmpty()) {
                    // 遇到新标题，且当前片段已有内容，先保存当前片段
                    finalizeAndAdd(segments, current);
                    current = newSegment(segments.size());
                    current.setTitle(paraText);
                }

                current.getParagraphIndexes().add(paraGlobalIdx);
                if (paraText != null && !paraText.isBlank()) {
                    current.setText(current.getText() + paraText + "\n");
                }

                // 判断是否需要强制截断（字符数超限）
                if (current.getText().length() > properties.getMaxSegmentChars()) {
                    finalizeAndAdd(segments, current);
                    current = newSegment(segments.size());
                }

                paraGlobalIdx++;

            } else if (element instanceof XWPFTable table) {
                // 表格：提取文本并归入当前片段
                String tableText = extractTableText(table);
                current.getTableIndexes().add(tableGlobalIdx);
                current.setHasTable(true);
                current.setText(current.getText() + tableText + "\n");

                // 表格可能很大，也需要检查截断
                if (current.getText().length() > properties.getMaxSegmentChars()) {
                    finalizeAndAdd(segments, current);
                    current = newSegment(segments.size());
                }

                tableGlobalIdx++;
            }
        }

        // 保存最后一个片段
        if (!current.getParagraphIndexes().isEmpty() || !current.getTableIndexes().isEmpty()) {
            finalizeAndAdd(segments, current);
        }

        log.info("[WordSegmenter] 文档分段完成: 共 {} 个片段, 段落总数={}, 表格总数={}",
                segments.size(), paraGlobalIdx, tableGlobalIdx);
        return segments;
    }

    /**
     * 判断段落是否为标题段落
     * 判断依据：样式名包含 Heading/标题，或字号>=16且加粗
     */
    private boolean isHeadingParagraph(XWPFParagraph paragraph) {
        String styleName = paragraph.getStyle();
        if (styleName != null) {
            String lower = styleName.toLowerCase();
            if (lower.contains("heading") || lower.contains("标题")) {
                return true;
            }
        }

        // 检查 Run 的字体大小和加粗属性
        List<XWPFRun> runs = paragraph.getRuns();
        if (!runs.isEmpty()) {
            XWPFRun firstRun = runs.get(0);
            // isBold() 返回 true，且字号 >= 14
            if (Boolean.TRUE.equals(firstRun.isBold())) {
                int fontSize = firstRun.getFontSize();
                if (fontSize >= 14) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 提取表格的纯文本（行列格式，便于大模型理解结构）
     */
    private String extractTableText(XWPFTable table) {
        StringBuilder sb = new StringBuilder("[表格]\n");
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText().trim());
            }
            sb.append(String.join(" | ", cells)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 合并段落内碎片化 Run（保证文本完整性）
     * 借鉴自旧引擎 ReverseTemplateEngine.mergeAllRunsInDocument()
     */
    private void mergeRunsInDocument(XWPFDocument doc) {
        // 合并正文段落
        for (XWPFParagraph para : doc.getParagraphs()) {
            mergeRunsInParagraph(para);
        }
        // 合并表格单元格内段落
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph para : cell.getParagraphs()) {
                        mergeRunsInParagraph(para);
                    }
                }
            }
        }
    }

    /**
     * 合并单个段落内的所有 Run 为一个 Run（保留第一个 Run 的格式）
     */
    private void mergeRunsInParagraph(XWPFParagraph para) {
        List<XWPFRun> runs = para.getRuns();
        if (runs == null || runs.size() <= 1) return;

        StringBuilder fullText = new StringBuilder();
        for (XWPFRun run : runs) {
            String t = run.getText(0);
            if (t != null) fullText.append(t);
        }

        // 把合并后的文本写入第一个 Run
        runs.get(0).setText(fullText.toString(), 0);

        // 清空后续 Run 的文本
        for (int i = 1; i < runs.size(); i++) {
            runs.get(i).setText("", 0);
        }
    }

    private WordSegment newSegment(int index) {
        WordSegment seg = new WordSegment();
        seg.setIndex(index);
        seg.setText("");
        seg.setTitle("");
        seg.setParagraphIndexes(new ArrayList<>());
        seg.setTableIndexes(new ArrayList<>());
        return seg;
    }

    private void finalizeAndAdd(List<WordSegment> segments, WordSegment segment) {
        if (segment.getText().isBlank() && segment.getTableIndexes().isEmpty()) {
            return; // 空片段跳过
        }
        segments.add(segment);
        log.debug("[WordSegmenter] 片段[{}]: title='{}', chars={}, paragraphs={}, tables={}",
                segment.getIndex(), segment.getTitle(),
                segment.getText().length(),
                segment.getParagraphIndexes().size(),
                segment.getTableIndexes().size());
    }
}
