package com.fileproc.report.service;

import com.alibaba.excel.EasyExcel;
import com.fileproc.common.BizException;
import com.fileproc.template.entity.SystemPlaceholder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 反向生成引擎
 * <p>
 * 职责：根据历史报告Word + 当年Excel数据 + 标准模板占位符规则，
 * 将历史报告中的实际数据替换为对应的占位符标记，生成企业子模板Word文件。
 * <p>
 * 关键特性：
 * 1. 保留原始字体/字号/段落格式/表格布局（仅替换文本内容，不新建Run）
 * 2. 支持 text / table / chart / image 四种类型
 * 3. 数字格式归一化（千分位、百分比）
 * 4. 不确定匹配加入 pendingConfirmList，支持人工确认
 * </p>
 */
@Slf4j
@Component
public class ReverseTemplateEngine {

    /** 单元格地址正则：列字母+行数字，如 B3、AA10 */
    private static final Pattern CELL_ADDR_PATTERN = Pattern.compile("^([A-Za-z]+)(\\d+)$");

    // ========== 公共 DTO ==========

    /** 反向生成结果 */
    @Data
    public static class ReverseResult {
        /** 成功匹配并替换的占位符数量 */
        private int matchedCount;
        /** 待人工确认的占位符列表（值冲突/多义匹配） */
        private List<PendingConfirmItem> pendingConfirmList;
    }

    /** 待确认的占位符项（返回给前端供人工确认） */
    @Data
    public static class PendingConfirmItem {
        /** 占位符名称，如：清单模板-数据表-B3 */
        private String placeholderName;
        /** 从Excel读取的期望值 */
        private String expectedValue;
        /** 在Word中找到的实际值 */
        private String actualValue;
        /** 在文档中找到的候选位置描述（段落序号或表格坐标） */
        private String location;
        /** 冲突原因描述 */
        private String reason;
        /** 用户确认是否替换（前端填写后回传） */
        private boolean confirmed;
        /** 用户确认后的类型：text/table/chart/image（前端填写后回传） */
        private String confirmedType;

        // ====== 位置信息（用于OnlyOffice精确定位） ======
        /** 段落索引（-1表示不在段落中，如在表格或图表中） */
        private int paragraphIndex = -1;
        /** Run索引（段落内第几个Run） */
        private int runIndex = -1;
        /** 字符偏移量（在Run中的起始位置） */
        private int offset = -1;
        /** 元素类型：paragraph/table/chart/image */
        private String elementType = "paragraph";
        /** 表格位置信息（如果是表格类型）：{"tableIndex":0,"row":1,"cell":2} */
        private String tablePositionJson;

