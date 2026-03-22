package com.fileproc.report.service;

import com.alibaba.excel.EasyExcel;
import com.fileproc.common.BizException;
import com.fileproc.datafile.entity.DataFile;
import com.fileproc.template.entity.Placeholder;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
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
                value = expandYearValue(ph.getName(), value);
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
            // 替换页眉/页脚中的文本占位符
            replaceHeaderFooterPlaceholders(doc, textValues);

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
     * 从行数据中按 sourceField 提取值，兼容三种 Sheet 布局：
     * <ul>
     *   <li><b>Excel 单元格地址</b>（如清单"数据表"）：sourceField 形如 "B1"、"B3"，
     *       列字母对应 Excel 列（A=0,B=1...），行号为 1-based。
     *       直接按行号和列索引取值。</li>
     *   <li><b>竖向布局</b>：A列=字段名，B列=值，按 sourceField 匹配 A列文字。</li>
     *   <li><b>横向布局</b>（传统表头行）：第0行为列名表头，第1行为数据行，
     *       按 sourceField 匹配列标题后取对应列的第1行值。</li>
     * </ul>
     */
    private String extractTextValue(List<Map<Integer, Object>> rows, String sheetName, String sourceField) {
        if (rows == null || rows.isEmpty() || sourceField == null) return null;

        String field = sourceField.trim();

        // 优先：Excel 单元格地址格式，如 "B1"、"C3"（字母+数字）
        if (field.matches("[A-Za-z]+\\d+")) {
            // 解析列字母 -> 列索引（A=0, B=1, C=2...）
            String colLetters = field.replaceAll("\\d", "").toUpperCase();
            int colIndex = 0;
            for (char c : colLetters.toCharArray()) {
                colIndex = colIndex * 26 + (c - 'A' + 1);
            }
            colIndex -= 1; // 转为 0-based

            // 解析行号（1-based -> 0-based）
            int rowIndex = Integer.parseInt(field.replaceAll("[A-Za-z]", "")) - 1;

            if (rowIndex >= 0 && rowIndex < rows.size()) {
                Object val = rows.get(rowIndex).get(colIndex);
                if (val != null && !val.toString().isBlank()) {
                    return val.toString().trim();
                }
            }
            log.warn("[ReportEngine] 单元格地址 '{}' 无数据（行={}, 列={}）", field, rowIndex, colIndex);
            return null;
        }

        // 次优：竖向布局 — A列(index=0) 的值与 sourceField 匹配，则取 B列(index=1)
        for (Map<Integer, Object> row : rows) {
            Object aCell = row.get(0);
            if (aCell != null && field.equalsIgnoreCase(aCell.toString().trim())) {
                Object bCell = row.get(1);
                if (bCell != null && !bCell.toString().isBlank()) {
                    return bCell.toString().trim();
                }
                return null;
            }
        }

        // 回退：横向布局 — 第0行为表头，sourceField 匹配列名
        if (rows.size() < 2) return null;
        Map<Integer, Object> headerRow = rows.get(0);
        Integer colIndex = findColumnIndex(headerRow, field);
        if (colIndex == null) {
            log.warn("[ReportEngine] 未找到列 '{}' 的索引（三种布局均未匹配）", field);
            return null;
        }
        Map<Integer, Object> dataRow = rows.get(1);
        Object val = dataRow.get(colIndex);
        return val != null ? val.toString() : null;
    }

    /**
     * 从行数据中提取完整表格数据（含表头行）。
     *
     * <p>PL Sheet 专用截取：当 sheetName 为 "PL" 或 "PL含特殊因素调整" 时，
     * 只取 rows[3..11]（跳过行0空行、行1表头、行2空行，以及行12以后的关联销售明细），
     * 前置一个空虚拟表头行（index=0），使 fillTableWithData 的 startDataRow=1 跳过它，
     * 从而保留 Word 表格原有表头（"金额（人民币元）" 等），只用 PL 数据填充数据行。
     * </p>
     */
    private List<List<Object>> extractTableData(List<Map<Integer, Object>> rows, String sheetName) {
        if (rows.isEmpty()) return Collections.emptyList();

        // PL Sheet 专用截取逻辑：只取行3~11（共9行数据）
        if ("PL".equals(sheetName) || "PL含特殊因素调整".equals(sheetName)) {
            List<List<Object>> result = new ArrayList<>();
            // 虚拟表头行（空列表），供 fillTableWithData 的 startDataRow=1 跳过，保留 Word 原有表头
            result.add(Collections.emptyList());
            // 截取数据行：rows[3..11]，共9行（跳过空行、表头行、空行以及下方的关联销售明细）
            int dataStart = 3;
            int dataEnd = Math.min(12, rows.size()); // 行12以后是关联销售明细，不包含
            for (int i = dataStart; i < dataEnd; i++) {
                Map<Integer, Object> row = rows.get(i);
                // 只保留前3列（项目/公式/金额），忽略多余列
                List<Object> line = new ArrayList<>();
                for (int col = 0; col < 3; col++) {
                    line.add(row.getOrDefault(col, ""));
                }
                result.add(line);
            }
            log.debug("[ReportEngine] PL Sheet '{}' 截取数据行 {}~{}，共 {} 行", sheetName, dataStart, dataEnd - 1, result.size() - 1);
            return result;
        }

        // 供应商清单 / 客户清单 Sheet 专用提取逻辑（行模板克隆方案数据源）
        // 结构：行0=公司名, 行1=空, 行2=附件标题, 行3=说明, 行4=大表头, 行5=子表头, 行6=分组标题, 行7起=数据
        // 输出：虚拟空表头行 + [col0=分组/名称/文本, col1=交易金额, col2=占比] 三列
        if ("4 供应商清单".equals(sheetName) || "5 客户清单".equals(sheetName)) {
            List<List<Object>> result = new ArrayList<>();
            // 虚拟空表头（3列），供 fillTableByRowTemplate 跳过 index=0
            result.add(Arrays.asList("", "", ""));

            for (int i = 7; i < rows.size(); i++) {
                Map<Integer, Object> row = rows.get(i);
                Object col0 = row.get(0);
                Object col1 = row.get(1);
                Object col2 = row.get(2);
                Object col4 = row.get(4);
                Object col5 = row.get(5);

                String col0Str = col0 != null ? col0.toString().trim() : "";
                String col1Str = col1 != null ? col1.toString().trim() : "";
                String col2Str = col2 != null ? col2.toString().trim() : "";

                // 分组标题行：col0非空，col1为空（或空白），col2为空
                if (!col0Str.isEmpty() && col1Str.isEmpty() && col2Str.isEmpty()) {
                    // 小计/总计行
                    if (col0Str.contains("小计") || col0Str.contains("合计") || col0Str.contains("总计")) {
                        result.add(Arrays.asList(col0Str, toPlainString(col4), toPlainString(col5)));
                    } else {
                        // 纯分组标题行
                        result.add(Arrays.asList(col0Str, "", ""));
                    }
                    continue;
                }

                // col1含"小计"/"合计"/"总计"
                if (!col1Str.isEmpty() && (col1Str.contains("小计") || col1Str.contains("合计") || col1Str.contains("总计"))) {
                    result.add(Arrays.asList(col1Str, toPlainString(col4), toPlainString(col5)));
                    continue;
                }

                // 明细行：col1为纯数字编号 或 "其他"
                boolean isSeqNum = col1Str.matches("^\\d+$");
                boolean isOther = "其他".equals(col1Str);
                if (isSeqNum || isOther) {
                    if (col2Str.isEmpty() && !isOther) continue; // 名称为空的明细行跳过
                    String name = isOther ? "其他" : col2Str;
                    if (!name.isEmpty()) {
                        result.add(Arrays.asList(name, toPlainString(col4), toPlainString(col5)));
                    }
                    continue;
                }

                // col0含"非关联"：关联区域结束，停止扫描
                if (col0Str.contains("非关联")) {
                    break;
                }
            }

            log.debug("[ReportEngine] Sheet '{}' 行模板数据提取完成，共 {} 行（含虚拟表头）", sheetName, result.size());
            return result;
        }

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

    /**
     * 替换页眉/页脚中的文本占位符，覆盖段落和表格单元格。
     * 参考反向引擎 ReverseTemplateEngine 页眉/页脚处理结构。
     */
    private void replaceHeaderFooterPlaceholders(XWPFDocument doc, Map<String, String> textValues) {
        for (XWPFHeader header : doc.getHeaderList()) {
            for (XWPFParagraph paragraph : header.getParagraphs()) {
                replacePlaceholdersInParagraph(paragraph, textValues);
            }
            for (XWPFTable table : header.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph paragraph : cell.getParagraphs()) {
                            replacePlaceholdersInParagraph(paragraph, textValues);
                        }
                    }
                }
            }
        }
        for (XWPFFooter footer : doc.getFooterList()) {
            for (XWPFParagraph paragraph : footer.getParagraphs()) {
                replacePlaceholdersInParagraph(paragraph, textValues);
            }
            for (XWPFTable table : footer.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph paragraph : cell.getParagraphs()) {
                            replacePlaceholdersInParagraph(paragraph, textValues);
                        }
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
                            // 检测该表格是否存在行模板标记（{{_tpl_}}），有则走克隆路径
                            boolean hasRowTemplate = tableHasRowTemplateMarker(table);
                            if (hasRowTemplate) {
                                fillTableByRowTemplate(table, data);
                            } else {
                                // 填充数据到该表格（从第二行开始追加，第一行保留为表头）
                                fillTableWithData(table, data);
                            }
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

    /**
     * 年度字段扩展：占位符名以 "-B2" 结尾且值为 2 位数字时，
     * 将原始值扩展为 "20XX年" 格式（如 "24" → "2024年"）。
     *
     * @param placeholderName 占位符名称
     * @param value           从 Excel 提取的原始值
     * @return 扩展后的值；不满足条件时原样返回
     */
    private String expandYearValue(String placeholderName, String value) {
        if (placeholderName != null && placeholderName.endsWith("-B2")
                && value != null && value.matches("^\\d{2}$")) {
            return "20" + value + "年";
        }
        return value;
    }

    /**
     * 检测表格中是否存在行模板标记（单元格文本含 "{{_tpl_" 前缀）。
     */
    private boolean tableHasRowTemplateMarker(XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                String text = cell.getText();
                if (text != null && text.contains("{{_tpl_")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 行模板克隆方式填充表格。
     * <p>
     * 逻辑：
     * <ol>
     *   <li>扫描表格，找到含 {@code {{_tpl_}} 标记的模板行（tplRowIdx）</li>
     *   <li>对 data 第1行起每条数据，深克隆模板行 XML（{@link CTRow#copy()}），清空单元格后逐列写入数据</li>
     *   <li>将所有新行插入模板行之前（通过 CTTbl XML 操作），最后删除原模板行</li>
     *   <li>若未找到模板行，退回 {@link #fillTableWithData(XWPFTable, List)}</li>
     * </ol>
     * </p>
     *
     * @param table 目标 Word 表格
     * @param data  数据列表，index=0 为虚拟表头（跳过），index=1~N 为实际数据行，每行3列（名称/金额/占比）
     */
    private void fillTableByRowTemplate(XWPFTable table, List<List<Object>> data) {
        if (data == null || data.size() <= 1) {
            log.warn("[ReportEngine-RowTemplate] 数据为空或只有表头行，跳过填充");
            return;
        }

        // 1. 找到模板行
        int tplRowIdx = -1;
        for (int i = 0; i < table.getRows().size(); i++) {
            XWPFTableRow row = table.getRow(i);
            for (XWPFTableCell cell : row.getTableCells()) {
                if (cell.getText() != null && cell.getText().contains("{{_tpl_")) {
                    tplRowIdx = i;
                    break;
                }
            }
            if (tplRowIdx >= 0) break;
        }

        if (tplRowIdx < 0) {
            log.warn("[ReportEngine-RowTemplate] 未找到含 {{_tpl_}} 标记的模板行，退回 fillTableWithData");
            fillTableWithData(table, data);
            return;
        }

        XWPFTableRow tplRow = table.getRow(tplRowIdx);
        CTRow tplCt = tplRow.getCtRow();
        CTTbl ctTbl = table.getCTTbl();
        int tableColCount = tplRow.getTableCells().size();

        // 2. 克隆并插入新行（在模板行之前）
        // data 第0行为虚拟表头，跳过；从 index=1 开始
        int insertedCount = 0;
        for (int di = 1; di < data.size(); di++) {
            List<Object> dataRow = data.get(di);

            // 深克隆模板行 XML
            CTRow newCt = (CTRow) tplCt.copy();
            XWPFTableRow newRow = new XWPFTableRow(newCt, table);

            // 清空并写入数据（按列 0/1/2）
            List<XWPFTableCell> newCells = newRow.getTableCells();
            int colLimit = Math.min(dataRow.size(), Math.min(newCells.size(), tableColCount));
            for (int ci = 0; ci < colLimit; ci++) {
                XWPFTableCell cell = newCells.get(ci);
                String cellVal = dataRow.get(ci) != null ? dataRow.get(ci).toString() : "";
                // 清空模板占位符文本，写入实际数据
                setCellText(cell, cellVal);
            }
            // 多余列清空
            for (int ci = colLimit; ci < newCells.size(); ci++) {
                setCellText(newCells.get(ci), "");
            }

            // 将新行 CTRow 插入到 CTTbl 中模板行之前
            int insertPos = tplRowIdx + insertedCount;
            // 通过 CTTbl 的 insertNewTr 在指定位置插入，然后将克隆的 CTRow 内容拷贝过去
            // 由于 Apache POI 没有直接的"在指定位置插入已有CTRow"API，
            // 使用 xmlbeans 的 selectPath / domNode 方式操作
            org.w3c.dom.Node tblNode = ctTbl.getDomNode();
            org.w3c.dom.NodeList trNodes = tblNode.getChildNodes();
            // 找到第 insertPos 个 <w:tr> 节点
            int trCount = 0;
            org.w3c.dom.Node refNode = null;
            for (int ni = 0; ni < trNodes.getLength(); ni++) {
                org.w3c.dom.Node n = trNodes.item(ni);
                if ("w:tr".equals(n.getNodeName())) {
                    if (trCount == insertPos) {
                        refNode = n;
                        break;
                    }
                    trCount++;
                }
            }
            org.w3c.dom.Node newTrNode = newCt.getDomNode();
            // importNode 以确保节点属于同一 Document
            org.w3c.dom.Node imported = tblNode.getOwnerDocument().importNode(newTrNode, true);
            if (refNode != null) {
                tblNode.insertBefore(imported, refNode);
            } else {
                tblNode.appendChild(imported);
            }
            insertedCount++;
        }

        // 3. 删除原始模板行（其在 CTTbl 中的位置已向后移了 insertedCount 个）
        int realTplIdx = tplRowIdx + insertedCount;
        org.w3c.dom.Node tblNode2 = ctTbl.getDomNode();
        org.w3c.dom.NodeList trNodes2 = tblNode2.getChildNodes();
        int trCount2 = 0;
        for (int ni = 0; ni < trNodes2.getLength(); ni++) {
            org.w3c.dom.Node n = trNodes2.item(ni);
            if ("w:tr".equals(n.getNodeName())) {
                if (trCount2 == realTplIdx) {
                    tblNode2.removeChild(n);
                    break;
                }
                trCount2++;
            }
        }

        log.info("[ReportEngine-RowTemplate] 行模板克隆完成：模板行idx={}，克隆插入 {} 行，数据行数={}",
                tplRowIdx, insertedCount, data.size() - 1);
    }

    /**
     * 清空单元格所有段落文本并写入指定值（使用第一个 Run，不改变格式）。
     */
    private void setCellText(XWPFTableCell cell, String value) {
        boolean first = true;
        for (XWPFParagraph para : cell.getParagraphs()) {
            List<XWPFRun> runs = para.getRuns();
            if (!runs.isEmpty()) {
                runs.get(0).setText(first ? value : "", 0);
                first = false;
                for (int r = 1; r < runs.size(); r++) {
                    runs.get(r).setText("", 0);
                }
            } else if (first) {
                para.createRun().setText(value);
                first = false;
            }
        }
    }

    /**
     * 将 Excel 读取到的数值对象转为普通字符串（避免科学计数法）。
     * Double → BigDecimal.toPlainString；其他 → toString；null → ""。
     */
    private String toPlainString(Object val) {
        if (val == null) return "";
        if (val instanceof Double) {
            return BigDecimal.valueOf((Double) val).stripTrailingZeros().toPlainString();
        }
        return val.toString().trim();
    }
}
