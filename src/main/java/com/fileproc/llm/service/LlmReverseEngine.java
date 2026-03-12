package com.fileproc.llm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileproc.common.BizException;
import com.fileproc.llm.client.OllamaClient;
import com.fileproc.llm.config.OllamaProperties;
import com.fileproc.llm.dto.PlaceholderRule;
import com.fileproc.llm.dto.SegmentAnalysisResult;
import com.fileproc.llm.dto.WordSegment;
import com.fileproc.report.service.ReverseTemplateEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大模型驱动的反向生成引擎（核心）
 * <p>
 * 职责：
 * 1. 使用 WordSegmenter 将 Word 文档分段
 * 2. 使用 ExcelStructureExtractor 提取 Excel 结构摘要
 * 3. 逐段调用 OllamaClient，解析大模型输出的 PlaceholderRule 列表
 * 4. 执行 Word 替换，生成子模板 Word 文件
 * 5. 返回与旧引擎兼容的 ReverseResult 结构
 * <p>
 * 置信度规则：
 * - confidence >= threshold（默认0.8）：status=confirmed，高置信度自动确认
 * - confidence < threshold：status=uncertain，执行替换但前端标记低置信度提示
 * - 大模型未给出 confidence：默认 0.5（低置信度处理）
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmReverseEngine {

    private final OllamaClient ollamaClient;
    private final OllamaProperties properties;
    private final WordSegmenter wordSegmenter;
    private final ExcelStructureExtractor excelExtractor;
    private final ObjectMapper objectMapper;

    /** 从大模型输出中提取 JSON 数组的正则（容错包裹的说明文字） */
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[.*?\\]", Pattern.DOTALL);

    /**
     * 执行大模型驱动的反向生成
     *
     * @param historicalReportPath 历史报告Word绝对路径
     * @param listExcelPath        清单Excel绝对路径
     * @param bvdExcelPath         BVD数据Excel绝对路径
     * @param outputPath           输出子模板Word绝对路径
     * @return 与旧引擎兼容的 ReverseResult
     */
    public ReverseTemplateEngine.ReverseResult reverse(String historicalReportPath,
                                                        String listExcelPath,
                                                        String bvdExcelPath,
                                                        String outputPath) {
        if (!Files.exists(Paths.get(historicalReportPath))) {
            throw BizException.of(400, "历史报告文件不存在：" + historicalReportPath);
        }

        // 1. 提取 Excel 结构摘要（用于拼入 Prompt）
        String listSummary = excelExtractor.extractSummary(listExcelPath, "list");
        String bvdSummary = excelExtractor.extractSummary(bvdExcelPath, "bvd");
        log.info("[LlmReverseEngine] Excel摘要提取完成: listLen={}, bvdLen={}", listSummary.length(), bvdSummary.length());

        // 2. 读取 Word 文档并分段
        List<SegmentAnalysisResult> analysisResults;
        try (FileInputStream fis = new FileInputStream(historicalReportPath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            List<WordSegment> segments = wordSegmenter.segment(doc);
            log.info("[LlmReverseEngine] Word分段完成: 共 {} 个片段", segments.size());

            // 3. 逐段调用大模型分析（串行，避免CPU并发推理OOM）
            analysisResults = analyzeSegments(segments, listSummary, bvdSummary);

            // 4. 汇总所有 PlaceholderRule，执行 Word 替换
            List<PlaceholderRule> allRules = collectAllRules(analysisResults);
            log.info("[LlmReverseEngine] 大模型共识别出 {} 条占位符规则", allRules.size());

            int replacedCount = replaceInDocument(doc, allRules);
            log.info("[LlmReverseEngine] Word替换完成: 替换 {} 处", replacedCount);

            // 5. 写出子模板文件
            try {
                Files.createDirectories(Paths.get(outputPath).getParent());
            } catch (IOException e) {
                throw BizException.of("创建输出目录失败：" + e.getMessage());
            }
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                doc.write(fos);
            }

        } catch (IOException e) {
            throw BizException.of("大模型反向生成失败：" + e.getMessage());
        }

        // 6. 构建兼容旧引擎格式的 ReverseResult
        return buildReverseResult(analysisResults);
    }

    // ========== 分段分析 ==========

    /**
     * 逐段调用大模型分析
     */
    private List<SegmentAnalysisResult> analyzeSegments(List<WordSegment> segments,
                                                          String listSummary,
                                                          String bvdSummary) {
        List<SegmentAnalysisResult> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (WordSegment segment : segments) {
            if (segment.getText().isBlank()) {
                results.add(SegmentAnalysisResult.success(segment.getIndex(), "", Collections.emptyList()));
                continue;
            }

            String prompt = buildPrompt(segment.getText(), listSummary, bvdSummary);
            log.debug("[LlmReverseEngine] 分析片段[{}]: title='{}', textLen={}",
                    segment.getIndex(), segment.getTitle(), segment.getText().length());

            String llmOutput = ollamaClient.generate(prompt);
            SegmentAnalysisResult result = parseOutput(segment.getIndex(), segment.getText(), llmOutput);

            results.add(result);
            if (result.isParseSuccess()) {
                successCount++;
                log.debug("[LlmReverseEngine] 片段[{}]解析成功: {} 条规则", segment.getIndex(), result.getRules().size());
            } else {
                failCount++;
                log.warn("[LlmReverseEngine] 片段[{}]解析失败: {}", segment.getIndex(), result.getParseErrorMsg());
            }
        }

        log.info("[LlmReverseEngine] 分段分析完成: 成功={}, 失败={}", successCount, failCount);
        return results;
    }

    /**
     * 构建 Prompt
     */
    private String buildPrompt(String segmentText, String listSummary, String bvdSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个财税文档分析专家。以下是一份企业关联交易报告的片段，以及对应的两个Excel数据文件结构摘要。\n");
        sb.append("请识别报告片段中哪些文本内容来自Excel数据，以JSON数组格式输出，不要输出其他任何文字。\n\n");

        sb.append("【报告片段】\n").append(segmentText).append("\n\n");

        if (!listSummary.isBlank()) {
            sb.append("【清单Excel结构（list）】\n").append(listSummary).append("\n");
        }
        if (!bvdSummary.isBlank()) {
            sb.append("【BVD Excel结构（bvd）】\n").append(bvdSummary).append("\n");
        }

        sb.append("\n输出格式（仅输出JSON数组，不要有任何其他文字）：\n");
        sb.append("[{\"placeholder\":\"唯一标识如list-基本信息-B3\",");
        sb.append("\"displayName\":\"业务名称如企业名称\",");
        sb.append("\"originalValue\":\"报告中的原文值\",");
        sb.append("\"sheetName\":\"来源Sheet名\",");
        sb.append("\"fieldAddr\":\"单元格地址如B3或空字符串\",");
        sb.append("\"dataSource\":\"list或bvd\",");
        sb.append("\"type\":\"text或table\",");
        sb.append("\"confidence\":0.95}]\n");
        sb.append("如果报告片段中没有来自Excel的数据，请输出空数组：[]\n");

        return sb.toString();
    }

    /**
     * 解析大模型输出（含 JSON 容错处理）
     */
    private SegmentAnalysisResult parseOutput(int segmentIndex, String segmentText, String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return SegmentAnalysisResult.failure(segmentIndex, segmentText, "大模型返回空响应");
        }

        // 提取 JSON 数组（容错包裹的说明文字）
        String jsonStr = extractJsonArray(llmOutput);
        if (jsonStr == null) {
            return SegmentAnalysisResult.failure(segmentIndex, segmentText,
                    "大模型输出中未找到JSON数组: " + llmOutput.substring(0, Math.min(200, llmOutput.length())));
        }

        try {
            List<PlaceholderRule> rules = objectMapper.readValue(jsonStr, new TypeReference<>() {});
            // 过滤掉 originalValue 为空的规则（无法执行替换）
            rules.removeIf(r -> r.getOriginalValue() == null || r.getOriginalValue().isBlank());
            // 确保 confidence 有默认值
            rules.forEach(r -> {
                if (r.getConfidence() <= 0) r.setConfidence(0.5);
            });
            return SegmentAnalysisResult.success(segmentIndex, segmentText, rules);

        } catch (Exception e) {
            return SegmentAnalysisResult.failure(segmentIndex, segmentText,
                    "JSON反序列化失败: " + e.getMessage() + ", json=" + jsonStr.substring(0, Math.min(200, jsonStr.length())));
        }
    }

    /**
     * 从大模型输出中用正则提取 JSON 数组字符串
     */
    private String extractJsonArray(String text) {
        // 先尝试直接 trim 后解析（模型输出规范时）
        String trimmed = text.trim();
        if (trimmed.startsWith("[")) {
            return trimmed;
        }
        // 正则提取第一个 [...] 块
        Matcher matcher = JSON_ARRAY_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    // ========== Word 替换 ==========

    /**
     * 汇总所有片段的规则（去重：同一 originalValue 只保留置信度最高的规则）
     */
    private List<PlaceholderRule> collectAllRules(List<SegmentAnalysisResult> analysisResults) {
        Map<String, PlaceholderRule> valueToRule = new LinkedHashMap<>();

        for (SegmentAnalysisResult result : analysisResults) {
            if (!result.isParseSuccess()) continue;
            for (PlaceholderRule rule : result.getRules()) {
                String key = rule.getOriginalValue();
                PlaceholderRule existing = valueToRule.get(key);
                // 保留置信度更高的规则
                if (existing == null || rule.getConfidence() > existing.getConfidence()) {
                    valueToRule.put(key, rule);
                }
            }
        }

        return new ArrayList<>(valueToRule.values());
    }

    /**
     * 在 Word 文档中执行占位符替换
     *
     * @return 实际替换次数
     */
    private int replaceInDocument(XWPFDocument doc, List<PlaceholderRule> rules) {
        int count = 0;

        for (PlaceholderRule rule : rules) {
            String originalValue = rule.getOriginalValue();
            String placeholderMark = "{{" + rule.getPlaceholder() + "}}";

            // 在段落中替换
            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                for (XWPFRun run : paragraph.getRuns()) {
                    String text = run.getText(0);
                    if (text != null && text.contains(originalValue)) {
                        run.setText(text.replace(originalValue, placeholderMark), 0);
                        count++;
                    }
                }
            }

            // 在表格单元格中替换
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph para : cell.getParagraphs()) {
                            for (XWPFRun run : para.getRuns()) {
                                String text = run.getText(0);
                                if (text != null && text.contains(originalValue)) {
                                    run.setText(text.replace(originalValue, placeholderMark), 0);
                                    count++;
                                }
                            }
                        }
                    }
                }
            }
        }

        return count;
    }

    // ========== 结果转换 ==========

    /**
     * 将大模型分析结果转换为与旧引擎兼容的 ReverseResult 格式
     */
    private ReverseTemplateEngine.ReverseResult buildReverseResult(List<SegmentAnalysisResult> analysisResults) {
        List<ReverseTemplateEngine.MatchedPlaceholder> matchedList = new ArrayList<>();
        List<ReverseTemplateEngine.PendingConfirmItem> pendingList = new ArrayList<>();
        Set<String> processedPlaceholders = new HashSet<>();

        double threshold = properties.getConfidenceThreshold();
        int sort = 0;

        for (SegmentAnalysisResult result : analysisResults) {
            if (!result.isParseSuccess()) continue;

            for (PlaceholderRule rule : result.getRules()) {
                if (processedPlaceholders.contains(rule.getPlaceholder())) continue;
                processedPlaceholders.add(rule.getPlaceholder());

                boolean isHighConfidence = rule.getConfidence() >= threshold;

                // 构建 MatchedPlaceholder（供 initModulesAndPlaceholders 使用）
                ReverseTemplateEngine.MatchedPlaceholder matched = new ReverseTemplateEngine.MatchedPlaceholder();
                matched.setPlaceholderName(rule.getPlaceholder());
                matched.setExpectedValue(rule.getOriginalValue());
                matched.setActualValue(rule.getOriginalValue());
                matched.setLocation("大模型识别");
                matched.setStatus(isHighConfidence ? "confirmed" : "uncertain");
                // 模块信息：从占位符标识中提取（格式：dataSource-sheetName-fieldAddr）
                String[] parts = rule.getPlaceholder().split("-", 3);
                matched.setModuleCode(parts.length >= 2 ? parts[0] + "-" + parts[1] : parts[0]);
                matched.setModuleName(rule.getSheetName() != null ? rule.getSheetName() : "未知模块");
                matched.setPositionJson("{}");

                matchedList.add(matched);

                // 低置信度项加入 pendingList（仅用于前端展示提示，不阻塞流程）
                if (!isHighConfidence) {
                    ReverseTemplateEngine.PendingConfirmItem pending = new ReverseTemplateEngine.PendingConfirmItem();
                    pending.setPlaceholderName(rule.getPlaceholder());
                    pending.setExpectedValue(rule.getOriginalValue());
                    pending.setActualValue(rule.getOriginalValue());
                    pending.setReason(String.format("低置信度(%.2f)，建议复查", rule.getConfidence()));
                    pending.setModuleCode(matched.getModuleCode());
                    pending.setModuleName(matched.getModuleName());
                    pending.setSort(sort);
                    // 低置信度已自动替换，不需要用户再次确认
                    pending.setConfirmed(true);
                    pending.setConfirmedType("text".equals(rule.getType()) ? "text" : "table");
                    pendingList.add(pending);
                }

                sort++;
            }
        }

        ReverseTemplateEngine.ReverseResult reverseResult = new ReverseTemplateEngine.ReverseResult();
        reverseResult.setMatchedCount(matchedList.size());
        reverseResult.setPendingConfirmList(pendingList);
        reverseResult.setAllMatchedPlaceholders(matchedList);
        return reverseResult;
    }
}
