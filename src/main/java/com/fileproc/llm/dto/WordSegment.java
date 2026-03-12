package com.fileproc.llm.dto;

import lombok.Data;

import java.util.List;

/**
 * Word 文档片段（分段结果）
 * <p>
 * WordSegmenter 将 Word 文档按标题切分后，每段封装为此对象。
 * 包含该段的纯文本（用于喂给大模型）和原始 POI 元素索引（用于执行替换）。
 * </p>
 */
@Data
public class WordSegment {

    /** 片段序号（从0开始） */
    private int index;

    /** 片段标题（如果该段以标题段落开始） */
    private String title;

    /** 片段纯文本内容（去除格式，用于拼入 Prompt） */
    private String text;

    /**
     * 片段内的段落索引列表（对应 doc.getParagraphs() 的索引）
     * 用于替换时精确定位
     */
    private List<Integer> paragraphIndexes;

    /**
     * 片段内的表格索引列表（对应 doc.getTables() 的索引）
     * 用于替换时精确定位
     */
    private List<Integer> tableIndexes;

    /** 是否包含表格 */
    private boolean hasTable;
}
