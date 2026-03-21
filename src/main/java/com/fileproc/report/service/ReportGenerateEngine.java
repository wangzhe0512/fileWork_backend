package com.fileproc.report.service;

import com.alibaba.excel.EasyExcel;
import com.fileproc.common.BizException;
import com.fileproc.datafile.entity.DataFile;
import com.fileproc.template.entity.Placeholder;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 报告生成核心引擎
 * <p>
 * 职责：读取 Word 模板文件，结合 Placeholder 定义从 Excel 数据文件中抽取数据，
 * 替换文档中的占位符（文本/表格），输出最终填充后的 Word 文件。
 * </p>
 * 占位符格式约定：{{placeholderName}}
 */
@Slf4j
@Component
public class ReportGenerateEngine {

    /**
     * 执行报告生成
     *
     * @param templateFilePath 模板 Word 文件的绝对/相对路径
     * @param placeholders     该模板关联的占位符定义列表
     * @param dataFiles        关联的数据文件列表（含 filePath，type=list/bvd）
     * @param outputPath       输出 Word 文件的完整路径（含文件名）
     */
    public void generate(String templateFilePath,
                         List<Placeholder> placeholders,
                         List<DataFile> dataFiles,
                         String outputPath) {

        if (templateFilePath == null || !Files.exists(Paths.get(templateFilePath))) {
            throw BizException.of(400, "模板文件不存在：" + templateFilePath);
        }

        // 按 dataFile.type 建立文件路径索引，用于后续按 sourceSheet 读取
        // key: dataFile.type (list/bvd), value: DataFile
        Map<String, DataFile> dataFileByType = new LinkedHashMap<>();
        for (DataFile df : dataFiles) {
            if (df.getFilePath() != null && Files.exists(Paths.get(df.getFilePath()))) {
                dataFileByType.put(df.getType(), df);
            } else {
                log.warn("[ReportEngine] 数据文件不存在，跳过: id={}, path={}", df.getId(), df.getFilePath());
            }
        }

        // 按 (type, sourceSheet) 缓存读取结果，避免重复 IO
        // key: "type:sheetIndex"
        Map<String, List<Map<Integer, Object>>> excelDataCache = new HashMap<>();

        // 构建文本占位符值 Map
        Map<String, String> textValues = new LinkedHashMap<>();
        // 构建表格占位符数据 Map：占位符名 -> 二维列表（含表头行）
        Map<String, List<List<Object>>> tableValues = new LinkedHashMap<>();

        for (Placeholder ph : placeholders) {
            String dataSource = ph.getDataSource(); // list / bvd
            DataFile df = dataFileByType.get(dataSource);
            if (df == null) {
                log.warn("[ReportEngine] 占位符 {} 对应的数据文件未找到，跳过", ph.getName());
                continue;
            }

            // 解析 sourceSheet（可为数字索引或 sheet 名，默认 0）
            String sheetKey = dataSource + ":" + (ph.getSourceSheet() != null ? ph.getSourceSheet() : "0");
            List<Map<Integer, Object>> rows = excelDataCache.computeIfAbsent(
                    sheetKey,
                    k -> readSheet(df.getFilePath(), ph.getSourceSheet())
            );

            if (rows == null || rows.isEmpty()) {
                log.warn("[ReportEngine] 占位符 {} 的 sheet '{}' 数据为空，跳过", ph.getName(), ph.getSourceSheet());
                continue;
            }

            if ("text".equals(ph.getType())) {
                String value = extractTextValue(rows, ph.getSourceSheet(), ph.getSourceField());
                textValues.put(ph.getName(), value != null ? value : "");
            } else if ("table".equals(ph.getType())) {
                List<List<Object>> tableData = extractTableData(rows, ph.getSourceSheet());
                tableValues.put(ph.getName(), tableData);
            } else if ("chart".equals(ph.getType())) {
                // 图表：以文字摘要形式嵌入（完整图表嵌入留作 TODO）
                List<List<Object>> chartData = extractTableData(rows, ph.getSourceSheet());
                String summary = buildChartSummary(ph.getName(), ph.getChartType(), chartData);
                textValues.put(ph.getName(), summary);
            }
        }

        // 创建输出目录
        try {
            Files.createDirectories(Paths.get(outputPath).getParent());
        } catch (IOException e) {
            throw BizException.of("创建输出目录失败：" + e.getMessage());
        }

        // 读取模板并替换占位符，写出结果
        // 部分 docx 内嵌字体压缩比较高，调低安全阈值以避免 Zip bomb 误报
        ZipSecureFile.setMinInflateRatio(0.001);
        try (FileInputStream fis = new FileInputStream(templateFilePath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            mergeAllRunsInDocument(doc);
            replaceParagraphPlaceholders(doc, textValues);
            replaceTablePlaceholders(doc, tableValues);
            // 同时替换表格内文本占位符
            replaceTextInTables(doc, textValues);

            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                doc.write(fos);
            }
            log.info("[ReportEngine] 报告生成成功：{}", outputPath);
        } catch (IOException e) {
            throw BizException.of("报告文件生成失败：" + e.getMessage());
        }
    }

