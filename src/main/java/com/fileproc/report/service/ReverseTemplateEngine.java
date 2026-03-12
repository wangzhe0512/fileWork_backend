package com.fileproc.report.service;

import com.alibaba.excel.EasyExcel;
import com.fileproc.common.BizException;
import com.fileproc.template.entity.SystemPlaceholder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.stream.Collectors;

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

    /**
     * 自动识别长文本Sheet的最小字符阈值：B列（或A列）中存在长度超过此值的单元格，则判定为长文本Sheet。
     * 根据实测清单，普通数据表字段最长约20字，行业情况/集团背景等长文本通常超过50字。
     */
    private static final int LONG_TEXT_MIN_CHARS = 50;

    /**
     * 短值安全替换：值长度低于此阈值时，不自动替换，标记为 uncertain（避免误替换）
     */
    private static final int SHORT_VALUE_THRESHOLD = 5;

    /**
     * 中等值（长度在 SHORT_VALUE_THRESHOLD ~ MEDIUM_VALUE_THRESHOLD 之间）：
     * 仅当在文档全文中唯一出现时才自动替换，否则标 uncertain。
     */
    private static final int MEDIUM_VALUE_THRESHOLD = 8;

    // ========== 公共 DTO ==========

    /** 反向生成结果 */
    @Data
    public static class ReverseResult {
        /** 成功匹配并替换的占位符数量 */
        private int matchedCount;
        /** 待人工确认的占位符列表（值冲突/多义匹配） */
        private List<PendingConfirmItem> pendingConfirmList;
        /** 所有匹配到的占位符列表（用于创建模块和占位符记录） */
        private List<MatchedPlaceholder> allMatchedPlaceholders;
        /** 未匹配到的长文本条目列表（供前端提示用户手动确认） */
        private List<ExcelEntry> unmatchedLongTextEntries;
    }

    /** 匹配到的占位符信息（用于创建模块和占位符记录） */
    @Data
    public static class MatchedPlaceholder {
        /** 占位符名称 */
        private String placeholderName;
        /** 期望的值（从Excel读取） */
        private String expectedValue;
        /** 实际值（在Word中找到） */
        private String actualValue;
        /** 位置描述 */
        private String location;
        /** 状态：confirmed-已确认替换，uncertain-待确认 */
        private String status;
        /** 所属模块编码 */
        private String moduleCode;
        /** 所属模块名称 */
        private String moduleName;
        /** 位置信息JSON */
        private String positionJson;
        // ====== 新增：来源信息（由引擎自身携带，Controller 持久化时不再依赖 systemPhMap） ======
        /** 数据来源：list 或 bvd */
        private String dataSource;
        /** 来源 Sheet 名 */
        private String sourceSheet;
        /** 来源单元格地址，如 B1 */
        private String sourceField;
    }

    /**
     * Excel 单元格条目（新引擎核心数据结构）
     * <p>
     * 代替旧的 SystemPlaceholder 规则列表，通过扫描清单 Excel 动态构建。
     * </p>
     */
    @Data
    public static class ExcelEntry {
        /** 原始单元格值（已 trim） */
        private String value;
        /** 占位符名称，如 "企业名称" 或 "行业情况-B1" */
        private String placeholderName;
        /** 数据来源，固定为 "list" */
        private String dataSource;
        /** Sheet 名 */
        private String sourceSheet;
        /** 单元格地址，如 "B1" */
        private String sourceField;
        /**
         * true = 长文本整段替换（行业情况/集团背景等），需全字符串精确匹配；
         * false = 数据表字段替换，按值长度降序、全局 replace
         */
        private boolean longText;
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

        // ====== 模块信息（用于反向生成时创建模块） ======
        /** 所属模块编码（从Sheet名转换） */
        private String moduleCode;
        /** 所属模块名称（Sheet原名） */
        private String moduleName;
        /** 排序序号 */
        private int sort;

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

    /** 模块信息（用于反向生成时创建模块记录） */
    @Data
    public static class ModuleInfo {
        /** 模块编码（由Sheet名转换） */
        private String code;
        /** 模块名称（Sheet原名） */
        private String name;
        /** 排序序号 */
        private int sort;
    }

    // ========== 主方法 ==========

    /**
     * 执行反向生成（新引擎：分层 Excel 扫描驱动，无需预定义 SystemPlaceholder 规则）
     *
     * <p>分层策略：
     * <ol>
     *   <li>第0层：长文本 Sheet（行业情况/集团背景等），整段精确匹配，先于数据表执行</li>
     *   <li>第1层：数据表 Sheet（A列=字段名，B列=值），按值长度降序替换</li>
     *   <li>第2层：BVD Excel（按企业名称定位目标行，提取财务数值，全部标 uncertain）</li>
     *   <li>第3层：表格类 Sheet（组织结构/关联方等），本期不处理</li>
     * </ol>
     *
     * @param historicalReportPath 历史报告Word绝对路径
     * @param listExcelPath        历史年份清单Excel绝对路径
     * @param bvdExcelPath         历史年份BVD数据Excel绝对路径（可选，传null则跳过）
     * @param outputPath           输出子模板Word的绝对路径
     * @return ReverseResult（包含匹配数量和待确认列表）
     */
    public ReverseResult reverse(String historicalReportPath,
                                  String listExcelPath,
                                  String bvdExcelPath,
                                  String outputPath) {

        if (!Files.exists(Paths.get(historicalReportPath))) {
            throw BizException.of(400, "历史报告文件不存在：" + historicalReportPath);
        }
        if (listExcelPath == null || !Files.exists(Paths.get(listExcelPath))) {
            throw BizException.of(400, "清单Excel文件不存在：" + listExcelPath);
        }

        // 1. 分层扫描清单 Excel，构建 ExcelEntry 列表（长文本在前，数据表字段按长度降序在后）
        List<ExcelEntry> entries = buildExcelEntries(listExcelPath);

        // 1b. 扫描 BVD Excel（按企业名称精准定位目标行，提取财务数值，全部标 uncertain）
        if (bvdExcelPath != null && Files.exists(Paths.get(bvdExcelPath))) {
            // 从清单数据表中取企业名称（占位符名为"企业名称"或含"企业"的第一个非空字段值）
            String companyName = extractCompanyNameFromEntries(entries);
            if (companyName != null) {
                List<ExcelEntry> bvdEntries = buildBvdEntries(bvdExcelPath, companyName);
                log.info("[ReverseEngine-New] BVD扫描：企业='{}', 找到条目={}", companyName, bvdEntries.size());
                // BVD条目插入到数据表条目之前（长文本之后），按值长度降序
                bvdEntries.sort((a, b) -> b.getValue().length() - a.getValue().length());
                entries.addAll(bvdEntries);
            } else {
                log.warn("[ReverseEngine-New] 无法从清单中提取企业名称，跳过BVD扫描");
            }
        }
        // 分离长文本条目和数据表条目，用于后续未匹配检测
        List<ExcelEntry> longTextEntries = entries.stream()
                .filter(ExcelEntry::isLongText)
                .toList();
        log.info("[ReverseEngine-New] 构建 ExcelEntry 共 {} 条（长文本={}, 数据表字段={}）",
                entries.size(), longTextEntries.size(), entries.size() - longTextEntries.size());

        // 2. 读取历史报告，合并 Run 后做替换，写出子模板
        List<PendingConfirmItem> pendingList = new ArrayList<>();
        List<MatchedPlaceholder> matchedList = new ArrayList<>();
        int[] matchedCount = {0};

        try (FileInputStream fis = new FileInputStream(historicalReportPath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            mergeAllRunsInDocument(doc);

            // 2a. 替换段落文本
            matchedCount[0] += replaceParagraphValuesNew(doc, entries, pendingList, matchedList);

            // 2b. 替换表格单元格
            matchedCount[0] += replaceTableCellValuesNew(doc, entries, pendingList, matchedList);

            // 写出子模板文件
            try {
                Files.createDirectories(Paths.get(outputPath).getParent());
            } catch (IOException e) {
                throw BizException.of("创建输出目录失败：" + e.getMessage());
            }
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                doc.write(fos);
            }
            log.info("[ReverseEngine-New] 子模板已生成：{}，匹配数={}，待确认数={}，总占位符数={}",
                    outputPath, matchedCount[0], pendingList.size(), matchedList.size());

        } catch (IOException e) {
            throw BizException.of("反向生成失败：" + e.getMessage());
        }

        // 检测未匹配的长文本条目
        List<ExcelEntry> unmatchedLongText = detectUnmatchedLongText(longTextEntries, matchedList);
        if (!unmatchedLongText.isEmpty()) {
            log.warn("[ReverseEngine-New] 以下长文本条目未能在Word中匹配到：{}",
                    unmatchedLongText.stream().map(ExcelEntry::getPlaceholderName).toList());
        }

        ReverseResult result = new ReverseResult();
        result.setMatchedCount(matchedCount[0]);
        result.setPendingConfirmList(pendingList);
        result.setAllMatchedPlaceholders(matchedList);
        result.setUnmatchedLongTextEntries(unmatchedLongText);
        return result;
    }

    /**
     * 分层扫描清单 Excel，构建 ExcelEntry 列表。
     *
     * <p>Sheet 类型自动识别规则（无需白名单配置）：
     * <ol>
     *   <li>Sheet名包含"数据" 或 等于"数据表/基本信息/Data/Info" → 数据表Sheet（A列=字段名，B列=值）</li>
     *   <li>Sheet 内 B 列（或 A 列）存在长度 &gt; {@value #LONG_TEXT_MIN_CHARS} 的单元格 → 长文本Sheet</li>
     *   <li>其余 Sheet → 跳过（表格类/BVD等）</li>
     * </ol>
     *
     * <p>构建顺序：长文本 Sheet 条目（isLongText=true）在前，数据表字段条目在后（按值长度降序）。
     *
     * @param listExcelPath 清单Excel绝对路径
     * @return 有序的 ExcelEntry 列表
     */
    private List<ExcelEntry> buildExcelEntries(String listExcelPath) {
        List<ExcelEntry> longTextEntries = new ArrayList<>();
        List<ExcelEntry> dataTableEntries = new ArrayList<>();

        List<String> sheetNames = readSheetNames(listExcelPath);
        log.info("[ReverseEngine-New] 清单Excel共 {} 个Sheet: {}", sheetNames.size(), sheetNames);

        for (int sheetIdx = 0; sheetIdx < sheetNames.size(); sheetIdx++) {
            String sheetName = sheetNames.get(sheetIdx);
            List<Map<Integer, Object>> rows = readSheetByIndex(listExcelPath, sheetIdx);
            if (rows == null || rows.isEmpty()) continue;

            SheetType sheetType = detectSheetType(sheetName, rows);
            log.info("[ReverseEngine-New] Sheet '{}' 自动识别类型: {}", sheetName, sheetType);

            if (sheetType == SheetType.DATA_TABLE) {
                // 数据表 Sheet：A列=字段名，B列=值
                for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
                    Map<Integer, Object> row = rows.get(rowIdx);
                    Object colA = row.get(0);
                    Object colB = row.get(1);
                    if (colA == null || colB == null) continue;

                    String fieldName = colA.toString().trim();
                    String value = colB.toString().trim();
                    if (fieldName.isBlank() || value.isBlank()) continue;

                    ExcelEntry entry = new ExcelEntry();
                    entry.setValue(value);
                    entry.setPlaceholderName(sanitizePlaceholderName(fieldName));
                    entry.setDataSource("list");
                    entry.setSourceSheet(sheetName);
                    entry.setSourceField("B" + (rowIdx + 1));
                    entry.setLongText(false);
                    dataTableEntries.add(entry);
                }

            } else if (sheetType == SheetType.LONG_TEXT) {
                // 长文本 Sheet：逐行扫描，提取满足最小长度的单元格文本
                for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
                    Map<Integer, Object> row = rows.get(rowIdx);

                    // 优先取 B 列，B 列无长值则取 A 列
                    Object bVal = row.get(1);
                    Object aVal = row.get(0);
                    String value = null;
                    String cellAddr = null;

                    if (bVal != null && bVal.toString().trim().length() > LONG_TEXT_MIN_CHARS) {
                        value = bVal.toString().trim();
                        cellAddr = "B" + (rowIdx + 1);
                    } else if (aVal != null && aVal.toString().trim().length() > LONG_TEXT_MIN_CHARS) {
                        value = aVal.toString().trim();
                        cellAddr = "A" + (rowIdx + 1);
                    }

                    if (value == null) continue;

                    ExcelEntry entry = new ExcelEntry();
                    entry.setValue(value);
                    entry.setPlaceholderName(sanitizePlaceholderName(sheetName + "-" + cellAddr));
                    entry.setDataSource("list");
                    entry.setSourceSheet(sheetName);
                    entry.setSourceField(cellAddr);
                    entry.setLongText(true);
                    longTextEntries.add(entry);
                }

            } else {
                // 跳过表格类/BVD等Sheet
                log.debug("[ReverseEngine-New] Sheet '{}' 识别为 SKIP，跳过处理", sheetName);
            }
        }

        // 数据表条目按值长度降序排序（先替换长值，避免短值干扰）
        dataTableEntries.sort((a, b) -> b.getValue().length() - a.getValue().length());

        List<ExcelEntry> result = new ArrayList<>();
        result.addAll(longTextEntries);
        result.addAll(dataTableEntries);
        log.info("[ReverseEngine-New] ExcelEntry构建完成：长文本={}, 数据表字段={}", longTextEntries.size(), dataTableEntries.size());
        return result;
    }

    /**
     * Sheet 类型枚举
     */
    private enum SheetType {
        /** 数据表：A列=字段名，B列=短值 */
        DATA_TABLE,
        /** 长文本Sheet：含有整段百字以上文本 */
        LONG_TEXT,
        /** 跳过：表格类/BVD/其他 */
        SKIP
    }

    /**
     * 自动识别 Sheet 类型（无需白名单配置）。
     *
     * <p>判断逻辑（按优先级）：
     * <ol>
     *   <li>Sheet名包含"数据"，或属于已知数据表别名（基本信息/Data/Info等）→ DATA_TABLE</li>
     *   <li>Sheet 内任意行的 B 列或 A 列存在长度 &gt; {@value #LONG_TEXT_MIN_CHARS} 的单元格 → LONG_TEXT</li>
     *   <li>否则 → SKIP</li>
     * </ol>
     */
    private SheetType detectSheetType(String sheetName, List<Map<Integer, Object>> rows) {
        // 规则1：按Sheet名判断是否为数据表
        String lowerName = sheetName.trim().toLowerCase();
        if (lowerName.contains("数据") || lowerName.equals("基本信息")
                || lowerName.equals("data") || lowerName.equals("info")
                || lowerName.equals("基础数据") || lowerName.equals("基本数据")) {
            return SheetType.DATA_TABLE;
        }

        // 规则2：按内容判断——B列或A列存在超长单元格 → 长文本Sheet
        for (Map<Integer, Object> row : rows) {
            Object bVal = row.get(1);
            if (bVal != null && bVal.toString().trim().length() > LONG_TEXT_MIN_CHARS) {
                return SheetType.LONG_TEXT;
            }
            Object aVal = row.get(0);
            if (aVal != null && aVal.toString().trim().length() > LONG_TEXT_MIN_CHARS) {
                return SheetType.LONG_TEXT;
            }
        }

        // 默认跳过
        return SheetType.SKIP;
    }

    // ========== BVD 精准行扫描 ==========

    /**
     * 从已构建的 ExcelEntry 列表中提取企业名称。
     * 优先取占位符名恰好为"企业名称"的条目，其次取名含"企业"的首个条目。
     */
    private String extractCompanyNameFromEntries(List<ExcelEntry> entries) {
        for (ExcelEntry e : entries) {
            if (!e.isLongText() && "企业名称".equals(e.getPlaceholderName())) {
                return e.getValue();
            }
        }
        for (ExcelEntry e : entries) {
            if (!e.isLongText() && e.getPlaceholderName() != null
                    && e.getPlaceholderName().contains("企业")) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 扫描 BVD Excel，按企业名称定位目标行，提取财务数值条目。
     *
     * <p>策略：
     * <ol>
     *   <li>扫描第0个Sheet（BVD通常只有一个大表）的所有行</li>
     *   <li>找到包含 companyName 的行作为目标行</li>
     *   <li>从目标行提取所有长度 &ge; {@value #BVD_MIN_VALUE_LEN} 的数值单元格</li>
     *   <li>所有 BVD 条目标记为 uncertain（dataSource=bvd），不自动替换</li>
     * </ol>
     *
     * @param bvdExcelPath BVD Excel绝对路径
     * @param companyName  企业名称（用于定位目标行）
     * @return BVD ExcelEntry 列表（isLongText=false, dataSource=bvd, status 由引擎决策为 uncertain）
     */
    private List<ExcelEntry> buildBvdEntries(String bvdExcelPath, String companyName) {
        List<ExcelEntry> result = new ArrayList<>();
        List<Map<Integer, Object>> rows = readSheetByIndex(bvdExcelPath, 0);
        if (rows == null || rows.isEmpty()) return result;

        // 找第一行（表头行，用于生成占位符名称）
        Map<Integer, Object> headerRow = rows.isEmpty() ? Collections.emptyMap() : rows.get(0);

        // 定位包含企业名称的数据行（跳过表头，从第1行开始）
        for (int rowIdx = 1; rowIdx < rows.size(); rowIdx++) {
            Map<Integer, Object> row = rows.get(rowIdx);
            boolean isTargetRow = row.values().stream()
                    .anyMatch(v -> v != null && v.toString().contains(companyName));
            if (!isTargetRow) continue;

            log.info("[ReverseEngine-BVD] 找到企业 '{}' 对应行: rowIdx={}", companyName, rowIdx);

            // 提取该行所有满足最小长度的数值列
            for (Map.Entry<Integer, Object> cell : row.entrySet()) {
                int colIdx = cell.getKey();
                Object cellVal = cell.getValue();
                if (cellVal == null) continue;
                String strVal = cellVal.toString().trim();
                if (strVal.isBlank() || strVal.length() < BVD_MIN_VALUE_LEN) continue;
                // 只提取数字类型的值（整数/小数/千分位格式）
                if (!strVal.matches("-?[\\d,]+(\\.[\\d]+)?%?")) continue;

                // 用表头列名作为占位符名，找不到则用列索引
                String colName = "Col" + colIdx;
                Object headerVal = headerRow.get(colIdx);
                if (headerVal != null && !headerVal.toString().isBlank()) {
                    colName = sanitizePlaceholderName(headerVal.toString().trim());
                }

                ExcelEntry entry = new ExcelEntry();
                entry.setValue(strVal);
                entry.setPlaceholderName(sanitizePlaceholderName("BVD-" + colName));
                entry.setDataSource("bvd");
                entry.setSourceSheet("BVD");
                entry.setSourceField(toColumnLetter(colIdx) + (rowIdx + 1));
                entry.setLongText(false);
                result.add(entry);
            }
            // 只处理第一个匹配行，防止重名企业干扰
            break;
        }
        return result;
    }

    /** BVD 数值最小长度：低于此长度的数字不提取（避免年份等短数字干扰） */
    private static final int BVD_MIN_VALUE_LEN = 6;

    /**
     * 列下标转列字母（0→A, 1→B, 25→Z, 26→AA ...）
     */
    private String toColumnLetter(int colIndex) {
        StringBuilder sb = new StringBuilder();
        int n = colIndex + 1;
        while (n > 0) {
            n--;
            sb.insert(0, (char) ('A' + n % 26));
            n /= 26;
        }
        return sb.toString();
    }

    /**
     * 读取 Excel 所有 Sheet 名称（按顺序）
     */
    private List<String> readSheetNames(String filePath) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(filePath)) {
            org.apache.poi.ss.usermodel.Workbook wb =
                    new org.apache.poi.xssf.usermodel.XSSFWorkbook(fis);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                names.add(wb.getSheetName(i));
            }
            wb.close();
            return names;
        } catch (Exception e) {
            log.error("[ReverseEngine-New] 读取Sheet名称失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按 Sheet 索引读取所有行（无表头模式）
     */
    private List<Map<Integer, Object>> readSheetByIndex(String filePath, int sheetIndex) {
        try {
            return EasyExcel.read(filePath).sheet(sheetIndex).headRowNumber(0).doReadSync();
        } catch (Exception e) {
            log.warn("[ReverseEngine-New] 读取Sheet[{}]失败: {}", sheetIndex, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 清理占位符名称中的非法字符（{{、}}、【、】、空格等）
     */
    private String sanitizePlaceholderName(String name) {
        if (name == null) return "unknown";
        return name.trim()
                .replace("{{", "").replace("}}", "")
                .replace("【", "").replace("】", "")
                .replaceAll("[\\s]+", "_");
    }

    /**
     * 执行反向生成（旧签名兼容，内部委托新4参数方法，忽略 placeholders 参数）
     *
     * @deprecated 请使用新的4参数签名 {@link #reverse(String, String, String, String)}
     */
    @Deprecated
    public ReverseResult reverse(String historicalReportPath,
                                  String listExcelPath,
                                  String bvdExcelPath,
                                  List<SystemPlaceholder> placeholders,
                                  String outputPath) {
        log.info("[ReverseEngine] 旧5参数签名调用，委托新4参数引擎处理（placeholders参数已忽略）");
        return reverse(historicalReportPath, listExcelPath, bvdExcelPath, outputPath);
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

    // ========== 新引擎：段落替换（接收 ExcelEntry 列表） ==========

    /**
     * 遍历所有段落 Run，将匹配的实际值替换为占位符标记（新引擎）
     */
    private int replaceParagraphValuesNew(XWPFDocument doc, List<ExcelEntry> entries,
                                           List<PendingConfirmItem> pendingList,
                                           List<MatchedPlaceholder> matchedList) {
        int count = 0;
        String fullDocText = extractFullDocText(doc);
        int paraIndex = 0;
        for (XWPFParagraph paragraph : doc.getParagraphs()) {
            count += replaceInRunsNew(paragraph.getRuns(), entries,
                    pendingList, matchedList, "段落#" + paraIndex, paraIndex, -1, -1, -1, fullDocText);
            paraIndex++;
        }
        return count;
    }

    /**
     * 遍历所有表格单元格，将实际值替换为占位符（新引擎）
     */
    private int replaceTableCellValuesNew(XWPFDocument doc, List<ExcelEntry> entries,
                                           List<PendingConfirmItem> pendingList,
                                           List<MatchedPlaceholder> matchedList) {
        int count = 0;
        String fullDocText = extractFullDocText(doc);
        int tableIndex = 0;
        for (XWPFTable table : doc.getTables()) {
            int rowIndex = 0;
            for (XWPFTableRow row : table.getRows()) {
                int cellIndex = 0;
                for (XWPFTableCell cell : row.getTableCells()) {
                    String location = "表格#" + tableIndex + "[行" + rowIndex + ",列" + cellIndex + "]";
                    for (XWPFParagraph para : cell.getParagraphs()) {
                        count += replaceInRunsNew(para.getRuns(), entries,
                                pendingList, matchedList, location, -1, tableIndex, rowIndex, cellIndex, fullDocText);
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
     * 提取文档全文（用于统计值出现次数，支持中等长度值的安全性判断）
     */
    private String extractFullDocText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph p : doc.getParagraphs()) {
            sb.append(p.getText());
        }
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    sb.append(cell.getText());
                }
            }
        }
        return sb.toString();
    }

    /**
     * 统计 value 在文档全文中出现的次数（精确子串匹配）
     */
    private int countOccurrences(String fullText, String value) {
        if (fullText == null || value == null || value.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = fullText.indexOf(value, idx)) != -1) {
            count++;
            idx += value.length();
        }
        return count;
    }

    /**
     * 在 Run 列表中执行新引擎替换逻辑（分级安全替换策略）：
     * <ul>
     *   <li>isLongText=true：整段精确全字符串替换，无需边界保护</li>
     *   <li>值长度 &ge; {@value #MEDIUM_VALUE_THRESHOLD}：直接精确替换（企业全称等，误匹配风险低）</li>
     *   <li>值长度 {@value #SHORT_VALUE_THRESHOLD}~{@value #MEDIUM_VALUE_THRESHOLD}（中等长度）：
     *       检查在文档全文中出现次数，仅出现1次才自动替换，否则标 uncertain</li>
     *   <li>值长度 &lt; {@value #SHORT_VALUE_THRESHOLD} 或纯数字短值：全部标 uncertain，不自动替换</li>
     * </ul>
     */
    private int replaceInRunsNew(List<XWPFRun> runs, List<ExcelEntry> entries,
                                  List<PendingConfirmItem> pendingList, List<MatchedPlaceholder> matchedList,
                                  String location, int paragraphIndex, int tableIndex, int rowIndex, int cellIndex,
                                  String fullDocText) {
        int count = 0;
        for (XWPFRun run : runs) {
            String text = run.getText(0);
            if (text == null || text.isBlank()) continue;

            String originalText = text;
            boolean runModified = false;

            for (ExcelEntry entry : entries) {
                String value = entry.getValue();
                if (value == null || value.isBlank()) continue;
                String phMark = "{{" + entry.getPlaceholderName() + "}}";
                if (text.contains(phMark)) continue;
                if (!text.contains(value)) continue;

                if (entry.isLongText()) {
                    // 长文本：整段精确匹配，直接替换
                    String newText = text.replace(value, phMark);
                    if (!newText.equals(text)) {
                        text = newText;
                        runModified = true;
                        count++;
                        addMatchedRecord(matchedList, entry, value, originalText, location,
                                "confirmed", paragraphIndex, tableIndex, rowIndex, cellIndex);
                        log.debug("[ReverseEngine-New] 长文本替换: '{}...' -> {}", value.substring(0, Math.min(20, value.length())), phMark);
                    }
                } else {
                    // 数据表字段 / BVD字段：按值长度分级决定替换策略
                    // BVD 来源的数据一律标 uncertain，由用户确认
                    ReplaceDecision decision = "bvd".equals(entry.getDataSource())
                            ? ReplaceDecision.UNCERTAIN_SHORT
                            : decideReplaceStrategy(value, fullDocText);
                    if (decision == ReplaceDecision.AUTO) {
                        String newText = text.replace(value, phMark);
                        if (!newText.equals(text)) {
                            text = newText;
                            runModified = true;
                            count++;
                            addMatchedRecord(matchedList, entry, value, originalText, location,
                                    "confirmed", paragraphIndex, tableIndex, rowIndex, cellIndex);
                            log.debug("[ReverseEngine-New] 自动替换: '{}' -> {}", value, phMark);
                        }
                    } else {
                        // uncertain：记录到 matchedList，不修改文本
                        addMatchedRecord(matchedList, entry, value, originalText, location,
                                "uncertain", paragraphIndex, tableIndex, rowIndex, cellIndex);
                        log.debug("[ReverseEngine-New] uncertain（{}）: '{}'", decision, value);
                    }
                }
            }
            if (runModified) {
                run.setText(text, 0);
            }
        }
        return count;
    }

    /**
     * 替换决策枚举
     */
    private enum ReplaceDecision {
        /** 自动替换 */
        AUTO,
        /** 值过短，标记 uncertain，不替换 */
        UNCERTAIN_SHORT,
        /** 中等长度值，文档中出现多次，标记 uncertain */
        UNCERTAIN_AMBIGUOUS
    }

    /**
     * 根据值长度和文档出现次数决定替换策略：
     * <ul>
     *   <li>值长度 &lt; {@value #SHORT_VALUE_THRESHOLD}，或纯数字且长度 &le; 4 → uncertain（短值风险高）</li>
     *   <li>值长度 {@value #SHORT_VALUE_THRESHOLD}~{@value #MEDIUM_VALUE_THRESHOLD}-1（中等）：
     *       文档全文只出现1次 → 自动替换；否则 → uncertain</li>
     *   <li>值长度 &ge; {@value #MEDIUM_VALUE_THRESHOLD} → 直接自动替换</li>
     * </ul>
     */
    private ReplaceDecision decideReplaceStrategy(String value, String fullDocText) {
        int len = value.length();
        // 短值（含纯数字年份等）：一律 uncertain
        if (len < SHORT_VALUE_THRESHOLD || isShortPureNumber(value)) {
            return ReplaceDecision.UNCERTAIN_SHORT;
        }
        // 长值（≥ MEDIUM_VALUE_THRESHOLD）：直接自动替换
        if (len >= MEDIUM_VALUE_THRESHOLD) {
            return ReplaceDecision.AUTO;
        }
        // 中等长度（SHORT_VALUE_THRESHOLD ~ MEDIUM_VALUE_THRESHOLD-1）：检查出现次数
        int occurrences = countOccurrences(fullDocText, value);
        return (occurrences == 1) ? ReplaceDecision.AUTO : ReplaceDecision.UNCERTAIN_AMBIGUOUS;
    }

    /**
     * 判断是否为"短纯数字"（纯数字且长度 ≤ 4，如年度"23"、"2023"的后两位等）
     */
    private boolean isShortPureNumber(String value) {
        if (value == null || value.length() > 4) return false;
        return value.chars().allMatch(Character::isDigit);
    }

    /**
     * 检测哪些长文本条目在Word中未能匹配到
     *
     * @param longTextEntries 所有长文本条目
     * @param matchedList     实际匹配到的占位符列表
     * @return 未匹配的长文本条目列表
     */
    private List<ExcelEntry> detectUnmatchedLongText(List<ExcelEntry> longTextEntries,
                                                      List<MatchedPlaceholder> matchedList) {
        if (longTextEntries.isEmpty()) {
            return Collections.emptyList();
        }
        // 收集所有已匹配的长文本占位符名称
        Set<String> matchedPhNames = matchedList.stream()
                .map(MatchedPlaceholder::getPlaceholderName)
                .collect(Collectors.toSet());
        // 找出未匹配的条目
        return longTextEntries.stream()
                .filter(entry -> !matchedPhNames.contains(entry.getPlaceholderName()))
                .toList();
    }

    /**
     * 添加已匹配占位符记录到 matchedList
     */
    private void addMatchedRecord(List<MatchedPlaceholder> matchedList, ExcelEntry entry,
                                   String expectedValue, String actualText, String location,
                                   String status, int paragraphIndex, int tableIndex, int rowIndex, int cellIndex) {
        MatchedPlaceholder matched = new MatchedPlaceholder();
        matched.setPlaceholderName(entry.getPlaceholderName());
        matched.setExpectedValue(expectedValue);
        matched.setActualValue(actualText);
        matched.setLocation(location);
        matched.setStatus(status);
        matched.setDataSource(entry.getDataSource());
        matched.setSourceSheet(entry.getSourceSheet());
        matched.setSourceField(entry.getSourceField());

        // 模块信息：直接使用 sourceSheet 作为模块名
        matched.setModuleName(entry.getSourceSheet());
        matched.setModuleCode(sheetNameToCode(entry.getSourceSheet()));

        // 位置 JSON
        StringBuilder posJson = new StringBuilder("{");
        posJson.append("\"paragraphIndex\":").append(paragraphIndex).append(",");
        posJson.append("\"elementType\":\"").append(tableIndex >= 0 ? "table" : "paragraph").append("\"");
        if (tableIndex >= 0) {
            posJson.append(",\"tablePosition\":{");
            posJson.append("\"tableIndex\":").append(tableIndex).append(",");
            posJson.append("\"row\":").append(rowIndex).append(",");
            posJson.append("\"cell\":").append(cellIndex);
            posJson.append("}");
        }
        posJson.append("}");
        matched.setPositionJson(posJson.toString());

        matchedList.add(matched);
    }

    // ========== 旧引擎：段落替换（保留以供 applyConfirmedPlaceholders 引用） ==========



    /**
     * 遍历所有段落Run，将匹配的实际值替换为占位符标记
     */
    private int replaceParagraphValues(XWPFDocument doc, Map<String, String> valueToPlaceholder,
                                        Map<String, List<String>> valueToCandidates,
                                        List<PendingConfirmItem> pendingList,
                                        List<MatchedPlaceholder> matchedList) {
        int count = 0;
        int paraIndex = 0;
        for (XWPFParagraph paragraph : doc.getParagraphs()) {
            count += replaceInRuns(paragraph.getRuns(), valueToPlaceholder, valueToCandidates,
                    pendingList, matchedList, "段落#" + paraIndex, paraIndex, -1, -1);
            paraIndex++;
        }
        return count;
    }

    /**
     * 遍历所有表格单元格，将实际值替换为占位符
     */
    private int replaceTableCellValues(XWPFDocument doc, Map<String, String> valueToPlaceholder,
                                        Map<String, List<String>> valueToCandidates,
                                        List<PendingConfirmItem> pendingList,
                                        List<MatchedPlaceholder> matchedList) {
        int count = 0;
        int tableIndex = 0;
        for (XWPFTable table : doc.getTables()) {
            int rowIndex = 0;
            for (XWPFTableRow row : table.getRows()) {
                int cellIndex = 0;
                for (XWPFTableCell cell : row.getTableCells()) {
                    String location = "表格#" + tableIndex + "[行" + rowIndex + ",列" + cellIndex + "]";
                    int runIdx = 0;
                    for (XWPFParagraph para : cell.getParagraphs()) {
                        for (XWPFRun run : para.getRuns()) {
                            int runMatchCount = replaceInRun(run, valueToPlaceholder, valueToCandidates,
                                    pendingList, matchedList, location, -1, runIdx, tableIndex, rowIndex, cellIndex);
                            count += runMatchCount;
                            if (runMatchCount > 0) runIdx++;
                        }
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
                               List<PendingConfirmItem> pendingList, List<MatchedPlaceholder> matchedList,
                               String location, int paragraphIndex, int runIndex, int tableIndex) {
        int count = 0;
        int runIdx = runIndex >= 0 ? runIndex : 0;
        for (XWPFRun run : runs) {
            int matched = replaceInRun(run, valueToPlaceholder, valueToCandidates,
                    pendingList, matchedList, location, paragraphIndex, runIdx, tableIndex, -1, -1);
            count += matched;
            if (matched > 0) runIdx++;
        }
        return count;
    }

    /**
     * 在单个 Run 中搜索并替换值为占位符
     */
    private int replaceInRun(XWPFRun run, Map<String, String> valueToPlaceholder,
                              Map<String, List<String>> valueToCandidates,
                              List<PendingConfirmItem> pendingList, List<MatchedPlaceholder> matchedList,
                              String location, int paragraphIndex, int runIndex, int tableIndex, int rowIndex, int cellIndex) {
        String text = run.getText(0);
        if (text == null || text.isBlank()) return 0;

        String normalizedText = normalizeNumber(text);
        int count = 0;

        // 遍历所有映射，寻找包含该值的替换
        for (Map.Entry<String, String> entry : valueToPlaceholder.entrySet()) {
            String value = entry.getKey();
            String phName = entry.getValue();
            String phMark = "{{" + phName + "}}";

            // 跳过过短的值（避免误匹配，如年份"2023"可能大量出现）
            if (value.length() < 2) continue;

            boolean isMatched = false;
            boolean hasConflict = false;
            String matchType = "";

            // 优先：原始文本精确包含
            if (text.contains(value)) {
                isMatched = true;
                matchType = "exact";
                // 检查是否有冲突
                List<String> candidates = valueToCandidates.getOrDefault(normalizeNumber(value), List.of());
                if (candidates.size() > 1) {
                    hasConflict = true;
                    // 冲突：加入待确认列表，不自动替换
                    addPendingIfNotExists(pendingList, phName, value, location,
                            "值'" + value + "'对应多个占位符：" + candidates);
                } else {
                    // 无冲突：直接替换，保留 Run 样式
                    text = text.replace(value, phMark);
                }
            } else if (normalizedText.contains(normalizeNumber(value)) && !value.equals(normalizedText)) {
                // 归一化后匹配（如千分位格式）
                isMatched = true;
                matchType = "normalized";
                List<String> candidates = valueToCandidates.getOrDefault(normalizeNumber(value), List.of());
                if (candidates.size() > 1) {
                    hasConflict = true;
                    addPendingIfNotExists(pendingList, phName, value, location,
                            "归一化匹配，值'" + value + "'对应多个占位符");
                } else {
                    String newText = replaceNormalizedInText(text, value, phMark);
                    if (newText != null) {
                        text = newText;
                    } else {
                        text = run.getText(0); // 替换失败回退
                        isMatched = false;
                    }
                }
            }

            // 记录匹配到的占位符（无论是否冲突）
            if (isMatched) {
                count++;
                // 添加到已匹配列表
                MatchedPlaceholder matched = new MatchedPlaceholder();
                matched.setPlaceholderName(phName);
                matched.setExpectedValue(value);
                matched.setActualValue(run.getText(0)); // 原始文本
                matched.setLocation(location);
                matched.setStatus(hasConflict ? "uncertain" : "confirmed");
                String sheetName = extractSheetName(phName);
                matched.setModuleName(sheetName);
                matched.setModuleCode(sheetNameToCode(sheetName));

                // 构建位置JSON
                StringBuilder posJson = new StringBuilder();
                posJson.append("{");
                posJson.append("\"paragraphIndex\":").append(paragraphIndex).append(",");
                posJson.append("\"runIndex\":").append(runIndex).append(",");
                posJson.append("\"elementType\":\"").append(tableIndex >= 0 ? "table" : "paragraph").append("\"");
                if (tableIndex >= 0) {
                    posJson.append(",\"tablePosition\":{");
                    posJson.append("\"tableIndex\":").append(tableIndex).append(",");
                    posJson.append("\"row\":").append(rowIndex).append(",");
                    posJson.append("\"cell\":").append(cellIndex);
                    posJson.append("}");
                }
                posJson.append("}");
                matched.setPositionJson(posJson.toString());

                matchedList.add(matched);

                if (!hasConflict) {
                    log.debug("[ReverseEngine] 占位符匹配成功: {} -> {}", value, phName);
                }
            }
        }

        // 写回 Run（仅修改文本，不改变字体、字号等样式）
        if (count > 0) {
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
     * 将文本中与 value（可能含千分位）等价的数字子串替换为占位符。
     *
     * <p>修复说明：原实现用 {@code text.replace(",", "")} 会删除文本中所有逗号（包括中文标点逗号），
     * 导致文档内容被物理破坏。新实现构造精确的数字正则，只替换匹配的数字字符串本身。
     *
     * @param text        原始文本（含逗号等标点）
     * @param value       Excel中的原始值（可能含千分位逗号，如 "1,234,567"）
     * @param replacement 替换目标字符串（占位符标记）
     * @return 替换后的文本；若无法匹配则返回 null
     */
    private String replaceNormalizedInText(String text, String value, String replacement) {
        String normalizedValue = normalizeNumber(value);

        // 构造能匹配文本中千分位数字的正则（如 normalizedValue="1234567" 匹配 "1,234,567" 或 "1234567"）
        String numRegex = buildNumberRegex(normalizedValue);
        if (numRegex == null) {
            // 无法构造数字正则（非纯数字）：回退到原始直接包含检查
            if (text.contains(value)) {
                return text.replace(value, Matcher.quoteReplacement(replacement));
            }
            return null;
        }

        Pattern p = Pattern.compile(numRegex);
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.replaceAll(Matcher.quoteReplacement(replacement));
        }
        return null;
    }

    /**
     * 根据归一化后的纯数字字符串，构造能同时匹配千分位格式和无格式的正则。
     *
     * <p>例如 "1234567" → {@code "1,234,567|1234567"}，封装在词边界之间。
     * 如果 normalizedValue 不是纯数字（含小数点也算），返回 null。
     *
     * @param normalizedValue 归一化后的值（如 "1234567" 或 "123.45"）
     * @return 正则字符串；非数字则返回 null
     */
    private String buildNumberRegex(String normalizedValue) {
        if (normalizedValue == null || normalizedValue.isBlank()) return null;
        // 只处理纯数字（整数或小数）
        if (!normalizedValue.matches("-?\\d+(\\.\\d+)?")) return null;

        // 构造千分位版本，用于匹配文本中带逗号的数字
        String withCommas = insertThousandSeparators(normalizedValue);
        String escaped1 = Pattern.quote(withCommas);
        String escaped2 = Pattern.quote(normalizedValue);

        // 两种格式都能匹配，优先匹配带千分位的版本
        if (withCommas.equals(normalizedValue)) {
            return escaped1;
        }
        return escaped1 + "|" + escaped2;
    }

    /**
     * 将纯数字字符串插入千分位逗号（整数部分每3位一组，小数部分不变）。
     * 例如 "1234567.89" → "1,234,567.89"
     */
    private String insertThousandSeparators(String number) {
        if (number == null) return number;
        int dotIdx = number.indexOf('.');
        String intPart = dotIdx >= 0 ? number.substring(0, dotIdx) : number;
        String decPart = dotIdx >= 0 ? number.substring(dotIdx) : "";
        // 处理负号
        String sign = "";
        if (intPart.startsWith("-")) {
            sign = "-";
            intPart = intPart.substring(1);
        }
        StringBuilder sb = new StringBuilder();
        int len = intPart.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) sb.append(',');
            sb.append(intPart.charAt(i));
        }
        return sign + sb + decPart;
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
            // 设置模块信息
            setModuleInfo(item, phName);
            pendingList.add(item);
        }
    }

    /**
     * 从占位符名称提取Sheet名并设置模块信息
     * <p>
     * 占位符名称格式：模板名-Sheet名-单元格地址，如 "清单模板-数据表-B3"
     * Sheet名 → 模块code转换规则：trim() + 空格/横杠转下划线
     * </p>
     */
    private void setModuleInfo(PendingConfirmItem item, String placeholderName) {
        // 解析占位符名称获取Sheet名
        String sheetName = extractSheetName(placeholderName);
        item.setModuleName(sheetName);
        item.setModuleCode(sheetNameToCode(sheetName));
    }

    /**
     * 从占位符名称提取Sheet名
     * 格式：数据源-Sheet名+单元格地址，如 "清单模板-数据表B3"
     * 注意：Sheet名是除去数据源前缀和单元格后缀的中间部分
     * 与 SystemTemplateParser 的解析逻辑保持一致
     */
    public static String extractSheetName(String placeholderName) {
        if (placeholderName == null || placeholderName.isBlank()) {
            return "默认模块";
        }
        
        // 使用与 SystemTemplateParser 相同的正则解析
        // 格式：数据源-Sheet名+单元格，如 清单模板-数据表B4
        Pattern pattern = Pattern.compile("^([^-]+)-(.*?)([A-Za-z]+\\d+)$");
        Matcher m = pattern.matcher(placeholderName);
        if (m.matches()) {
            return m.group(2).trim();  // Sheet名，如 "数据表"
        }
        
        // 无法解析时，退回到简单分割
        String[] parts = placeholderName.split("-");
        if (parts.length >= 2) {
            return parts[1].trim();
        }
        return "默认模块";
    }

    /**
     * Sheet名转换为模块code
     * 规则：trim() + 空格/横杠转下划线
     * 示例："基本 信息" → "基本_信息"，"基本--信息" → "基本_信息"
     */
    public static String sheetNameToCode(String sheetName) {
        if (sheetName == null || sheetName.isBlank()) {
            return "default";
        }
        String code = sheetName.trim()
                .replaceAll("[\\s\\-]+", "_")  // 连续空格/横杠 → 单个下划线
                .replaceAll("_+", "_")           // 多个下划线 → 单个下划线
                .toLowerCase();
        return code.isEmpty() ? "default" : code;
    }

    /**
     * 从占位符列表提取模块信息
     * <p>
     * 用于反向生成时创建模块记录
     * </p>
     *
     * @param placeholderNames 占位符名称列表
     * @return 去重后的模块信息列表
     */
    public static List<ModuleInfo> extractModules(List<String> placeholderNames) {
        Map<String, ModuleInfo> moduleMap = new LinkedHashMap<>();
        int sort = 0;

        for (String phName : placeholderNames) {
            String sheetName = extractSheetName(phName);
            String code = sheetNameToCode(sheetName);

            // 相同code的模块只保留一个
            if (!moduleMap.containsKey(code)) {
                ModuleInfo module = new ModuleInfo();
                module.setCode(code);
                module.setName(sheetName);
                module.setSort(sort++);
                moduleMap.put(code, module);
            }
        }

        return new ArrayList<>(moduleMap.values());
    }
}
