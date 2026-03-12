package com.fileproc.llm.dto;

import lombok.Data;

/**
 * 大模型输出的单条占位符规则
 * <p>
 * 大模型每次分析一个 Word 片段后，返回 JSON 数组，每个元素反序列化为此类。
 * 示例 JSON：
 * {"placeholder":"list-基本信息-B3","displayName":"企业名称","originalValue":"华为技术有限公司",
 *  "sheetName":"基本信息","fieldAddr":"B3","dataSource":"list","type":"text","confidence":0.95}
 * </p>
 */
@Data
public class PlaceholderRule {

    /**
     * 占位符唯一标识，如 "list-基本信息-B3"
     * 生成规则：{dataSource}-{sheetName}-{fieldAddr}
     */
    private String placeholder;

    /**
     * 业务语义名称，如 "企业名称"、"注册资本"
     */
    private String displayName;

    /**
     * 该占位符在 Word 报告片段中的原始文本值，如 "华为技术有限公司"
     * Java 将根据此值在 Word 中执行精确替换
     */
    private String originalValue;

    /**
     * 来源 Excel Sheet 名称，如 "基本信息"
     */
    private String sheetName;

    /**
     * 来源单元格地址，如 "B3"（大模型推断不出时可为空）
     */
    private String fieldAddr;

    /**
     * 数据来源：list（清单Excel）或 bvd（BVD数据Excel）
     */
    private String dataSource;

    /**
     * 占位符类型：text（段落文本）或 table（表格单元格）
     */
    private String type;

    /**
     * 大模型置信度，范围 0.0~1.0
     * >= 阈值（默认0.8）：高置信度，status=confirmed
     * <  阈值：低置信度，status=uncertain，前端标记提示
     * 大模型未给出时默认 0.5
     */
    private double confidence = 0.5;
}