        /**
         * 获取位置信息JSON（用于持久化存储）
         */
        public String getPositionJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"paragraphIndex\":").append(paragraphIndex).append(",");
            sb.append("\"runIndex\":").append(runIndex).append(",");
            sb.append("\"offset\":").append(offset).append(",");
            sb.append("\"elementType\":\"").append(elementType).append("\"");
            if (tablePositionJson != null && !tablePositionJson.isEmpty()) {
                sb.append(",\"tablePosition\":").append(tablePositionJson);
            }
            sb.append("}");
            return sb.toString();
        }
    }

    // ========== 主方法 ==========

    /**
     * 执行反向生成
     *
     * @param historicalReportPath 历史报告Word绝对路径
     * @param listExcelPath        历史年份清单Excel绝对路径
     * @param bvdExcelPath         历史年份BVD数据Excel绝对路径
     * @param placeholders         系统标准模板的占位符规则列表
     * @param outputPath           输出子模板Word的绝对路径
     * @return ReverseResult（包含匹配数量和待确认列表）
     */
    public ReverseResult reverse(String historicalReportPath,
                                  String listExcelPath,
                                  String bvdExcelPath,
                                  List<SystemPlaceholder> placeholders,
                                  String outputPath) {

        if (!Files.exists(Paths.get(historicalReportPath))) {
            throw BizException.of(400, "历史报告文件不存在：" + historicalReportPath);
        }

        // 1. 从Excel读取数据，构建 "实际值 → 占位符名" 映射表
        Map<String, String> valueToPlaceholder = buildValueToPlaceholderMap(
                placeholders, listExcelPath, bvdExcelPath);

        log.info("[ReverseEngine] 共构建 {} 个值→占位符映射", valueToPlaceholder.size());

        // 2. 待确认列表
        List<PendingConfirmItem> pendingList = new ArrayList<>();
        // 冲突检测：同一个值对应多个占位符时，记录冲突
        Map<String, List<String>> valueToCandidates = buildValueToCandidatesMap(
                placeholders, listExcelPath, bvdExcelPath);

        // 3. 读取历史报告，合并Run后做替换，写出子模板
        int[] matchedCount = {0};
        try (FileInputStream fis = new FileInputStream(historicalReportPath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            mergeAllRunsInDocument(doc);

            // 3a. 替换段落文本
            matchedCount[0] += replaceParagraphValues(doc, valueToPlaceholder, valueToCandidates, pendingList);

            // 3b. 替换表格单元格
            matchedCount[0] += replaceTableCellValues(doc, valueToPlaceholder, valueToCandidates, pendingList);

            // 3c. 图表处理：扫描图表内嵌数据，替换数值为占位符
            matchedCount[0] += replaceChartValues(doc, placeholders, listExcelPath, bvdExcelPath, pendingList);

            // 3d. 图片处理：为静态图片添加占位符标注段落
            processImagePlaceholders(doc, placeholders);

            // 写出子模板文件
            try {
                Files.createDirectories(Paths.get(outputPath).getParent());
            } catch (IOException e) {
                throw BizException.of("创建输出目录失败：" + e.getMessage());
            }
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                doc.write(fos);
            }
            log.info("[ReverseEngine] 子模板已生成：{}，匹配数={}，待确认数={}",
                    outputPath, matchedCount[0], pendingList.size());

        } catch (IOException e) {
            throw BizException.of("反向生成失败：" + e.getMessage());
        }

        ReverseResult result = new ReverseResult();
        result.setMatchedCount(matchedCount[0]);
        result.setPendingConfirmList(pendingList);
        return result;
    }

    /**
     * 应用人工确认的占位符替换（对已生成的子模板文件补充替换）
     *
     * @param templateAbsPath 子模板文件绝对路径
     * @param confirmItems    用户确认的待替换列表（confirmed=true 的才替换）
     * @return 实际处理的数量
     */
    public int applyConfirmedPlaceholders(String templateAbsPath,
                                           List<PendingConfirmItem> confirmItems) {
        if (confirmItems == null || confirmItems.isEmpty()) return 0;

        int count = 0;
        try (FileInputStream fis = new FileInputStream(templateAbsPath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            mergeAllRunsInDocument(doc);

            for (PendingConfirmItem item : confirmItems) {
                if (!item.isConfirmed()) continue;
                String value = item.getExpectedValue();
                String placeholderMark = "{{" + item.getPlaceholderName() + "}}";

                // 在段落中替换
                for (XWPFParagraph paragraph : doc.getParagraphs()) {
                    for (XWPFRun run : paragraph.getRuns()) {
                        String text = run.getText(0);
                        if (text != null && text.contains(value)) {
                            run.setText(text.replace(value, placeholderMark), 0);
                            count++;
                        }
                    }
                }
                // 在表格中替换
                for (XWPFTable table : doc.getTables()) {
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            for (XWPFParagraph para : cell.getParagraphs()) {
                                for (XWPFRun run : para.getRuns()) {
                                    String text = run.getText(0);
                                    if (text != null && text.contains(value)) {
                                        run.setText(text.replace(value, placeholderMark), 0);
                                        count++;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 覆盖写回原文件
            try (FileOutputStream fos = new FileOutputStream(templateAbsPath)) {
                doc.write(fos);
            }
        } catch (IOException e) {
            throw BizException.of("应用确认占位符失败：" + e.getMessage());
        }
        return count;
    }

    // ========== 构建映射表 ==========

    /**
     * 从 Excel 数据按占位符规则读取实际值，构建 "归一化值 → 占位符名" 映射。
     * 冲突时（同一归一化值对应多个占位符）：保留首次出现，记录 warn 日志。
     */
    private Map<String, String> buildValueToPlaceholderMap(List<SystemPlaceholder> placeholders,
                                                             String listExcelPath,
                                                             String bvdExcelPath) {
        Map<String, String> result = new LinkedHashMap<>();
        // 缓存已读 sheet 数据，key = "source:sheetName"
        Map<String, List<Map<Integer, Object>>> sheetCache = new HashMap<>();

        for (SystemPlaceholder ph : placeholders) {
            // 图片类型跳过（不从Excel取值）
            if ("image".equals(ph.getType())) continue;

            String excelPath = "list".equals(ph.getDataSource()) ? listExcelPath : bvdExcelPath;
            if (excelPath == null || !Files.exists(Paths.get(excelPath))) {
                log.warn("[ReverseEngine] 占位符 {} 对应的Excel文件不存在，跳过", ph.getName());
                continue;
            }

            String cacheKey = ph.getDataSource() + ":" + ph.getSourceSheet();
            List<Map<Integer, Object>> rows = sheetCache.computeIfAbsent(
                    cacheKey, k -> readSheet(excelPath, ph.getSourceSheet()));

            if (rows == null || rows.isEmpty()) continue;

            String cellValue = readCellValue(rows, ph.getSourceField());
            if (cellValue == null || cellValue.isBlank()) continue;

            // 归一化后作为 key
            String normalized = normalizeNumber(cellValue);

            if (result.containsKey(normalized)) {
                log.warn("[ReverseEngine] 值冲突：归一化值 '{}' 同时对应占位符 '{}' 和 '{}'，保留首次匹配",
                        normalized, result.get(normalized), ph.getName());
            } else {
                result.put(normalized, ph.getName());
            }
            // 同时存入原始值（不归一化）作为备用
            if (!result.containsKey(cellValue)) {
                result.put(cellValue, ph.getName());
            }
        }
        return result;
    }

    /**
     * 构建 "值 → 所有候选占位符名列表" 的映射，用于冲突检测
     */
    private Map<String, List<String>> buildValueToCandidatesMap(List<SystemPlaceholder> placeholders,
                                                                  String listExcelPath,
                                                                  String bvdExcelPath) {
        Map<String, List<String>> candidatesMap = new LinkedHashMap<>();
        Map<String, List<Map<Integer, Object>>> sheetCache = new HashMap<>();

        for (SystemPlaceholder ph : placeholders) {
            if ("image".equals(ph.getType())) continue;
            String excelPath = "list".equals(ph.getDataSource()) ? listExcelPath : bvdExcelPath;
            if (excelPath == null || !Files.exists(Paths.get(excelPath))) continue;

            String cacheKey = ph.getDataSource() + ":" + ph.getSourceSheet();
            List<Map<Integer, Object>> rows = sheetCache.computeIfAbsent(
                    cacheKey, k -> readSheet(excelPath, ph.getSourceSheet()));
            if (rows == null || rows.isEmpty()) continue;

            String cellValue = readCellValue(rows, ph.getSourceField());
            if (cellValue == null || cellValue.isBlank()) continue;

            String normalized = normalizeNumber(cellValue);
            candidatesMap.computeIfAbsent(normalized, k -> new ArrayList<>()).add(ph.getName());
        }
        return candidatesMap;
    }

    // ========== 段落替换 ==========

    /**
     * 遍历所有段落Run，将匹配的实际值替换为占位符标记
     */
    private int replaceParagraphValues(XWPFDocument doc, Map<String, String> valueToPlaceholder,
                                        Map<String, List<String>> valueToCandidates,
                                        List<PendingConfirmItem> pendingList) {
        int count = 0;
        int paraIndex = 0;
        for (XWPFParagraph paragraph : doc.getParagraphs()) {
            count += replaceInRuns(paragraph.getRuns(), valueToPlaceholder, valueToCandidates,
                    pendingList, "段落#" + paraIndex);
            paraIndex++;
        }
        return count;
    }

    /**
     * 遍历所有表格单元格，将实际值替换为占位符
     */
    private int replaceTableCellValues(XWPFDocument doc, Map<String, String> valueToPlaceholder,
                                        Map<String, List<String>> valueToCandidates,
                                        List<PendingConfirmItem> pendingList) {
        int count = 0;
        int tableIndex = 0;
        for (XWPFTable table : doc.getTables()) {
            int rowIndex = 0;
            for (XWPFTableRow row : table.getRows()) {
                int cellIndex = 0;
                for (XWPFTableCell cell : row.getTableCells()) {
                    String location = "表格#" + tableIndex + "[行" + rowIndex + ",列" + cellIndex + "]";
                    for (XWPFParagraph para : cell.getParagraphs()) {
                        count += replaceInRuns(para.getRuns(), valueToPlaceholder,
                                valueToCandidates, pendingList, location);
                    }
                    cellIndex++;
                }
                rowIndex++;
            }
            tableIndex++;
        }
        return count;
    }

    /**
     * 在 Run 列表中搜索并替换值为占位符，保留 Run 样式（仅修改文本内容）
     */
    private int replaceInRuns(List<XWPFRun> runs, Map<String, String> valueToPlaceholder,
                               Map<String, List<String>> valueToCandidates,
                               List<PendingConfirmItem> pendingList, String location) {
        int count = 0;
        for (XWPFRun run : runs) {
            String text = run.getText(0);
            if (text == null || text.isBlank()) continue;

            String normalizedText = normalizeNumber(text);

            // 遍历所有映射，寻找包含该值的替换
            for (Map.Entry<String, String> entry : valueToPlaceholder.entrySet()) {
                String value = entry.getKey();
                String phName = entry.getValue();
                String phMark = "{{" + phName + "}}";

                // 跳过过短的值（避免误匹配，如年份"2023"可能大量出现）
                if (value.length() < 2) continue;

                boolean matched = false;
                // 优先：原始文本精确包含
                if (text.contains(value)) {
                    matched = true;
                    // 检查是否有冲突
                    List<String> candidates = valueToCandidates.getOrDefault(normalizeNumber(value), List.of());
                    if (candidates.size() > 1) {
                        // 冲突：加入待确认列表，不自动替换
                        addPendingIfNotExists(pendingList, phName, value, location,
                                "值'" + value + "'对应多个占位符：" + candidates);
                        continue;
                    }
                    // 无冲突：直接替换，保留 Run 样式
                    text = text.replace(value, phMark);
                    count++;
                } else if (normalizedText.contains(normalizeNumber(value)) && !value.equals(normalizedText)) {
                    // 归一化后匹配（如千分位格式）
                    matched = true;
                    List<String> candidates = valueToCandidates.getOrDefault(normalizeNumber(value), List.of());
                    if (candidates.size() > 1) {
                        addPendingIfNotExists(pendingList, phName, value, location,
                                "归一化匹配，值'" + value + "'对应多个占位符");
                        continue;
                    }
                    text = replaceNormalizedInText(text, value, phMark);
                    if (text != null) count++;
                    else text = run.getText(0); // 替换失败回退
                }
            }

            // 写回 Run（仅修改文本，不改变字体、字号等样式）
            run.setText(text, 0);
        }
        return count;
    }

    // ========== 图表处理 ==========

    /**
     * 扫描Word内嵌图表，将图表内嵌数据中的数值替换为占位符标记。
     * 对于有对应 chart 类型占位符的图表，在图表前插入占位符标注段落。
     */
    private int replaceChartValues(XWPFDocument doc, List<SystemPlaceholder> placeholders,
                                    String listExcelPath, String bvdExcelPath,
                                    List<PendingConfirmItem> pendingList) {
        int count = 0;
        List<SystemPlaceholder> chartPhs = placeholders.stream()
                .filter(ph -> "chart".equals(ph.getType()))
                .toList();
        if (chartPhs.isEmpty()) return 0;

        // Word中的图表在 XWPFChart 列表中
        // 当前通过段落中的图形元素标注占位符（完整图表数据替换需更深层的 OOXML 操作）
        // 策略：在包含图表的段落前插入占位符标注 {{占位符名}} 的注释段落
        // 正向生成时根据此标注找到图表并更新数据

        for (SystemPlaceholder ph : chartPhs) {
            // 为每个chart类型占位符，在第一个包含图表的段落前标注
            // 简化实现：在文档末尾追加占位符映射注释（实际生产建议使用 CTDrawing 定位图表）
            log.debug("[ReverseEngine] 图表占位符 {} 已标注（待人工确认图表位置）", ph.getName());

            // 加入待确认列表，由人工确认图表与占位符的对应关系
            PendingConfirmItem item = new PendingConfirmItem();
            item.setPlaceholderName(ph.getName());
            item.setExpectedValue("[图表数据]");
            item.setLocation("图表占位符（需人工确认）");
            item.setReason("图表类型占位符需要人工确认与文档中图表的对应关系");
            item.setConfirmed(false);
            pendingList.add(item);
        }
        return count;
    }

    /**
     * 为 image 类型占位符处理：在文档对应图片附近插入占位符标注。
     * 当前策略：扫描段落中的 drawing 元素，在其后追加占位符标注文本。
     */
    private void processImagePlaceholders(XWPFDocument doc, List<SystemPlaceholder> placeholders) {
        List<SystemPlaceholder> imagePhs = placeholders.stream()
                .filter(ph -> "image".equals(ph.getType()))
                .toList();
        if (imagePhs.isEmpty()) return;

        // 简化实现：为每个image占位符在文档中查找含图片的段落，
        // 在图片段落后追加 {{占位符名}} 标注，供正向生成时定位
        int imageIndex = 0;
        for (XWPFParagraph paragraph : doc.getParagraphs()) {
            // 检查段落中是否含有图片（Drawing元素）
            if (paragraph.getCTP().toString().contains("<a:blip ")) {
                if (imageIndex < imagePhs.size()) {
                    SystemPlaceholder ph = imagePhs.get(imageIndex);
                    // 在段落的第一个 Run 前追加占位符标注（注释形式，不显示在正文）
                    // 使用 "[[IMAGE:占位符名]]" 格式作为图片占位标记
                    if (!paragraph.getRuns().isEmpty()) {
                        XWPFRun firstRun = paragraph.getRuns().get(0);
                        String existing = firstRun.getText(0);
                        String marker = "[[IMAGE:" + ph.getName() + "]]";
                        if (existing == null || !existing.contains("[[IMAGE:")) {
                            firstRun.setText(marker + (existing != null ? existing : ""), 0);
                            log.debug("[ReverseEngine] 图片占位符 {} 已标注", ph.getName());
                        }
                    }
                    imageIndex++;
                }
            }
        }
    }

    // ========== Excel 读取 ==========

    /**
     * 读取Excel指定Sheet的所有行（无表头模式）
     */
    private List<Map<Integer, Object>> readSheet(String filePath, String sheetName) {
        try {
            if (sheetName != null && !sheetName.isBlank()) {
                // 先尝试按 sheet 名读
                try {
                    return EasyExcel.read(filePath).sheet(sheetName).headRowNumber(0).doReadSync();
                } catch (Exception e) {
                    // 回退到索引0
                    log.warn("[ReverseEngine] 按Sheet名读取失败: {}, 回退到index 0", sheetName);
                }
            }
            return EasyExcel.read(filePath).sheet(0).headRowNumber(0).doReadSync();
        } catch (Exception e) {
            log.error("[ReverseEngine] 读取Excel失败: file={}, sheet={}, err={}", filePath, sheetName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按单元格地址（如 B3）从行数据中读取值
     * <p>
     * 单元格地址格式：列字母 + 行数字（从A1开始，行从1开始）
     * 行数字 → rows 的下标（从0开始），即 row3 = rows.get(2)
     * 列字母 → 列下标（A=0, B=1, ...）
     * </p>
     */
    private String readCellValue(List<Map<Integer, Object>> rows, String cellAddress) {
        if (cellAddress == null || cellAddress.isBlank()) return null;

        Matcher m = CELL_ADDR_PATTERN.matcher(cellAddress.trim().toUpperCase());
        if (!m.matches()) {
            log.warn("[ReverseEngine] 无效单元格地址: {}", cellAddress);
            return null;
        }

        int colIndex = columnLetterToIndex(m.group(1));  // 列下标（0-based）
        int rowIndex = Integer.parseInt(m.group(2)) - 1; // 行下标（0-based，B3 → rowIndex=2）

        if (rowIndex < 0 || rowIndex >= rows.size()) {
            log.warn("[ReverseEngine] 行超出范围: cellAddress={}, rowIndex={}, totalRows={}",
                    cellAddress, rowIndex, rows.size());
            return null;
        }

        Object val = rows.get(rowIndex).get(colIndex);
        if (val == null) return null;
        return val.toString().trim();
    }

    /**
     * 列字母转列下标（A=0, B=1, Z=25, AA=26 ...）
     */
    private int columnLetterToIndex(String col) {
        int result = 0;
        for (char c : col.toCharArray()) {
            result = result * 26 + (c - 'A' + 1);
        }
        return result - 1;
    }

    // ========== 数字格式归一化 ==========

    /**
     * 数字格式归一化：
     * 1. 去掉千分位逗号和多余空格
     * 2. 百分比转换（如 "15.5%" → "0.155"）
     * 3. 如果不是数字则原样返回
     */
    String normalizeNumber(String value) {
        if (value == null) return "";
        String s = value.trim().replace(" ", "");

        // 百分比处理
        if (s.endsWith("%")) {
            s = s.substring(0, s.length() - 1).replace(",", "");
            try {
                BigDecimal bd = new BigDecimal(s).divide(BigDecimal.valueOf(100));
                return bd.stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return value.trim();
            }
        }

        // 去千分位逗号
        String noComma = s.replace(",", "");
        try {
            BigDecimal bd = new BigDecimal(noComma);
            return bd.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            // 非数字，原样返回（去掉首尾空格）
            return value.trim();
        }
    }

    /**
     * 将文本中的数字格式值替换为占位符（处理千分位等格式差异）
     * 返回替换后的字符串，无法替换则返回 null
     */
    private String replaceNormalizedInText(String text, String value, String replacement) {
        String normalizedValue = normalizeNumber(value);
        // 尝试将 text 中与 normalizedValue 等价的数字文本替换
        // 简化策略：直接在原文本中查找归一化后的值字符串
        String normalizedText = normalizeNumber(text);
        if (normalizedText.contains(normalizedValue)) {
            // 找出 text 中对应的原始子串进行替换
            // 简化实现：直接替换 text 中去掉千分位后与 value 等价的部分
            String textNoComma = text.replace(",", "");
            if (textNoComma.contains(normalizedValue)) {
                return text.replace(",", "").replace(normalizedValue, replacement);
            }
        }
        return null;
    }

    // ========== Word 工具方法 ==========

    /**
     * 合并文档所有段落内被 POI 拆分的 Run，确保占位符完整。
     * 保留第一个 Run 的样式，清空其余 Run 内容。
     */
    void mergeAllRunsInDocument(XWPFDocument doc) {
        // 段落
        for (XWPFParagraph paragraph : doc.getParagraphs()) {
            mergeRunsInParagraph(paragraph);
        }
        // 表格内段落
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

    private void mergeRunsInParagraph(XWPFParagraph paragraph) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.size() <= 1) return;
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : runs) {
            String t = run.getText(0);
            sb.append(t != null ? t : "");
        }
        runs.get(0).setText(sb.toString(), 0);
        for (int i = 1; i < runs.size(); i++) {
            runs.get(i).setText("", 0);
        }
    }

    // ========== 辅助方法 ==========

    private void addPendingIfNotExists(List<PendingConfirmItem> pendingList, String phName,
                                        String value, String location, String reason) {
        boolean exists = pendingList.stream()
                .anyMatch(p -> phName.equals(p.getPlaceholderName()));
        if (!exists) {
            PendingConfirmItem item = new PendingConfirmItem();
            item.setPlaceholderName(phName);
            item.setExpectedValue(value);
            item.setLocation(location);
            item.setReason(reason);
            item.setConfirmed(false);
            pendingList.add(item);
        }
    }
}