    // ===== Excel 数据读取 =====

    /**
     * 按 sourceSheet 读取 Excel 指定 sheet（EasyExcel，无表头模式）。
     * sourceSheet 可为数字索引字符串（"0","1"...）或 sheet 名称，默认读第 0 个 sheet。
     */
    private List<Map<Integer, Object>> readSheet(String filePath, String sourceSheet) {
        try {
            int sheetIndex = 0;
            String sheetName = null;
            if (sourceSheet != null && !sourceSheet.isBlank()) {
                try {
                    sheetIndex = Integer.parseInt(sourceSheet.trim());
                } catch (NumberFormatException e) {
                    sheetName = sourceSheet.trim();
                }
            }

            List<Map<Integer, Object>> result;
            if (sheetName != null) {
                result = EasyExcel.read(filePath)
                        .sheet(sheetName)
                        .headRowNumber(0)
                        .doReadSync();
            } else {
                result = EasyExcel.read(filePath)
                        .sheet(sheetIndex)
                        .headRowNumber(0)
                        .doReadSync();
            }
            log.debug("[ReportEngine] 读取文件 {} sheet='{}' 共 {} 行", filePath, sourceSheet, result.size());
            return result;
        } catch (Exception e) {
            log.error("[ReportEngine] 读取 Excel 失败: file={}, sheet={}, err={}", filePath, sourceSheet, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从行数据中按 sourceField 提取值，兼容两种 Sheet 布局：
     * <ul>
     *   <li><b>竖向布局</b>（如清单"数据表"）：每行 A列=字段地址（B1/B2...），B列=值。
     *       遍历所有行，找 A列 == sourceField 的那行，返回 B列值。</li>
     *   <li><b>横向布局</b>（传统表头行）：第0行为列名表头，第1行为数据行，
     *       按 sourceField 匹配列标题后取对应列的第1行值。</li>
     * </ul>
     * 优先尝试竖向布局；若未命中再尝试横向布局。
     */
    private String extractTextValue(List<Map<Integer, Object>> rows, String sheetName, String sourceField) {
        if (rows == null || rows.isEmpty() || sourceField == null) return null;

        // 优先：竖向布局 — A列(index=0) 的值与 sourceField 匹配，则取 B列(index=1)
        for (Map<Integer, Object> row : rows) {
            Object aCell = row.get(0);
            if (aCell != null && sourceField.equalsIgnoreCase(aCell.toString().trim())) {
                Object bCell = row.get(1);
                if (bCell != null && !bCell.toString().isBlank()) {
                    return bCell.toString().trim();
                }
                return null; // 找到行但值为空
            }
        }

        // 回退：横向布局 — 第0行为表头，sourceField 匹配列名
        if (rows.size() < 2) return null;
        Map<Integer, Object> headerRow = rows.get(0);
        Integer colIndex = findColumnIndex(headerRow, sourceField);
        if (colIndex == null) {
            log.warn("[ReportEngine] 未找到列 '{}' 的索引（竖向/横向均未匹配）", sourceField);
            return null;
        }
        Map<Integer, Object> dataRow = rows.get(1);
        Object val = dataRow.get(colIndex);
        return val != null ? val.toString() : null;
    }

    /**
     * 从行数据中提取完整表格数据（含表头行）。
     */
    private List<List<Object>> extractTableData(List<Map<Integer, Object>> rows, String sheetName) {
        if (rows.isEmpty()) return Collections.emptyList();

        List<List<Object>> result = new ArrayList<>();
        // 确定最大列数
        int maxCol = rows.stream()
                .mapToInt(r -> r.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1)
                .max().orElse(0);

        for (Map<Integer, Object> row : rows) {
            List<Object> line = new ArrayList<>();
            for (int i = 0; i < maxCol; i++) {
                line.add(row.getOrDefault(i, ""));
            }
            result.add(line);
        }
        return result;
    }

    /**
     * 按列标题名找到列下标
     */
    private Integer findColumnIndex(Map<Integer, Object> headerRow, String fieldName) {
        if (fieldName == null) return null;
        for (Map.Entry<Integer, Object> entry : headerRow.entrySet()) {
            if (fieldName.equalsIgnoreCase(String.valueOf(entry.getValue()))) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 构建图表数据文字摘要（替代实际图表嵌入）
     */
    private String buildChartSummary(String phName, String chartType, List<List<Object>> data) {
        int rows = Math.max(0, data.size() - 1); // 减去表头
        int cols = data.isEmpty() ? 0 : data.get(0).size();
        return String.format("[%s 图表 (%s): %d 行 %d 列数据]", phName,
                chartType != null ? chartType : "bar", rows, cols);
    }

    // ===== Word 文档处理 =====

    /**
     * 合并文档所有段落中被拆分的 Run，确保占位符不会被 POI 切断。
     */
    private void mergeAllRunsInDocument(XWPFDocument doc) {
        for (XWPFParagraph paragraph : doc.getParagraphs()) {
            mergeRunsInParagraph(paragraph);
        }
        // 同时处理表格内段落
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph paragraph : cell.getParagraphs()) {
                        mergeRunsInParagraph(paragraph);
                    }
                }
            }
        }
    }

    /**
     * 合并段落内所有 Run 的文本，避免占位符被拆入多个 Run。
     * 保留第一个 Run 的样式，清空其余 Run。
     */
    private void mergeRunsInParagraph(XWPFParagraph paragraph) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.size() <= 1) return;

        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : runs) {
            sb.append(run.getText(0) != null ? run.getText(0) : "");
        }

        // 将合并后的文本放入第一个 Run
        runs.get(0).setText(sb.toString(), 0);
        // 清空其余 Run
        for (int i = 1; i < runs.size(); i++) {
            runs.get(i).setText("", 0);
        }
    }

    /**
     * 替换文档段落（正文）中的文本占位符 {{name}} -> value
     */
    private void replaceParagraphPlaceholders(XWPFDocument doc, Map<String, String> textValues) {
        for (XWPFParagraph paragraph : doc.getParagraphs()) {
            replacePlaceholdersInParagraph(paragraph, textValues);
        }
    }

    /**
     * 替换表格内单元格中的文本占位符
     */
    private void replaceTextInTables(XWPFDocument doc, Map<String, String> textValues) {
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph paragraph : cell.getParagraphs()) {
                        replacePlaceholdersInParagraph(paragraph, textValues);
                    }
                }
            }
        }
    }

    private void replacePlaceholdersInParagraph(XWPFParagraph paragraph, Map<String, String> textValues) {
        for (XWPFRun run : paragraph.getRuns()) {
            String text = run.getText(0);
            if (text == null) continue;
            for (Map.Entry<String, String> entry : textValues.entrySet()) {
                String placeholder = "{{" + entry.getKey() + "}}";
                if (text.contains(placeholder)) {
                    text = text.replace(placeholder, entry.getValue());
                }
            }
            run.setText(text, 0);
        }
    }

    /**
     * 替换表格占位符：找到含 {{tableName}} 标记的段落，
     * 若该段落位于表格内，则将数据填入该表格；否则在文档中创建新表格。
     */
    private void replaceTablePlaceholders(XWPFDocument doc, Map<String, List<List<Object>>> tableValues) {
        for (Map.Entry<String, List<List<Object>>> entry : tableValues.entrySet()) {
            String phName = entry.getKey();
            List<List<Object>> data = entry.getValue();
            String marker = "{{" + phName + "}}";

            // 在文档表格中查找标记
            boolean found = false;
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String cellText = cell.getText();
                        if (cellText != null && cellText.contains(marker)) {
                            // 清除标记单元格内容
                            clearCellText(cell, marker);
                            // 填充数据到该表格（从第二行开始追加，第一行保留为表头）
                            fillTableWithData(table, data);
                            found = true;
                            break;
                        }
                    }
                    if (found) break;
                }
                if (found) break;
            }

            // 如果在段落中找到标记，替换段落文本为数据摘要
            if (!found) {
                for (XWPFParagraph paragraph : doc.getParagraphs()) {
                    for (XWPFRun run : paragraph.getRuns()) {
                        String text = run.getText(0);
                        if (text != null && text.contains(marker)) {
                            String summary = "[表格数据: " + (data.size() - 1) + " 行]";
                            run.setText(text.replace(marker, summary), 0);
                        }
                    }
                }
            }
        }
    }

    private void clearCellText(XWPFTableCell cell, String marker) {
        for (XWPFParagraph para : cell.getParagraphs()) {
            for (XWPFRun run : para.getRuns()) {
                String text = run.getText(0);
                if (text != null) {
                    run.setText(text.replace(marker, ""), 0);
                }
            }
        }
    }

    /**
     * 将二维数据填充到表格（从第一行之后追加数据行）。
     * data 的第0行视为列头（可与表格已有表头合并），后续行填入数据。
     * P1 修复：截断多余列，不超过表格已有列数，避免列数不匹配异常。
     */
    private void fillTableWithData(XWPFTable table, List<List<Object>> data) {
        if (data.isEmpty()) return;

        // 取表格已有列数作为上限（防止数据列数超出模板表格列数）
        int tableColCount = table.getRows().isEmpty() ? Integer.MAX_VALUE
                : table.getRow(0).getTableCells().size();

        int startDataRow = 1; // 跳过表头
        for (int i = startDataRow; i < data.size(); i++) {
            List<Object> dataRow = data.get(i);
            XWPFTableRow tableRow;
            // 复用已有行或新增行
            if (i < table.getRows().size()) {
                tableRow = table.getRow(i);
            } else {
                tableRow = table.createRow();
            }

            // 截断：最多写入 tableColCount 列
            int colLimit = Math.min(dataRow.size(), tableColCount);
            for (int j = 0; j < colLimit; j++) {
                XWPFTableCell cell;
                if (j < tableRow.getTableCells().size()) {
                    cell = tableRow.getCell(j);
                } else {
                    cell = tableRow.addNewTableCell();
                }
                String cellVal = dataRow.get(j) != null ? dataRow.get(j).toString() : "";
                // 清空并写入
                if (!cell.getParagraphs().isEmpty()) {
                    XWPFParagraph para = cell.getParagraphs().get(0);
                    if (!para.getRuns().isEmpty()) {
                        para.getRuns().get(0).setText(cellVal, 0);
                    } else {
                        para.createRun().setText(cellVal);
                    }
                }
            }
        }
    }
}
