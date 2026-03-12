package com.fileproc.llm.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.read.metadata.ReadSheet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Excel 结构提取器
 * <p>
 * 读取 Excel 文件的所有 Sheet，提取 Sheet 名称 + 列名行 + 前5行数据，
 * 生成精简的结构摘要字符串，用于拼入大模型的 Prompt。
 * <p>
 * 输出示例：
 * <pre>
 * Sheet: 基本信息
 * 列名: 企业名称 | 统一社会信用代码 | 注册资本
 * 数据行1: 华为技术有限公司 | 91440300MA5FMXXX | 3,000,000万元
 * ...
 * Sheet: 关联方信息
 * ...
 * </pre>
 * </p>
 */
@Slf4j
@Component
public class ExcelStructureExtractor {

    /** 每个 Sheet 最多提取的数据行数（不含表头行） */
    private static final int MAX_DATA_ROWS = 5;

    /** 每行最多提取的列数（防止宽表占用太多 token） */
    private static final int MAX_COLS = 15;

    /** 每个单元格值最大字符数 */
    private static final int MAX_CELL_CHARS = 50;

    /**
     * 提取 Excel 文件的结构摘要
     *
     * @param excelFilePath Excel 文件绝对路径
     * @param dataSourceTag 数据源标识（"list" 或 "bvd"），用于日志
     * @return 结构摘要字符串（用于 Prompt 拼接），文件不存在时返回空字符串
     */
    public String extractSummary(String excelFilePath, String dataSourceTag) {
        if (excelFilePath == null || excelFilePath.isBlank()) {
            return "";
        }

        java.io.File file = new java.io.File(excelFilePath);
        if (!file.exists()) {
            log.warn("[ExcelStructureExtractor] 文件不存在: {}", excelFilePath);
            return "";
        }

        try {
            // 获取所有 Sheet 列表
            List<ReadSheet> sheets = EasyExcel.read(excelFilePath).build()
                    .excelExecutor().sheetList();

            StringBuilder summary = new StringBuilder();

            for (ReadSheet sheet : sheets) {
                String sheetName = sheet.getSheetName();
                summary.append("Sheet: ").append(sheetName).append("\n");

                // 读取该 Sheet 的所有行（无表头模式，index 0 = 第一行）
                List<Map<Integer, Object>> rows = EasyExcel.read(excelFilePath)
                        .sheet(sheetName)
                        .headRowNumber(0)
                        .doReadSync();

                if (rows == null || rows.isEmpty()) {
                    summary.append("（空Sheet）\n");
                    continue;
                }

                // 第一行作为列名行
                Map<Integer, Object> headerRow = rows.get(0);
                List<String> headers = extractRowValues(headerRow);
                summary.append("列名: ").append(String.join(" | ", headers)).append("\n");

                // 提取后续数据行（最多 MAX_DATA_ROWS 行）
                int dataRowCount = Math.min(rows.size() - 1, MAX_DATA_ROWS);
                for (int i = 1; i <= dataRowCount; i++) {
                    Map<Integer, Object> dataRow = rows.get(i);
                    List<String> values = extractRowValues(dataRow);
                    summary.append("数据行").append(i).append(": ")
                            .append(String.join(" | ", values)).append("\n");
                }

                summary.append("\n");
            }

            String result = summary.toString();
            log.debug("[ExcelStructureExtractor] 提取完成: source={}, sheets={}, summaryLen={}",
                    dataSourceTag, sheets.size(), result.length());
            return result;

        } catch (Exception e) {
            log.error("[ExcelStructureExtractor] 提取Excel结构失败: source={}, file={}, error={}",
                    dataSourceTag, excelFilePath, e.getMessage(), e);
            return "";
        }
    }

    /**
     * 从行数据 Map 中提取字符串值列表（最多 MAX_COLS 列）
     */
    private List<String> extractRowValues(Map<Integer, Object> row) {
        if (row == null) return Collections.emptyList();

        List<String> values = new ArrayList<>();
        int maxCol = Math.min(row.size(), MAX_COLS);

        for (int col = 0; col < maxCol; col++) {
            Object cellVal = row.get(col);
            if (cellVal == null) {
                values.add("");
            } else {
                String strVal = cellVal.toString().trim();
                // 截断过长的值
                if (strVal.length() > MAX_CELL_CHARS) {
                    strVal = strVal.substring(0, MAX_CELL_CHARS) + "...";
                }
                values.add(strVal);
            }
        }

        return values;
    }
}
