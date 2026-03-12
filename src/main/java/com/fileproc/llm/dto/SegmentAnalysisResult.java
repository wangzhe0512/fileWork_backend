package com.fileproc.llm.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 Word 片段的大模型分析结果
 * <p>
 * LlmReverseEngine 逐段调用大模型后，每段返回一个此对象。
 * 汇总所有段的结果后，执行 Word 替换。
 * </p>
 */
@Data
public class SegmentAnalysisResult {

    /** 片段序号（从0开始） */
    private int segmentIndex;

    /** 片段的文本内容（用于日志/调试） */
    private String segmentText;

    /** 大模型解析出的占位符规则列表 */
    private List<PlaceholderRule> rules = new ArrayList<>();

    /** 大模型 JSON 解析是否成功；false 时 rules 为空，记录 warn 日志跳过 */
    private boolean parseSuccess;

    /** 解析失败时的原因描述 */
    private String parseErrorMsg;

    public static SegmentAnalysisResult success(int index, String text, List<PlaceholderRule> rules) {
        SegmentAnalysisResult result = new SegmentAnalysisResult();
        result.segmentIndex = index;
        result.segmentText = text;
        result.rules = rules != null ? rules : new ArrayList<>();
        result.parseSuccess = true;
        return result;
    }

    public static SegmentAnalysisResult failure(int index, String text, String errorMsg) {
        SegmentAnalysisResult result = new SegmentAnalysisResult();
        result.segmentIndex = index;
        result.segmentText = text;
        result.parseSuccess = false;
        result.parseErrorMsg = errorMsg;
        return result;
    }
}
