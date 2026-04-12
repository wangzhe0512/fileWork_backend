package com.fileproc.report.service;

import com.alibaba.excel.EasyExcel;
import com.fileproc.common.BizException;
import com.fileproc.datafile.entity.DataFile;
import com.fileproc.registry.service.PlaceholderRegistryService;
import com.fileproc.template.entity.Placeholder;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

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
     * 占位符注册表服务（可选注入，用于获取企业级 column_defs 配置）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlaceholderRegistryService placeholderRegistryService;

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
                log.info("[ReportEngine] 加载数据文件: type={}, path={}", df.getType(), df.getFilePath());
            } else {
                log.warn("[ReportEngine] 数据文件不存在，跳过: id={}, path={}", df.getId(), df.getFilePath());
            }
        }
        log.info("[ReportEngine] 数据文件索引构建完成，共 {} 个类型: {}", 
                dataFileByType.size(), dataFileByType.keySet());

        // 按 (type, sourceSheet) 缓存读取结果，避免重复 IO
        // key: "type:sheetIndex"
        Map<String, List<Map<Integer, Object>>> excelDataCache = new HashMap<>();

        // 构建文本占位符值 Map
        Map<String, String> textValues = new LinkedHashMap<>();
        // 构建表格占位符数据 Map：占位符名 -> 二维列表（含表头行）
        Map<String, List<List<Object>>> tableValues = new LinkedHashMap<>();
        // 构建行模板占位符数据 Map：占位符名 -> 行列表（每行为字段名->值的 Map）
        // 用于 TABLE_ROW_TEMPLATE 类型，fillTableByRowTemplateMapped 按字段名填值
        Map<String, List<Map<String, Object>>> rowTemplateValues = new LinkedHashMap<>();

        // 行模板类型 Sheet 名集合已不再使用，改为在处理时直接通过注册表 columnDefs 判断是否为行模板类型
        // 保留此变量仅为兼容，不再用于判断行模板路径

        log.info("[ReportEngine] 开始处理 {} 个占位符", placeholders.size());
        for (Placeholder ph : placeholders) {
            String dataSource = ph.getDataSource(); // list / bvd
            DataFile df = dataFileByType.get(dataSource);
            if (df == null) {
                log.warn("[ReportEngine] 占位符 '{}' 对应的数据文件未找到，dataSource='{}'，跳过", 
                        ph.getName(), dataSource);
                continue;
            }

            // 解析 sourceSheet（可为数字索引或 sheet 名，默认 0）
            String sheetKey = dataSource + ":" + (ph.getSourceSheet() != null ? ph.getSourceSheet() : "0");
            log.info("[ReportEngine] 处理占位符 '{}'，类型='{}'，数据源='{}'，sheet='{}'，缓存键='{}'", 
                    ph.getName(), ph.getType(), dataSource, ph.getSourceSheet(), sheetKey);
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
                if ("bvd".equals(dataSource) && "SummaryYear".equals(ph.getSourceSheet())) {
                    // BVD SummaryYear 可比公司列表：动态行模板（按 column_defs 提取数据）
                    List<String> colDefs = getColumnDefsForPlaceholder(ph.getName(), ph.getCompanyId());
                    List<Map<String, Object>> rowData = extractSummaryYearRowData(rows, colDefs);
                    rowTemplateValues.put(ph.getName(), rowData);
                    log.info("[ReportEngine] BVD SummaryYear '{}' 行模板数据提取完成：{} 行，columnDefs={}",
                            ph.getName(), rowData.size(), colDefs);
                } else if (hasColumnDefs(ph.getName(), ph.getCompanyId())) {
                    // 行模板克隆类型：注册表有 columnDefs 定义（TABLE_ROW_TEMPLATE），输出 List<Map<字段名,値>>
                    List<Map<String, Object>> rowData = extractRowTemplateData(rows, ph.getSourceSheet(), ph.getName());
                    rowTemplateValues.put(ph.getName(), rowData);
                } else {
                    List<List<Object>> tableData = extractTableData(rows, ph.getSourceSheet());
                    tableValues.put(ph.getName(), tableData);
                }
            } else if ("TABLE_CLEAR_FULL".equals(ph.getType())) {
                // TABLE_CLEAR_FULL：整张表全部清空，从 sourceSheet 逐行展开数据
                // 如 PL 财务状况表：从 PL Sheet 读取数据，保留三列（项目/公式/金额）
                List<List<Object>> tableData = extractTableData(rows, ph.getSourceSheet());
                tableValues.put(ph.getName(), tableData);
                log.info("[ReportEngine] TABLE_CLEAR_FULL '{}' 数据提取完成：{} 行", ph.getName(), tableData.size() - 1);
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
            // 先替换所有文本占位符（不改变表格行结构）
            replaceTextInTables(doc, textValues);
            // 替换页眉/页脚中的文本占位符
            replaceHeaderFooterPlaceholders(doc, textValues);
            // 最后做表格行模板操作（修改 CTTbl 结构，必须在所有文本替换完成后执行）
            replaceTablePlaceholders(doc, tableValues, rowTemplateValues);

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
            log.info("[ReportEngine] 读取文件 {} sheet='{}' 共 {} 行", filePath, sourceSheet, result.size());
            // 打印前15行的第一列内容，用于调试
            for (int i = 0; i < Math.min(15, result.size()); i++) {
                Map<Integer, Object> row = result.get(i);
                Object firstCol = row != null ? row.get(0) : null;
                log.info("[ReportEngine] 行[{}] 第一列='{}'", i, firstCol != null ? firstCol.toString() : "(null)");
            }
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

        // 最优先：D列关键词动态定位格式，如 "D_KEYWORD:MIN"
        // 扫描 D 列（index=3）找含关键词的行，取该行 E 列（index=4）的值
        if (field.startsWith("D_KEYWORD:")) {
            String keyword = field.substring("D_KEYWORD:".length()).trim().toUpperCase();
            for (Map<Integer, Object> row : rows) {
                Object dCell = row.get(3); // D列，index=3
                if (dCell != null && dCell.toString().toUpperCase().contains(keyword)) {
                    Object eCell = row.get(4); // E列，index=4
                    if (eCell != null && !eCell.toString().isBlank()) {
                        return eCell.toString().trim();
                    }
                }
            }
            log.warn("[ReportEngine] D列未找到含关键词 '{}' 的行（sheet={}）", keyword, sheetName);
            return null;
        }

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

        // PL Sheet 专用截取逻辑：动态识别表头和数据行
        if ("PL".equals(sheetName) || "PL含特殊因素调整".equals(sheetName)) {
            List<List<Object>> result = new ArrayList<>();
            
            // 动态查找表头行（第一列包含"项目"或"项目名称"的行）
            int headerIndex = -1;
            for (int i = 0; i < Math.min(5, rows.size()); i++) {
                Map<Integer, Object> row = rows.get(i);
                if (row == null || row.isEmpty()) continue;
                Object firstCol = row.get(0);
                String firstColStr = firstCol != null ? firstCol.toString().trim() : "";
                if ("项目".equals(firstColStr) || "项目名称".equals(firstColStr)) {
                    headerIndex = i;
                    break;
                }
            }
            
            // 添加表头行
            if (headerIndex >= 0) {
                Map<Integer, Object> headerRow = rows.get(headerIndex);
                List<Object> headerLine = new ArrayList<>();
                for (int col = 0; col < 3; col++) {
                    Object val = headerRow.getOrDefault(col, "");
                    headerLine.add(val != null ? val.toString() : "");
                }
                result.add(headerLine);
                log.info("[ReportEngine] PL Sheet '{}' 找到并添加表头行(index={}): {}", sheetName, headerIndex, headerLine);
            } else {
                log.warn("[ReportEngine] PL Sheet '{}' 未找到表头行，使用默认表头", sheetName);
                result.add(java.util.Arrays.asList("项目", "公式", "金额"));
            }
            
            // 从表头行的下一行开始查找数据行（最多9行数据）
            int dataStart = headerIndex >= 0 ? headerIndex + 1 : 0;
            int dataCount = 0;
            for (int i = dataStart; i < rows.size() && dataCount < 9; i++) {
                Map<Integer, Object> row = rows.get(i);
                // 跳过空行（行数据为空或第一列没有值）
                Object firstCol = row != null ? row.get(0) : null;
                String firstColStr = firstCol != null ? firstCol.toString().trim() : "";
                if (row == null || row.isEmpty() || firstColStr.isEmpty()) {
                    log.debug("[ReportEngine] PL Sheet 跳过空行 i={}，第一列值='{}'", i, firstColStr);
                    continue;
                }
                // 检查是否是关联销售明细等额外内容（通过关键词判断）
                if (firstColStr.contains("根据访谈") || firstColStr.contains("关联销售")) {
                    log.info("[ReportEngine] PL Sheet 遇到非数据行，停止截取: '{}'", firstColStr);
                    break;
                }
                // 只保留前3列（项目/公式/金额），忽略多余列
                List<Object> line = new ArrayList<>();
                for (int col = 0; col < 3; col++) {
                    Object val = row.getOrDefault(col, "");
                    // 处理数字格式：将 BigDecimal/Double 格式化为标准数字字符串
                    if (val instanceof java.math.BigDecimal) {
                        val = formatNumber((java.math.BigDecimal) val);
                    } else if (val instanceof Double || val instanceof Float) {
                        val = formatNumber(((Number) val).doubleValue());
                    } else if (val instanceof String) {
                        // 处理会计格式的负数：(2,200.00) -> -2,200.00
                        val = convertAccountingNumber((String) val);
                    }
                    line.add(val);
                }
                log.debug("[ReportEngine] PL Sheet 添加数据行 i={}，第一列='{}'", i, firstColStr);
                result.add(line);
                dataCount++;
            }
            log.info("[ReportEngine] PL Sheet '{}' 截取完成，共 {} 行数据（含表头）", sheetName, result.size());
            return result;
        }

        // 供应商清单 / 客户清单 Sheet 由 extractRowTemplateData() 专门处理（行模板克隆按字段名方案）
        // 此处不再处理，直接走下方通用逻辑（历史兼容：如有遗留旧调用，仍返回全列数据）

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
     * 供应商清单 / 客户清单 / 劳务交易表 Sheet 专用行模板数据提取（按字段名方案）。
     * <p>
     * Excel Sheet 结构：
     * <ul>
     *   <li>供应商/客户清单：行0=公司名, 行1=空, 行2=附件标题, 行3=说明, 行4=大表头，行5=子表头，行6=分组标题，行7起=实际数据</li>
     *   <li>劳务交易表：行5=表头，行8起=实际数据（行次1-15为劳务收入，行次16-30为劳务支出）</li>
     * </ul>
     * 输出：每行为字段名->值的 Map，字段名来自子表头动态解析，不硬编码列索引。
     * </p>
     *
     * @param rows              EasyExcel 读取的行数据（行索引从0开始）
     * @param sheetName         Sheet 名（用于路由和日志）
     * @param placeholderName   占位符名称（劳务交易表专用：用于区分收入/支出段）
     * @return 数据行列表，每行为 Map&lt;字段名, 值&gt;
     */
    private List<Map<String, Object>> extractRowTemplateData(List<Map<Integer, Object>> rows, String sheetName, String placeholderName) {
        // ===== 劳务交易表专用路径 =====
        if ("6 劳务交易表".equals(sheetName)) {
            return extractLaborServiceData(rows, placeholderName);
        }

        // ===== 关联公司信息专用路径 =====
        if ("2 关联公司信息".equals(sheetName)) {
            return extractRelatedCompanyData(rows);
        }

        // ===== 组织结构及管理架构专用路径 =====
        if ("1 组织结构及管理架构".equals(sheetName)) {
            return extractOrgStructureData(rows);
        }

        // ===== 关联方个人信息专用路径 =====
        if ("关联方个人信息".equals(sheetName)) {
            return extractRelatedPersonData(rows);
        }

        // ===== 关联关系变化情况专用路径 =====
        if ("关联关系变化情况".equals(sheetName)) {
            return extractRelationChangeData(rows);
        }

        // ===== 关联交易汇总表专用路径 =====
        if ("关联交易汇总表".equals(sheetName)) {
            return extractRelatedTransactionSummaryData(rows);
        }

        // ===== 劳务成本归集专用路径 =====
        if ("劳务成本归集".equals(sheetName)) {
            return extractLaborCostData(rows);
        }

        // ===== 资金融通专用路径 =====
        if ("资金融通".equals(sheetName)) {
            return extractFundTransferData(rows);
        }

        // ===== 公司间资金融通专用路径 =====
        if ("公司间资金融通交易总结".equals(sheetName)) {
            return extractInterCompanyFundData(rows);
        }

        // ===== 有形资产信息专用路径 =====
        if ("有形资产信息".equals(sheetName)) {
            return extractTangibleAssetData(rows);
        }

        // ===== 功能风险汇总表专用路径 =====
        if ("功能风险汇总表".equals(sheetName)) {
            return extractFuncRiskData(rows);
        }

        // ===== 主要产品专用路径 =====
        if ("主要产品".equals(sheetName)) {
            return extractMainProductData(rows);
        }

        // ===== 供应商/客户清单原有逻辑 =====
        if (rows.size() < 9) {
            log.warn("[ReportEngine-RowTpl] Sheet '{}' 行数不足，无法提取行模板数据", sheetName);
            return Collections.emptyList();
        }

        // 1. 合并解析行5（Excel行5，含"名称"字段）和行6（Excel行6，含"金额/比例"字段）构建 colIdx->字段名 Map
        //    Excel 该类 Sheet 结构：行5=项目/编号/供应商名称/产品类型/..., 行6=金额(人民币)/占采购比例/...（行5的E列拆分子标题）
        Map<Integer, String> colNameMap = new LinkedHashMap<>();
        // 先读行5（0-based index=4），优先级高
        Map<Integer, Object> headerRow4 = rows.get(4);
        for (Map.Entry<Integer, Object> entry : headerRow4.entrySet()) {
            String colName = entry.getValue() != null ? entry.getValue().toString().trim() : "";
            if (!colName.isEmpty()) {
                colNameMap.put(entry.getKey(), colName);
            }
        }
        // 再读行6（0-based index=5），补充行5未覆盖的列（如金额子标题列）
        Map<Integer, Object> headerRow5 = rows.get(5);
        for (Map.Entry<Integer, Object> entry : headerRow5.entrySet()) {
            String colName = entry.getValue() != null ? entry.getValue().toString().trim() : "";
            if (!colName.isEmpty()) {
                colNameMap.putIfAbsent(entry.getKey(), colName); // 行5已有的列不覆盖
            }
        }
        log.debug("[ReportEngine-RowTpl] Sheet '{}' 子表头解析（合并行5+行6）：{}", sheetName, colNameMap);

        // 找到"名称"列（供应商名称/客户名称等）的列索引：通常在col2，但通过子表头名称动态确定
        // 名称列：colNameMap 中包含"名称"关键词的列
        Integer nameColIdx = colNameMap.entrySet().stream()
                .filter(e -> e.getValue().contains("名称"))
                .map(Map.Entry::getKey)
                .findFirst().orElse(2); // fallback col2

        // 金额列：colNameMap 中包含"金额"关键词的列
        Integer amountColIdx = colNameMap.entrySet().stream()
                .filter(e -> e.getValue().contains("金额"))
                .map(Map.Entry::getKey)
                .findFirst().orElse(4); // fallback col4

        // 占比列：colNameMap 中包含"比例"或"占比"或"比重"关键词的列
        Integer ratioColIdx = colNameMap.entrySet().stream()
                .filter(e -> e.getValue().contains("比例") || e.getValue().contains("占比") || e.getValue().contains("比重"))
                .map(Map.Entry::getKey)
                .findFirst().orElse(5); // fallback col5

        log.debug("[ReportEngine-RowTpl] Sheet '{}' 关键列定位：名称col={}, 金额col={}, 占比col={}",
                sheetName, nameColIdx, amountColIdx, ratioColIdx);

        // 2. 从行9（0-based i=8）起扫描数据，跳过行8的"关联供应商/关联客户"大类标题行
        //    按原有分组/明细/小计逻辑，输出字段名->值的 Map
        List<Map<String, Object>> result = new ArrayList<>();
        final int finalNameColIdx = nameColIdx;
        final int finalAmountColIdx = amountColIdx;
        final int finalRatioColIdx = ratioColIdx;

        for (int i = 8; i < rows.size(); i++) {
            Map<Integer, Object> row = rows.get(i);
            Object col0 = row.get(0);
            Object col1 = row.get(1);
            Object colName = row.get(finalNameColIdx);

            String col0Str = col0 != null ? col0.toString().trim() : "";
            String col1Str = col1 != null ? col1.toString().trim() : "";
            String colNameStr = colName != null ? colName.toString().trim() : "";

            // 分组标题行：col0非空，col1为空
            if (!col0Str.isEmpty() && col1Str.isEmpty() && colNameStr.isEmpty()) {
                if (col0Str.contains("小计") || col0Str.contains("合计") || col0Str.contains("总计")) {
                    // 小计/总计行：col0存名称，取金额和占比
                    Map<String, Object> rowMap = buildRowMap(col0Str,
                            toPlainString(row.get(finalAmountColIdx)),
                            toPlainString(row.get(finalRatioColIdx)),
                            colNameMap, finalNameColIdx, finalAmountColIdx, finalRatioColIdx,
                            "subtotal");
                    result.add(rowMap);
                } else {
                    // 纯分组标题行：只有名称
                    Map<String, Object> rowMap = buildRowMap(col0Str, "", "",
                            colNameMap, finalNameColIdx, finalAmountColIdx, finalRatioColIdx,
                            "group");
                    result.add(rowMap);
                }
                continue;
            }

            // col1含"小计"/"合计"/"总计"
            if (!col1Str.isEmpty() && (col1Str.contains("小计") || col1Str.contains("合计") || col1Str.contains("总计"))) {
                Map<String, Object> rowMap = buildRowMap(col1Str,
                        toPlainString(row.get(finalAmountColIdx)),
                        toPlainString(row.get(finalRatioColIdx)),
                        colNameMap, finalNameColIdx, finalAmountColIdx, finalRatioColIdx,
                        "subtotal");
                result.add(rowMap);
                continue;
            }

            // 明细行：col1为纯数字编号 或 "其他"（兼容旧格式 Excel）
            boolean isSeqNum = col1Str.matches("^\\d+$");
            boolean isOther = "其他".equals(col1Str);
            if (isSeqNum || isOther) {
                String name = isOther ? "其他" : colNameStr;
                if (name.isEmpty() && !isOther) continue; // 名称为空跳过
                if (!name.isEmpty()) {
                    Map<String, Object> rowMap = buildRowMap(name,
                            toPlainString(row.get(finalAmountColIdx)),
                            toPlainString(row.get(finalRatioColIdx)),
                            colNameMap, finalNameColIdx, finalAmountColIdx, finalRatioColIdx,
                            "data");
                    result.add(rowMap);
                }
                continue;
            }

            // 兜底 data 行：col0 为空 且 名称列非空 且 不含小计类词
            // 覆盖 Excel 中 col1 直接为供应商/客户名称（非数字序号）的情况
            if (col0Str.isEmpty() && !colNameStr.isEmpty()
                    && !colNameStr.contains("小计") && !colNameStr.contains("合计") && !colNameStr.contains("总计")) {
                Map<String, Object> rowMap = buildRowMap(colNameStr,
                        toPlainString(row.get(finalAmountColIdx)),
                        toPlainString(row.get(finalRatioColIdx)),
                        colNameMap, finalNameColIdx, finalAmountColIdx, finalRatioColIdx,
                        "data");
                result.add(rowMap);
                continue;
            }

            // col0含"非关联"：关联区域结束，停止扫描
            if (col0Str.contains("非关联")) {
                break;
            }
        }

        log.debug("[ReportEngine-RowTpl] Sheet '{}' 行模板数据提取完成，共 {} 行", sheetName, result.size());
        return result;
    }

    /**
     * 组织结构及管理架构表（1 组织结构及管理架构）专用行模板数据提取。
     * <p>
     * Excel 结构（0-based行索引）：
     * <ul>
     *   <li>行0-4（index=0-4）：公司名/说明文字/空行/说明/提示（跳过）</li>
     *   <li>行5（index=5）：表头行（主要部门、人数、主要职责范围、汇报对象、汇报对象主要办公所在地）</li>
     *   <li>行6（index=6）起：数据行，col0（主要部门列）字符串非空则为有效数据行</li>
     * </ul>
     * 注意：col0 为部门名称（字符串），与关联公司信息的行次（数字）不同。
     * </p>
     *
     * @param rows EasyExcel 读取的行数据（行索引从0开始）
     * @return 数据行列表，每行为 Map&lt;字段名, 值&gt;
     */
    private List<Map<String, Object>> extractOrgStructureData(List<Map<Integer, Object>> rows) {
        if (rows.size() < 5) {
            log.warn("[ReportEngine-RowTpl] Sheet '1 组织结构及管理架构' 行数不足，无法提取行模板数据");
            return Collections.emptyList();
        }

        // 1. 解析行3（0-based index=3）为表头行，构建 colIdx→规范字段名 Map
        //    EasyExcel 跳过完全空行后，实际行序为:
        //    index=0 公司名, index=1 附件标题, index=2 说明文字, index=3 大表头, index=4 子表头, index=5+ 数据行
        //    列名可能带追加说明，如“人数（截至【年度】年12月31日）”，需截取括号/换行前的部分
        Map<Integer, String> colNameMap = new LinkedHashMap<>();
        Map<Integer, Object> headerRow = rows.get(3);
        for (Map.Entry<Integer, Object> entry : headerRow.entrySet()) {
            String colName = entry.getValue() != null ? entry.getValue().toString().trim() : "";
            if (!colName.isEmpty()) {
                // 截取第一个全角括号、半角括号或换行前的部分
                int cutAt = colName.length();
                int p1 = colName.indexOf('（'); // 全角左括号
                int p2 = colName.indexOf('(');   // 半角左括号
                int p3 = colName.indexOf('\n');  // 换行
                if (p1 >= 0 && p1 < cutAt) cutAt = p1;
                if (p2 >= 0 && p2 < cutAt) cutAt = p2;
                if (p3 >= 0 && p3 < cutAt) cutAt = p3;
                String normalized = colName.substring(0, cutAt).trim();
                colNameMap.put(entry.getKey(), normalized.isEmpty() ? colName : normalized);
            }
        }
        log.debug("[ReportEngine-RowTpl] Sheet '1 组织结构及管理架构' 表头解析（规范化后）：{}", colNameMap);

        // 2. 从行5（0-based index=5）起扫描数据行
        //    index=4 为子表头行（汇报对象 / 汇报对象主要办公所在地），需跳过
        //    col0（主要部门列）非空且非"总计"则为有效数据行
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 5; i < rows.size(); i++) {
            Map<Integer, Object> row = rows.get(i);
            Object col0 = row.get(0);
            if (col0 == null || col0.toString().trim().isEmpty()) {
                continue; // 部门名称为空，跳过
            }
            String col0Str = col0.toString().trim();
            // 如果人数列（通常是col1）为空，说明是表格外部的提示行，跳过
            Object col1 = row.get(1);
            if (col1 == null || col1.toString().trim().isEmpty()) {
                // 但如果是"总计"行，人数列应该有值，所以这里跳过的是非数据行
                // 继续检查是否是总计行（可能人数列在别的位置）
                boolean isTotal = col0Str.contains("总计") || col0Str.contains("合计") || col0Str.contains("小计");
                if (!isTotal) {
                    continue; // 不是总计行且人数为空，跳过
                }
            }
            // 按 colNameMap 提取所有列，输出字段名→值的 Map
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> colEntry : colNameMap.entrySet()) {
                Object val = row.get(colEntry.getKey());
                rowMap.put(colEntry.getValue(), val != null ? val.toString().trim() : "");
            }
            // 首列含"总计"/"合计"/"小计" → subtotal 类型，否则 data 类型
            String rowType = (col0Str.contains("总计") || col0Str.contains("合计") || col0Str.contains("小计"))
                    ? "subtotal" : "data";
            rowMap.put("_rowType", rowType);
            result.add(rowMap);
        }
        log.debug("[ReportEngine-RowTpl] Sheet '1 组织结构及管理架构' 数据提取完成，共 {} 行", result.size());
        return result;
    }

    /**
     * 关联公司信息表（2 关联公司信息）专用行模板数据提取。
     * <p>
     * Excel 结构（0-based行索引）：
     * <ul>
     *   <li>行0-3（index=0-3）：说明/提示文字（跳过）</li>
     *   <li>行4（index=4）：表头行（行次、关联方名称、关联方类型、国家（地区）等）</li>
     *   <li>行5（index=5）：列序号辅助行（col0="1", col1="2"...，非真实数据，需跳过）</li>
     *   <li>行6（index=6）起：真实数据行，col0（行次）非空且col1（关联方名称）非空为有效数据行</li>
     * </ul>
     * 双重条件校验（col0 非空 且 col1 非空）可准确跳过列序号辅助行。
     * </p>
     *
     * @param rows EasyExcel 读取的行数据（行索引从0开始）
     * @return 数据行列表，每行为 Map&lt;字段名, 值&gt;
     */
    private List<Map<String, Object>> extractRelatedCompanyData(List<Map<Integer, Object>> rows) {
        if (rows.size() < 7) {
            log.warn("[ReportEngine-RowTpl] Sheet '2 关联公司信息' 行数不足，无法提取行模板数据");
            return Collections.emptyList();
        }

        // 1. 解析行4（0-based index=4）为表头行，构建 colIdx→字段名 Map
        Map<Integer, String> colNameMap = new LinkedHashMap<>();
        Map<Integer, Object> headerRow = rows.get(4);
        for (Map.Entry<Integer, Object> entry : headerRow.entrySet()) {
            String colName = entry.getValue() != null ? entry.getValue().toString().trim() : "";
            if (!colName.isEmpty()) {
                colNameMap.put(entry.getKey(), colName);
            }
        }
        log.debug("[ReportEngine-RowTpl] Sheet '2 关联公司信息' 表头解析：{}", colNameMap);

        // 2. 从行5（0-based index=5）起扫描数据行
        //    双重条件：col0（行次）非空 且 col1（关联方名称）非空字符串，才为有效数据行
        //    行5（index=5）为列序号辅助行（col0="1",col1="2"...），col1为纯数字，会被双重条件准确跳过
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 5; i < rows.size(); i++) {
            Map<Integer, Object> row = rows.get(i);
            Object col0 = row.get(0);
            Object col1 = row.get(1);
            String col0Str = col0 != null ? col0.toString().trim() : "";
            String col1Str = col1 != null ? col1.toString().trim() : "";
            // 双重校验：行次非空 且 关联方名称非空（跳过辅助序号行及空白行）
            if (col0Str.isEmpty() || col1Str.isEmpty()) {
                continue;
            }
            // 按 colNameMap 提取所有列，输出字段名→值的 Map
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> colEntry : colNameMap.entrySet()) {
                Object val = row.get(colEntry.getKey());
                rowMap.put(colEntry.getValue(), val != null ? val.toString().trim() : "");
            }
            result.add(rowMap);
        }
        log.debug("[ReportEngine-RowTpl] Sheet '2 关联公司信息' 数据提取完成，共 {} 行", result.size());
        return result;
    }

    /**
     * 关联方个人信息表（关联方个人信息）专用行模板数据提取。
     * <p>
     * Excel 结构（0-based行索引）：
     * <ul>
     *   <li>行0（index=0）：表头行（个人关联方、国籍、关联关系类型、居住地址）</li>
     *   <li>行1（index=1）起：真实数据行，col0（个人关联方）非空为有效数据行</li>
     * </ul>
     * </p>
     *
     * @param rows EasyExcel 读取的行数据（行索引从0开始）
     * @return 数据行列表，每行为 Map&lt;字段名, 值&gt;
     */
    private List<Map<String, Object>> extractRelatedPersonData(List<Map<Integer, Object>> rows) {
        if (rows.size() < 2) {
            log.warn("[ReportEngine-RowTpl] Sheet '关联方个人信息' 行数不足，无法提取行模板数据");
            return Collections.emptyList();
        }

        // 1. 解析行0（0-based index=0）为表头行，构建 colIdx→字段名 Map
        Map<Integer, String> colNameMap = new LinkedHashMap<>();
        Map<Integer, Object> headerRow = rows.get(0);
        for (Map.Entry<Integer, Object> entry : headerRow.entrySet()) {
            String colName = entry.getValue() != null ? entry.getValue().toString().trim() : "";
            if (!colName.isEmpty()) {
                colNameMap.put(entry.getKey(), colName);
            }
        }
        log.debug("[ReportEngine-RowTpl] Sheet '关联方个人信息' 表头解析：{}", colNameMap);

        // 2. 从行1（0-based index=1）起扫描数据行，col0（个人关联方）非空则为有效数据行
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            Map<Integer, Object> row = rows.get(i);
            Object col0 = row.get(0);
            if (col0 == null || col0.toString().trim().isEmpty()) {
                continue;
            }
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> colEntry : colNameMap.entrySet()) {
                Object val = row.get(colEntry.getKey());
                rowMap.put(colEntry.getValue(), val != null ? val.toString().trim() : "");
            }
            result.add(rowMap);
        }
        log.debug("[ReportEngine-RowTpl] Sheet '关联方个人信息' 数据提取完成，共 {} 行", result.size());
        return result;
    }

    /**
     * 关联关系变化情况表（关联关系变化情况）专用行模板数据提取。
     * <p>
     * Excel 结构（0-based行索引）：
     * <ul>
     *   <li>行0（index=0）：表头行（关联方名称、国家/地区、关联关系类型、起止日期、变化原因）</li>
     *   <li>行1（index=1）起：真实数据行，col0（关联方名称）非空为有效数据行</li>
     * </ul>
     * </p>
     *
     * @param rows EasyExcel 读取的行数据（行索引从0开始）
     * @return 数据行列表，每行为 Map&lt;字段名, 值&gt;
     */
    private List<Map<String, Object>> extractRelationChangeData(List<Map<Integer, Object>> rows) {
        if (rows.size() < 2) {
            log.warn("[ReportEngine-RowTpl] Sheet '关联关系变化情况' 行数不足，无法提取行模板数据");
            return Collections.emptyList();
        }

        // 1. 解析行0（0-based index=0）为表头行，构建 colIdx→字段名 Map
        Map<Integer, String> colNameMap = new LinkedHashMap<>();
        Map<Integer, Object> headerRow = rows.get(0);
        for (Map.Entry<Integer, Object> entry : headerRow.entrySet()) {
            String colName = entry.getValue() != null ? entry.getValue().toString().trim() : "";
            if (!colName.isEmpty()) {
                colNameMap.put(entry.getKey(), colName);
            }
        }
        log.debug("[ReportEngine-RowTpl] Sheet '关联关系变化情况' 表头解析：{}", colNameMap);

        // 2. 从行1（0-based index=1）起扫描数据行，col0（关联方名称）非空则为有效数据行
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            Map<Integer, Object> row = rows.get(i);
            Object col0 = row.get(0);
            if (col0 == null || col0.toString().trim().isEmpty()) {
                continue;
            }
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> colEntry : colNameMap.entrySet()) {
                Object val = row.get(colEntry.getKey());
                rowMap.put(colEntry.getValue(), val != null ? val.toString().trim() : "");
            }
            result.add(rowMap);
        }
        log.debug("[ReportEngine-RowTpl] Sheet '关联关系变化情况' 数据提取完成，共 {} 行", result.size());
        return result;
    }

    /**
     * 关联交易汇总表（关联交易汇总表）专用行模板数据提取。
     * <p>
     * Excel 结构（0-based行索引）：
     * <ul>
     *   <li>行0（index=0）：主表头（关联交易类型、境外交易金额、境内交易金额、交易总额）</li>
     *   <li>行1（index=1）：副表头（空、A、B、C=A+B），需跳过</li>
     *   <li>行2（index=2）起：真实数据行，col0（关联交易类型）非空且不等于"合计"为有效数据行</li>
     * </ul>
     * </p>
     *
     * @param rows EasyExcel 读取的行数据（行索引从0开始）
     * @return 数据行列表，每行为 Map&lt;字段名, 值&gt;
     */
    private List<Map<String, Object>> extractRelatedTransactionSummaryData(List<Map<Integer, Object>> rows) {
        if (rows.size() < 3) {
            log.warn("[ReportEngine-RowTpl] Sheet '关联交易汇总表' 行数不足，无法提取行模板数据");
            return Collections.emptyList();
        }

        // 1. 解析行0（0-based index=0）为主表头行，构建 colIdx→字段名 Map
        Map<Integer, String> colNameMap = new LinkedHashMap<>();
        Map<Integer, Object> headerRow = rows.get(0);
        for (Map.Entry<Integer, Object> entry : headerRow.entrySet()) {
            String colName = entry.getValue() != null ? entry.getValue().toString().trim() : "";
            if (!colName.isEmpty()) {
                colNameMap.put(entry.getKey(), colName);
            }
        }
        log.debug("[ReportEngine-RowTpl] Sheet '关联交易汇总表' 表头解析：{}", colNameMap);

        // 2. 从行2（0-based index=2）起扫描数据行，行1为副表头跳过
        //    col0（关联交易类型）非空则为有效数据行
        //    包含"合计"的行标记为 subtotal 类型
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 2; i < rows.size(); i++) {
            Map<Integer, Object> row = rows.get(i);
            Object col0 = row.get(0);
            if (col0 == null || col0.toString().trim().isEmpty()) {
                continue;
            }
            String col0Str = col0.toString().trim();
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> colEntry : colNameMap.entrySet()) {
                Object val = row.get(colEntry.getKey());
                rowMap.put(colEntry.getValue(), val != null ? val.toString().trim() : "");
            }
            // 标记行类型：包含"合计"的为 subtotal，否则为 data
            if (col0Str.contains("合计")) {
                rowMap.put("_rowType", "subtotal");
            }
            result.add(rowMap);
        }
        log.debug("[ReportEngine-RowTpl] Sheet '关联交易汇总表' 数据提取完成，共 {} 行", result.size());
        return result;
    }

    /**
     * 劳务交易表（6 劳务交易表）专用行模板数据提取。
     * <p>
     * Excel 结构（0-based行索引）：
     * <ul>
     *   <li>行5（index=5）：表头行，列2=关联方名称，列4=交易金额，列5=比例，列6=占总劳务收入比重</li>
     *   <li>行8（index=8）起：实际数据，行次由 col0 决定</li>
     *   <li>行次1-7（index 8-14）：劳务收入-境外；行次7（index 14）：境外小计</li>
     *   <li>行次8-14（index 15-21）：劳务收入-境内；行次14（index 21）：境内小计</li>
     *   <li>行次15（index 22）：收入合计</li>
     *   <li>行次16-22（index 23-29）：劳务支出-境外；行次22（index 29）：境外小计</li>
     *   <li>行次23-29（index 30-36）：劳务支出-境内；行次29（index 36）：境内小计</li>
     *   <li>行次30（index 37）：支出合计</li>
     * </ul>
     * 占位符名含"支出"→取行次16-30（支出段），否则取行次1-15（收入段）。
     * </p>
     *
     * @param rows            EasyExcel 读取的行数据
     * @param placeholderName 占位符名称，用于区分支出/收入段
     * @return 数据行列表，每行为 Map&lt;字段名, 值&gt;
     */
    private List<Map<String, Object>> extractLaborServiceData(List<Map<Integer, Object>> rows, String placeholderName) {
        // 固定列映射（基于表头行结构）
        final String NAME_FIELD   = "关联方名称";
        final String AMOUNT_FIELD = "交易金额";
        // 占比字段名依据收入/支出占位符区分
        final boolean isExpense = placeholderName != null && placeholderName.contains("支出");
        final String RATIO_FIELD  = isExpense ? "占总经营成本费用比重（%）" : "占营业收入比重（%）";

        // 名称列=col2, 金额列=col4, 占比列=col6（收入）或col6（支出，仅有col5）
        // 实测清单：col5=比例(百分比), col6=占总劳务收入比重 → 统一取col4金额,col5占比
        final int NAME_COL   = 2;
        final int AMOUNT_COL = 4;
        final int RATIO_COL  = 5;

        // 数据范围（0-based行索引）
        // 表头行index=5，数据从index=8开始（行次1）
        // 收入段：行次1-15 → index 8-22 (inclusive)
        // 支出段：行次16-30 → index 23-37 (inclusive)
        int dataStart = isExpense ? 23 : 8;
        int dataEnd   = isExpense ? 38 : 23;  // exclusive

        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = dataStart; i < dataEnd && i < rows.size(); i++) {
            Map<Integer, Object> row = rows.get(i);
            Object col0 = row.get(0); // 行次
            Object col1 = row.get(1); // 关联交易类型（分组标题）
            Object colName = row.get(NAME_COL);

            String col0Str   = col0   != null ? col0.toString().trim()   : "";
            String col1Str   = col1   != null ? col1.toString().trim()   : "";
            String nameStr   = colName != null ? colName.toString().trim() : "";

            // 跳过完全空行（col0和col1都为空）
            if (col0Str.isEmpty() && col1Str.isEmpty()) continue;

            // 小计/合计行：col1含"小计"/"合计"
            if (col1Str.contains("小计") || col1Str.contains("合计") || col1Str.contains("总计")) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put(NAME_FIELD,   col1Str);
                map.put(AMOUNT_FIELD, toPlainString(row.get(AMOUNT_COL)));
                map.put(RATIO_FIELD,  toPlainString(row.get(RATIO_COL)));
                map.put("_rowType",   "subtotal");
                result.add(map);
                continue;
            }

            // 分组标题行：col1含"境外"/"境内"（且名称列为空或含"────"）
            if ((col1Str.contains("境外") || col1Str.contains("境内"))
                    && (nameStr.isEmpty() || nameStr.contains("────"))) {
                // 取分组标签，去掉换行符
                String groupLabel = col1Str.replace("\n", "").replace("\r", "").trim();
                Map<String, Object> map = new LinkedHashMap<>();
                map.put(NAME_FIELD,   groupLabel);
                map.put(AMOUNT_FIELD, "");
                map.put(RATIO_FIELD,  "");
                map.put("_rowType",   "group");
                result.add(map);
                continue;
            }

            // "────"占位行（其他关联方占位符行）：跳过
            if (nameStr.equals("────") || nameStr.equals("其他关联方")) continue;

            // 明细行：名称列非空且不含上述关键词
            if (!nameStr.isEmpty()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put(NAME_FIELD,   nameStr);
                map.put(AMOUNT_FIELD, toPlainString(row.get(AMOUNT_COL)));
                map.put(RATIO_FIELD,  toPlainString(row.get(RATIO_COL)));
                map.put("_rowType",   "data");
                result.add(map);
            }
        }

        log.debug("[ReportEngine-Labor] placeholderName='{}' ({}), 提取数据行={}", placeholderName, isExpense ? "支出" : "收入", result.size());
        return result;
    }

    /**
     * 劳务成本归集 Sheet 专用行模板数据提取。
     * <p>
     * Excel 结构（0-based行索引）：
     * <ul>
     *   <li>行0（index=0）：表头行（劳务内容 | 分配方法 | 总成本费用 | 所需承担的比例）</li>
     *   <li>行1起：数据行，无分组/小计/break逻辑，全部输出为 _rowType=data</li>
     * </ul>
     * </p>
     *
     * @param rows EasyExcel 读取的行数据
     * @return 数据行列表
     */
    private List<Map<String, Object>> extractLaborCostData(List<Map<Integer, Object>> rows) {
        if (rows.isEmpty()) {
            log.warn("[ReportEngine-LaborCost] Sheet '劳务成本归集' 无数据行");
            return Collections.emptyList();
        }

        // 行0：动态解析表头，构建 colIdx->字段名 Map
        Map<Integer, String> colNameMap = new LinkedHashMap<>();
        Map<Integer, Object> headerRow = rows.get(0);
        for (Map.Entry<Integer, Object> entry : headerRow.entrySet()) {
            String colName = entry.getValue() != null ? entry.getValue().toString().trim() : "";
            if (!colName.isEmpty()) {
                colNameMap.put(entry.getKey(), colName);
            }
        }
        if (colNameMap.isEmpty()) {
            log.warn("[ReportEngine-LaborCost] 表头行解析失败，无有效列名");
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();

        // 行1起：逐行提取数据
        for (int i = 1; i < rows.size(); i++) {
            Map<Integer, Object> row = rows.get(i);

            // 判断是否为全空行
            boolean allEmpty = colNameMap.keySet().stream()
                    .allMatch(col -> row.get(col) == null || row.get(col).toString().trim().isEmpty());
            if (allEmpty) continue;

            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> colEntry : colNameMap.entrySet()) {
                map.put(colEntry.getValue(), toPlainString(row.get(colEntry.getKey())));
            }
            map.put("_rowType", "data");
            result.add(map);
        }

        log.debug("[ReportEngine-LaborCost] 提取数据行={}", result.size());
        return result;
    }

    /**
     * 提取"资金融通" Sheet 数据行（TABLE_ROW_TEMPLATE 专用）。
     *
     * <p>Sheet 结构：
     * <ul>
     *   <li>行0：表头（关联方 | 金额）</li>
     *   <li>行1起：数据行；关联方列值含"合计"→subtotal；值为"没找到"→跳过；全空→跳过</li>
     * </ul>
     * </p>
     *
     * @param rows EasyExcel 读取的行数据
     * @return 数据行列表
     */
    private List<Map<String, Object>> extractFundTransferData(List<Map<Integer, Object>> rows) {
        if (rows.isEmpty()) {
            log.warn("[ReportEngine-FundTransfer] Sheet '资金融通' 无数据行");
            return Collections.emptyList();
        }

        // 行0：动态解析表头，构建 colIdx->字段名 Map
        Map<Integer, String> colNameMap = new LinkedHashMap<>();
        Map<Integer, Object> headerRow = rows.get(0);
        for (Map.Entry<Integer, Object> entry : headerRow.entrySet()) {
            String colName = entry.getValue() != null ? entry.getValue().toString().trim() : "";
            if (!colName.isEmpty()) {
                colNameMap.put(entry.getKey(), colName);
            }
        }
        if (colNameMap.isEmpty()) {
            log.warn("[ReportEngine-FundTransfer] 表头行解析失败，无有效列名");
            return Collections.emptyList();
        }

        // 确定关联方列（第一列，用于判断合计/没找到）
        Integer firstColIdx = colNameMap.keySet().stream().findFirst().orElse(0);

        List<Map<String, Object>> result = new ArrayList<>();

        // 行1起：逐行提取数据
        for (int i = 1; i < rows.size(); i++) {
            Map<Integer, Object> row = rows.get(i);

            // 判断是否为全空行
            boolean allEmpty = colNameMap.keySet().stream()
                    .allMatch(col -> row.get(col) == null || row.get(col).toString().trim().isEmpty());
            if (allEmpty) continue;

            // 关联方列的值
            Object firstCell = row.get(firstColIdx);
            String firstCellStr = firstCell != null ? firstCell.toString().trim() : "";

            // "没找到"行：跳过
            if ("没找到".equals(firstCellStr)) {
                log.debug("[ReportEngine-FundTransfer] 行{} 为'没找到'标记，跳过", i);
                continue;
            }

            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> colEntry : colNameMap.entrySet()) {
                map.put(colEntry.getValue(), toPlainString(row.get(colEntry.getKey())));
            }

            // 含"合计"→ subtotal，否则 data
            String rowType = firstCellStr.contains("合计") ? "subtotal" : "data";
            map.put("_rowType", rowType);
            result.add(map);
        }

        log.debug("[ReportEngine-FundTransfer] 提取数据行={}", result.size());
        return result;
    }

    /**
     * 提取"公司间资金融通交易总结" Sheet 数据行（TABLE_ROW_TEMPLATE 专用）。
     *
     * <p>Sheet 结构：
     * <ul>
     *   <li>行0：主表头（缔约方|公司间资金融通交易性质|货币|本金|本金|到期期限|利率|利息收入/利息支出|利息收入/利息支出）</li>
     *   <li>行1：副表头（本金列→"（原币）"/"（人民币）"，利息列同样拆分）</li>
     *   <li>行2起：数据行；"没找到"行跳过；全空行跳过</li>
     * </ul>
     * </p>
     *
     * @param rows EasyExcel 读取的行数据
     * @return 数据行列表
     */
    private List<Map<String, Object>> extractInterCompanyFundData(List<Map<Integer, Object>> rows) {
        if (rows.size() < 2) {
            log.warn("[ReportEngine-InterCompanyFund] Sheet '公司间资金融通交易总结' 行数不足，无法解析双行表头");
            return Collections.emptyList();
        }

        // 行0+行1：合并构建 colIdx->字段名 Map
        // 规则：若行1同列非空则 colName = 行0值 + 行1值，否则 colName = 行0值
        Map<Integer, String> colNameMap = new LinkedHashMap<>();
        Map<Integer, Object> headerRow0 = rows.get(0);
        Map<Integer, Object> headerRow1 = rows.get(1);

        // 先收集行0的所有列索引
        for (Map.Entry<Integer, Object> entry : headerRow0.entrySet()) {
            String h0 = entry.getValue() != null ? entry.getValue().toString().trim() : "";
            if (h0.isEmpty()) continue;
            Object h1Val = headerRow1.get(entry.getKey());
            String h1 = h1Val != null ? h1Val.toString().trim() : "";
            String colName = h1.isEmpty() ? h0 : h0 + h1;
            colNameMap.put(entry.getKey(), colName);
        }

        if (colNameMap.isEmpty()) {
            log.warn("[ReportEngine-InterCompanyFund] 表头解析失败，无有效列名");
            return Collections.emptyList();
        }
        log.debug("[ReportEngine-InterCompanyFund] 合并双行表头：{}", colNameMap);

        // 确定第一列（缔约方），用于判断"没找到"
        Integer firstColIdx = colNameMap.keySet().stream().findFirst().orElse(0);

        List<Map<String, Object>> result = new ArrayList<>();

        // 行2起：逐行提取数据
        for (int i = 2; i < rows.size(); i++) {
            Map<Integer, Object> row = rows.get(i);

            // 全空行跳过
            boolean allEmpty = colNameMap.keySet().stream()
                    .allMatch(col -> row.get(col) == null || row.get(col).toString().trim().isEmpty());
            if (allEmpty) continue;

            // "没找到"行跳过
            Object firstCell = row.get(firstColIdx);
            String firstCellStr = firstCell != null ? firstCell.toString().trim() : "";
            if ("没找到".equals(firstCellStr)) {
                log.debug("[ReportEngine-InterCompanyFund] 行{} 为'没找到'标记，跳过", i);
                continue;
            }

            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> colEntry : colNameMap.entrySet()) {
                map.put(colEntry.getValue(), toPlainString(row.get(colEntry.getKey())));
            }
            map.put("_rowType", "data");
            result.add(map);
        }

        log.debug("[ReportEngine-InterCompanyFund] 提取数据行={}", result.size());
        return result;
    }

    /**
     * 提取"有形资产信息" Sheet 数据（单行表头，3列，合计行→subtotal）。
     *
     * <p>Sheet 结构：
     * <ul>
     *   <li>行0：表头（资产净值 | 年初数 | 年末数）</li>
     *   <li>行1~N-1：数据行（各类资产）</li>
     *   <li>含"合计"的行：_rowType=subtotal</li>
     * </ul>
     *
     * @param rows EasyExcel 读取的行数据
     * @return 数据行列表
     */
    private List<Map<String, Object>> extractTangibleAssetData(List<Map<Integer, Object>> rows) {
        if (rows.isEmpty()) {
            log.warn("[ReportEngine-TangibleAsset] Sheet '有形资产信息' 无数据行");
            return Collections.emptyList();
        }

        // 1. 解析行0表头，构建 colIdx → 字段名 Map
        Map<Integer, String> colNameMap = new LinkedHashMap<>();
        Map<Integer, Object> headerRow = rows.get(0);
        for (Map.Entry<Integer, Object> entry : headerRow.entrySet()) {
            String col = entry.getValue() != null ? entry.getValue().toString().trim() : "";
            if (!col.isEmpty()) {
                colNameMap.put(entry.getKey(), col);
            }
        }

        if (colNameMap.isEmpty()) {
            log.warn("[ReportEngine-TangibleAsset] 表头解析失败，无有效列名");
            return Collections.emptyList();
        }
        log.debug("[ReportEngine-TangibleAsset] 表头：{}", colNameMap);

        // 确定第一列索引，用于判断"没找到"和"合计"
        Integer firstColIdx = colNameMap.keySet().stream().findFirst().orElse(0);

        List<Map<String, Object>> result = new ArrayList<>();

        // 2. 行1起逐行提取数据
        for (int i = 1; i < rows.size(); i++) {
            Map<Integer, Object> row = rows.get(i);

            // 全空行跳过
            boolean allEmpty = colNameMap.keySet().stream()
                    .allMatch(col -> row.get(col) == null || row.get(col).toString().trim().isEmpty());
            if (allEmpty) continue;

            // "没找到"行跳过
            Object firstCell = row.get(firstColIdx);
            String firstCellStr = firstCell != null ? firstCell.toString().trim() : "";
            if ("没找到".equals(firstCellStr)) {
                log.debug("[ReportEngine-TangibleAsset] 行{} 为'没找到'标记，跳过", i);
                continue;
            }

            // 含"合计"→ subtotal，否则 data
            String rowType = firstCellStr.contains("合计") ? "subtotal" : "data";

            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> colEntry : colNameMap.entrySet()) {
                map.put(colEntry.getValue(), toPlainString(row.get(colEntry.getKey())));
            }
            map.put("_rowType", rowType);
            result.add(map);
        }

        log.debug("[ReportEngine-TangibleAsset] 提取数据行={}", result.size());
        return result;
    }

    /**
     * 提取"功能风险汇总表" Sheet 数据（单行表头，4列，无合计行，全部标记 data）。
     *
     * <p>Sheet 结构：
     * <ul>
     *   <li>行0：表头（序号 | 风险 | 【清单模板-数据表B5】 | 关联方）</li>
     *   <li>行1起：数据行，_rowType=data</li>
     *   <li>"没找到"标记行跳过，全空行跳过</li>
     * </ul>
     *
     * @param rows EasyExcel 读取的行数据
     * @return 数据行列表
     */
    private List<Map<String, Object>> extractFuncRiskData(List<Map<Integer, Object>> rows) {
        if (rows.isEmpty()) {
            log.warn("[ReportEngine-FuncRisk] Sheet '功能风险汇总表' 无数据行");
            return Collections.emptyList();
        }

        // 1. 解析行0表头，构建 colIdx → 字段名 Map
        Map<Integer, String> colNameMap = new LinkedHashMap<>();
        Map<Integer, Object> headerRow = rows.get(0);
        for (Map.Entry<Integer, Object> entry : headerRow.entrySet()) {
            String col = entry.getValue() != null ? entry.getValue().toString().trim() : "";
            if (!col.isEmpty()) {
                colNameMap.put(entry.getKey(), col);
            }
        }

        if (colNameMap.isEmpty()) {
            log.warn("[ReportEngine-FuncRisk] 表头解析失败，无有效列名");
            return Collections.emptyList();
        }
        log.debug("[ReportEngine-FuncRisk] 表头：{}", colNameMap);

        // 确定第一列索引，用于判断"没找到"
        Integer firstColIdx = colNameMap.keySet().stream().findFirst().orElse(0);

        List<Map<String, Object>> result = new ArrayList<>();

        // 2. 行1起逐行提取数据
        for (int i = 1; i < rows.size(); i++) {
            Map<Integer, Object> row = rows.get(i);

            // 全空行跳过
            boolean allEmpty = colNameMap.keySet().stream()
                    .allMatch(col -> row.get(col) == null || row.get(col).toString().trim().isEmpty());
            if (allEmpty) continue;

            // "没找到"行跳过
            Object firstCell = row.get(firstColIdx);
            String firstCellStr = firstCell != null ? firstCell.toString().trim() : "";
            if ("没找到".equals(firstCellStr)) {
                log.debug("[ReportEngine-FuncRisk] 行{} 为'没找到'标记，跳过", i);
                continue;
            }

            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> colEntry : colNameMap.entrySet()) {
                map.put(colEntry.getValue(), toPlainString(row.get(colEntry.getKey())));
            }
            map.put("_rowType", "data");
            result.add(map);
        }

        log.debug("[ReportEngine-FuncRisk] 提取数据行={}", result.size());
        return result;
    }

    /**
     * 提取"主要产品" Sheet 数据（单行表头，3列，含合计行→subtotal）。
     *
     * <p>Sheet 结构：
     * <ul>
     *   <li>行0：表头（产品 | 销售额（万元） | 占比(%)）</li>
     *   <li>行1~N-1：数据行（各产品），_rowType=data</li>
     *   <li>首列含"总计"/"合计"/"小计"的行：_rowType=subtotal</li>
     *   <li>全空行及"没找到"标记行跳过</li>
     * </ul>
     *
     * @param rows EasyExcel 读取的行数据
     * @return 数据行列表
     */
    private List<Map<String, Object>> extractMainProductData(List<Map<Integer, Object>> rows) {
        if (rows.isEmpty()) {
            log.warn("[ReportEngine-MainProduct] Sheet '主要产品' 无数据行");
            return Collections.emptyList();
        }

        // 1. 解析行0表头，构建 colIdx → 字段名 Map
        Map<Integer, String> colNameMap = new LinkedHashMap<>();
        Map<Integer, Object> headerRow = rows.get(0);
        for (Map.Entry<Integer, Object> entry : headerRow.entrySet()) {
            String col = entry.getValue() != null ? entry.getValue().toString().trim() : "";
            if (!col.isEmpty()) {
                colNameMap.put(entry.getKey(), col);
            }
        }

        if (colNameMap.isEmpty()) {
            log.warn("[ReportEngine-MainProduct] 表头解析失败，无有效列名");
            return Collections.emptyList();
        }
        log.debug("[ReportEngine-MainProduct] 表头：{}", colNameMap);

        // 确定第一列索引，用于判断"没找到"和合计行
        Integer firstColIdx = colNameMap.keySet().stream().findFirst().orElse(0);

        List<Map<String, Object>> result = new ArrayList<>();

        // 2. 行1起逐行提取数据
        for (int i = 1; i < rows.size(); i++) {
            Map<Integer, Object> row = rows.get(i);

            // 全空行跳过
            boolean allEmpty = colNameMap.keySet().stream()
                    .allMatch(col -> row.get(col) == null || row.get(col).toString().trim().isEmpty());
            if (allEmpty) continue;

            // "没找到"行跳过
            Object firstCell = row.get(firstColIdx);
            String firstCellStr = firstCell != null ? firstCell.toString().trim() : "";
            if ("没找到".equals(firstCellStr)) {
                log.debug("[ReportEngine-MainProduct] 行{} 为'没找到'标记，跳过", i);
                continue;
            }

            // 首列含"总计"/"合计"/"小计" → subtotal，否则 data
            String rowType = (firstCellStr.contains("总计") || firstCellStr.contains("合计") || firstCellStr.contains("小计"))
                    ? "subtotal" : "data";

            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> colEntry : colNameMap.entrySet()) {
                map.put(colEntry.getValue(), toPlainString(row.get(colEntry.getKey())));
            }
            map.put("_rowType", rowType);
            result.add(map);
        }

        log.debug("[ReportEngine-MainProduct] 提取数据行={}", result.size());
        return result;
    }

    private Map<String, Object> buildRowMap(String name, String amount, String ratio,
                                             Map<Integer, String> colNameMap,
                                             int nameColIdx, int amountColIdx, int ratioColIdx,
                                             String rowType) {
        Map<String, Object> map = new LinkedHashMap<>();
        // 按动态字段名存入
        String nameField = colNameMap.getOrDefault(nameColIdx, "名称");
        String amountField = colNameMap.getOrDefault(amountColIdx, "交易金额");
        String ratioField = colNameMap.getOrDefault(ratioColIdx, "占比");
        map.put(nameField, name);
        map.put(amountField, amount);
        map.put(ratioField, ratio);
        // 存入行类型标识，供填充引擎按类型路由到对应模板行克隆
        map.put("_rowType", rowType != null ? rowType : "data");
        return map;
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
     * <p>
     * 同时处理行模板克隆类型（rowTemplateValues），按字段名填充。
     * </p>
     */
    private void replaceTablePlaceholders(XWPFDocument doc, Map<String, List<List<Object>>> tableValues,
                                          Map<String, List<Map<String, Object>>> rowTemplateValues) {
        // 处理普通表格占位符
        for (Map.Entry<String, List<List<Object>>> entry : tableValues.entrySet()) {
            String phName = entry.getKey();
            List<List<Object>> data = entry.getValue();
            String marker = "{{" + phName + "}}";

            // 在文档表格中查找标记
            boolean found = false;
            for (XWPFTable table : doc.getTables()) {
                // 直接操作 CT 层读取文本，避免 POI 缓存失效
                boolean tableMatched = false;
                XWPFTableCell matchedCell = null;
                outer:
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        CTTc ctTc = cell.getCTTc();
                        StringBuilder sb = new StringBuilder();
                        for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p : ctTc.getPArray())
                            for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR r : p.getRArray())
                                for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText t : r.getTArray())
                                    if (t.getStringValue() != null) sb.append(t.getStringValue());
                        if (sb.toString().contains(marker)) { matchedCell = cell; tableMatched = true; break outer; }
                    }
                }
                if (tableMatched && matchedCell != null) {
                    // 清除标记单元格内容
                    clearCellText(matchedCell, marker);
                    // 检测该表格是否存在行模板标记（{{_tpl_}}），有则走克隆路径
                    boolean hasRowTemplate = tableHasRowTemplateMarker(table);
                    if (hasRowTemplate) {
                        fillTableByRowTemplate(table, data);
                    } else {
                        // 填充数据到该表格（从第二行开始追加，第一行保留为表头）
                        fillTableWithData(table, data);
                    }
                    found = true;
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

        // 处理行模板克隆占位符（按字段名填充）
        for (Map.Entry<String, List<Map<String, Object>>> entry : rowTemplateValues.entrySet()) {
            String phName = entry.getKey();
            List<Map<String, Object>> rowData = entry.getValue();
            // 行模板的整表标识 marker 仍是 {{_tpl_占位符名}}，在 cell 内部，
            // 通过扫描含 {{_tpl_ 的表格来定位，然后调用 fillTableByRowTemplateMapped
            boolean found = false;
            for (XWPFTable table : doc.getTables()) {
                if (tableHasRowTemplateMarkerFor(table, phName)) {
                    fillTableByRowTemplateMapped(table, rowData);
                    found = true;
                    break;
                }
            }
            if (!found) {
                log.warn("[ReportEngine-RowTpl] 未在文档中找到占位符 '{}' 对应的行模板表格", phName);
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
        int tableRowCount = table.getRows().size();
        int tableColCount = tableRowCount == 0 ? Integer.MAX_VALUE
                : table.getRow(0).getTableCells().size();

        // TABLE_CLEAR_FULL 类型：data[0] 是实际表头，需要填充到表格第一行
        // 其他类型：data[0] 是虚拟表头，跳过
        int startDataRow = 0; // 从第0行开始，包含表头
        log.info("[ReportEngine] fillTableWithData: 表格原有 {} 行, 数据共 {} 行(含表头), 从第 {} 行开始填充",
                tableRowCount, data.size(), startDataRow);

        for (int i = startDataRow; i < data.size(); i++) {
            List<Object> dataRow = data.get(i);
            XWPFTableRow tableRow;
            // 复用已有行或新增行
            // 注意：i 是数据索引，表格行索引应该也是 i（因为 data[0] 是表头，对应 table.getRow(0)）
            if (i < table.getRows().size()) {
                tableRow = table.getRow(i);
                log.debug("[ReportEngine] 复用表格第 {} 行", i);
            } else {
                tableRow = table.createRow();
                log.debug("[ReportEngine] 创建新行，当前表格共 {} 行", table.getRows().size());
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
                Object dataVal = dataRow.get(j);
                String cellVal = dataVal != null ? dataVal.toString() : "";
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
        log.info("[ReportEngine] fillTableWithData 完成，表格现在有 {} 行", table.getRows().size());
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
        // 对以 "-B2" 结尾或名称为 "年度" 的占位符进行年度扩展
        boolean isYearPlaceholder = placeholderName != null && 
                (placeholderName.endsWith("-B2") || "年度".equals(placeholderName));
        if (isYearPlaceholder && value != null && value.matches("^\\d{2}$")) {
            return "20" + value + "年";
        }
        return value;
    }

    /**
     * 格式化数字为字符串，保留标准数字格式（负数显示为 -X.XX，而非 (X.XX)）。
     */
    private String formatNumber(java.math.BigDecimal value) {
        if (value == null) return "";
        // 使用标准数字格式，不使用会计格式
        java.text.DecimalFormat df = new java.text.DecimalFormat("#,##0.00");
        df.setNegativePrefix("-");
        return df.format(value);
    }

    private String formatNumber(double value) {
        java.text.DecimalFormat df = new java.text.DecimalFormat("#,##0.00");
        df.setNegativePrefix("-");
        return df.format(value);
    }

    /**
     * 转换会计格式的数字字符串为标准格式。
     * <p>
     * 会计格式使用括号表示负数，如 (2,200.00) 表示 -2,200.00
     * 此方法将会计格式转换为标准负数格式。
     * </p>
     *
     * @param value 会计格式的数字字符串，如 "(2,200.00)" 或 "1,000.00"
     * @return 标准格式的数字字符串，如 "-2,200.00" 或 "1,000.00"
     */
    private String convertAccountingNumber(String value) {
        if (value == null || value.trim().isEmpty()) {
            return value;
        }
        String trimmed = value.trim();
        // 检查是否是会计格式的负数：(数字)
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            // 去掉括号，添加负号前缀
            String numberPart = trimmed.substring(1, trimmed.length() - 1);
            return "-" + numberPart;
        }
        // 如果不是会计格式，直接返回原值
        return value;
    }

    /**
     * 检测表格中是否存在行模板标记（单元格文本含 "{{_tpl_" 前缀）。
     */
    private boolean tableHasRowTemplateMarker(XWPFTable table) {
        // 直接操作 CT 层，避免 POI 缓存失效导致 XmlValueDisconnectedException
        CTTbl ctTbl = table.getCTTbl();
        for (int i = 0; i < ctTbl.sizeOfTrArray(); i++) {
            if (getCtRowText(ctTbl.getTrArray(i)).contains("{{_tpl_")) {
                return true;
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

            // 将新行 CTRow 插入到 CTTbl 中模板行之前（使用 CTTbl trArray，避免 XmlValueDisconnectedException）
            int insertPos = tplRowIdx + insertedCount;
            int curSize = ctTbl.sizeOfTrArray();
            ctTbl.addNewTr();
            for (int si = curSize; si > insertPos; si--) {
                ctTbl.setTrArray(si, ctTbl.getTrArray(si - 1));
            }
            ctTbl.setTrArray(insertPos, newCt);
            insertedCount++;
        }

        // 3. 删除原始模板行（使用 CTTbl removeTr，避免 XmlValueDisconnectedException）
        int realTplIdx = tplRowIdx + insertedCount;
        ctTbl.removeTr(realTplIdx);

        log.info("[ReportEngine-RowTemplate] 行模板克隆完成：模板行idx={}，克隆插入 {} 行，数据行数={}",
                tplRowIdx, insertedCount, data.size() - 1);
    }

    /**
     * 检测表格中是否存在指定占位符名称的行模板标记（{{_tpl_占位符名}}）。
     *
     * @param table       Word 表格
     * @param phName      占位符名称
     * @return 是否找到对应的行模板标记
     */
    private boolean tableHasRowTemplateMarkerFor(XWPFTable table, String phName) {
        String tplMarker = "{{_tpl_" + phName + "}}";
        // 直接操作 CT 层，避免 POI 缓存失效导致 XmlValueDisconnectedException
        CTTbl ctTbl = table.getCTTbl();
        for (int i = 0; i < ctTbl.sizeOfTrArray(); i++) {
            String rowText = getCtRowText(ctTbl.getTrArray(i));
            if (rowText.contains(tplMarker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 行模板克隆按字段名填充表格（新方案，替代按列索引的旧方案）。
     * <p>
     * 逻辑：
     * <ol>
     *   <li>扫描表格，找到含 {@code {{_tpl_}} 标记的模板行（tplRowIdx）</li>
     *   <li>对 rowData 每条数据（Map&lt;字段名,值&gt;），深克隆模板行 XML</li>
     *   <li>对克隆行每个 cell，读取 cell 文本中的 {@code {{_col_字段名}}}，按字段名查 Map 取值填入</li>
     *   <li>无 {@code {{_col_}}} 标记的列清空；无法匹配字段名时留空并记录警告</li>
     *   <li>将所有新行插入模板行之前，最后删除原模板行</li>
     * </ol>
     * </p>
     *
     * @param table   目标 Word 表格
     * @param rowData 数据行列表，每行为字段名->值的 Map
     */
    private void fillTableByRowTemplateMapped(XWPFTable table, List<Map<String, Object>> rowData) {
        if (rowData == null) {
            rowData = Collections.emptyList();
        }

        CTTbl ctTbl = table.getCTTbl();

        // -----------------------------------------------------------------------
        // 工具：从 CTRow 读取所有 cell 的纯文本（拼接）
        // 全程使用 CT 层操作，不依赖 XWPFTableRow/XWPFTableCell 缓存对象，
        // 避免 setTrArray 后 POI 缓存失效引发 XmlValueDisconnectedException
        // -----------------------------------------------------------------------

        // 1. 扫描所有行，识别模板行（data/group/subtotal），记录每行的类型和索引
        class RowInfo {
            int idx;
            String type; // "data", "group", "subtotal"
            List<String> colFields; // 该行的列字段名列表
            RowInfo(int idx, String type, List<String> colFields) {
                this.idx = idx; this.type = type; this.colFields = colFields;
            }
        }
        List<RowInfo> allTplRows = new ArrayList<>();

        for (int i = 0; i < ctTbl.sizeOfTrArray(); i++) {
            CTRow ctRow = ctTbl.getTrArray(i);
            String rowText = getCtRowText(ctRow);

            String rowType = null;
            // 优先级：subtotal > group > data（避免同时包含_tpl_和_row_subtotal的行被误判为data）
            if (rowText.contains("{{_row_subtotal}}")) {
                rowType = "subtotal";
            } else if (rowText.contains("{{_row_group}}")) {
                rowType = "group";
            } else if (rowText.contains("{{_tpl_") || rowText.contains("{{_row_data}}")) {
                rowType = "data";
            }

            if (rowType != null) {
                List<String> colFields = getCtRowColFields(ctRow);
                allTplRows.add(new RowInfo(i, rowType, colFields));
            }
        }

        if (allTplRows.isEmpty()) {
            log.warn("[ReportEngine-RowTplMapped] 未找到任何模板行标记（{{_tpl_}} / {{_row_XXX}}），跳过");
            return;
        }

        // 2. 动态调整行数
        RowInfo dataTpl = allTplRows.stream().filter(r -> "data".equals(r.type)).findFirst().orElse(null);
        if (dataTpl == null) {
            log.warn("[ReportEngine-RowTplMapped] 未找到 data 类型模板行，跳过");
            return;
        }

        int dataRowCount = (int) allTplRows.stream().filter(r -> "data".equals(r.type)).count();
        int actualDataCount = (int) rowData.stream()
                .filter(m -> !"subtotal".equals(m.get("_rowType")))
                .count();

        log.info("[ReportEngine-RowTplMapped] 模板中 data 行数={}, 实际数据行数={}", dataRowCount, actualDataCount);

        List<Integer> dataRowIdxList = allTplRows.stream()
                .filter(r -> "data".equals(r.type))
                .map(r -> r.idx)
                .sorted(java.util.Comparator.reverseOrder())
                .collect(java.util.stream.Collectors.toList());

        int netChange = 0;

        if (actualDataCount > dataRowCount) {
            int needClone = actualDataCount - dataRowCount;
            int lastDataIdx = dataRowIdxList.get(0);
            // 直接从 CTTbl 取模板行，避免使用 XWPFTableRow 缓存
            CTRow tplCtRow = ctTbl.getTrArray(lastDataIdx);

            for (int i = 0; i < needClone; i++) {
                CTRow newCt = (CTRow) tplCtRow.copy();
                int insertPos = lastDataIdx + netChange + 1;
                insertRowAt(table, newCt, insertPos);
                netChange++;
            }
            log.debug("[ReportEngine-RowTplMapped] 克隆了 {} 行", needClone);
        } else if (actualDataCount < dataRowCount) {
            int needDelete = dataRowCount - actualDataCount;
            for (int i = 0; i < needDelete && i < dataRowIdxList.size(); i++) {
                int idxToDelete = dataRowIdxList.get(i) + netChange;
                removeRowAt(table, idxToDelete);
                netChange--;
            }
            log.debug("[ReportEngine-RowTplMapped] 删除了 {} 行", needDelete);
        }

        // 3. 重新扫描（行数已变化），全程用 CTTbl.getTrArray
        List<RowInfo> finalTplRows = new ArrayList<>();
        int trArraySize = ctTbl.sizeOfTrArray();
        log.info("[ReportEngine-RowTplMapped] 重新扫描，ctTbl.sizeOfTrArray()={}", trArraySize);
        for (int i = 0; i < trArraySize; i++) {
            CTRow ctRow = ctTbl.getTrArray(i);
            String rowText = getCtRowText(ctRow);
            log.info("[ReportEngine-RowTplMapped] 扫描行{}: text={}", i, rowText.substring(0, Math.min(100, rowText.length())));

            String rowType = null;
            // 优先级：subtotal > group > data（避免同时包含_tpl_和_row_subtotal的行被误判为data）
            if (rowText.contains("{{_row_subtotal}}")) {
                rowType = "subtotal";
            } else if (rowText.contains("{{_row_group}}")) {
                rowType = "group";
            } else if (rowText.contains("{{_tpl_") || rowText.contains("{{_row_data}}")) {
                rowType = "data";
            }

            if (rowType != null) {
                List<String> colFields = getCtRowColFields(ctRow);
                finalTplRows.add(new RowInfo(i, rowType, colFields));
                log.info("[ReportEngine-RowTplMapped] 识别到 {} 行，索引={}", rowType, i);
            }
        }

        // 4. 填充数据（直接操作 CTRow）
        int dataIdx = 0;
        for (RowInfo info : finalTplRows) {
            CTRow ctRow = ctTbl.getTrArray(info.idx);

            if ("data".equals(info.type)) {
                while (dataIdx < rowData.size() && "subtotal".equals(rowData.get(dataIdx).get("_rowType"))) {
                    dataIdx++;
                }
                if (dataIdx < rowData.size()) {
                    fillCtRowWithData(ctRow, info.colFields, rowData.get(dataIdx));
                    dataIdx++;
                } else {
                    fillCtRowWithData(ctRow, info.colFields, Collections.emptyMap());
                }
            } else if ("subtotal".equals(info.type)) {
                rowData.stream()
                        .filter(m -> "subtotal".equals(m.get("_rowType")))
                        .findFirst()
                        .ifPresent(subtotalData -> fillCtRowWithData(ctRow, info.colFields, subtotalData));
            }
            // group 行保持原样
        }

        log.info("[ReportEngine-RowTplMapped] 行模板填充完成：处理了 {} 个模板行，填充了 {} 行数据，finalTplRows包含subtotal={}",
                finalTplRows.size(), dataIdx,
                finalTplRows.stream().anyMatch(r -> "subtotal".equals(r.type)));
    }

    /** 从 CTRow 中读取所有 cell 文本拼接成字符串（CT层，不依赖 POI 缓存） */
    private String getCtRowText(CTRow ctRow) {
        StringBuilder sb = new StringBuilder();
        for (CTTc ctTc : ctRow.getTcArray()) {
            for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp : ctTc.getPArray()) {
                for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR ctr : ctp.getRArray()) {
                    for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText t : ctr.getTArray()) {
                        if (t.getStringValue() != null) sb.append(t.getStringValue());
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 从 CTRow 每个 cell 中提取 {{_col_字段名}}，返回按列顺序的字段名列表 */
    private List<String> getCtRowColFields(CTRow ctRow) {
        List<String> fields = new ArrayList<>();
        for (CTTc ctTc : ctRow.getTcArray()) {
            StringBuilder cellText = new StringBuilder();
            for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp : ctTc.getPArray()) {
                for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR ctr : ctp.getRArray()) {
                    for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText t : ctr.getTArray()) {
                        if (t.getStringValue() != null) cellText.append(t.getStringValue());
                    }
                }
            }
            fields.add(extractColFieldName(cellText.toString()));
        }
        return fields;
    }

    /** 向 CTRow 中按字段名填充数据（CT层，不依赖 POI 缓存） */
    private void fillCtRowWithData(CTRow ctRow, List<String> colFields, Map<String, Object> dataMap) {
        CTTc[] cells = ctRow.getTcArray();
        int limit = Math.min(cells.length, colFields.size());
        for (int ci = 0; ci < limit; ci++) {
            String fieldName = colFields.get(ci);
            String val = "";
            if (fieldName != null && dataMap.containsKey(fieldName)) {
                Object v = dataMap.get(fieldName);
                val = v != null ? v.toString() : "";
            }
            setCtCellText(cells[ci], val);
        }
    }

    /** 向 CTTc 写入文本（保留第一个 run 的格式和段落属性，清除占位符内容） */
    private void setCtCellText(CTTc ctTc, String value) {
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP[] paras = ctTc.getPArray();
        
        for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp : paras) {
            // 保存段落的属性（PPr）
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr pPr = ctp.getPPr();
            
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR[] runs = ctp.getRArray();
            if (runs.length == 0) {
                // 没有 run，新建一个
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR newRun = ctp.addNewR();
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText newT = newRun.addNewT();
                newT.setStringValue(value);
                continue;
            }
            
            // 保留第一个 run，设置文本值
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR firstRun = runs[0];
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText[] texts = firstRun.getTArray();
            if (texts.length > 0) {
                texts[0].setStringValue(value);
                // 清除同一 run 内其余 t
                for (int ti = 1; ti < texts.length; ti++) texts[ti].setStringValue("");
            } else {
                // run 无 t 节点，新建一个
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText newT = firstRun.addNewT();
                newT.setStringValue(value);
            }
            
            // 删除多余的 run（从后往前删，避免索引变化）
            for (int ri = runs.length - 1; ri > 0; ri--) {
                ctp.removeR(ri);
            }
            
            // 确保段落属性被保留（如果之前没有，尝试设置默认的垂直对齐）
            if (pPr != null && ctp.getPPr() == null) {
                ctp.setPPr(pPr);
            }
        }
        
        // 确保单元格的垂直对齐属性被保留
        // CTTcPr 包含 vAlign 属性，控制单元格内容的垂直对齐
        if (ctTc.getTcPr() != null) {
            // 单元格属性已存在，保留它
            // vAlign 属性控制垂直对齐：top, center, bottom
        }
    }

    /**
     * 在指定位置插入新行（使用 CTTbl trArray 操作，避免 XmlValueDisconnectedException）
     */
    private void insertRowAt(XWPFTable table, CTRow newCt, int pos) {
        CTTbl ctTbl = table.getCTTbl();
        int size = ctTbl.sizeOfTrArray();
        // 先追加一个空行占位，再逐行后移腾出 pos 位置
        ctTbl.addNewTr();
        for (int i = size; i > pos; i--) {
            ctTbl.setTrArray(i, ctTbl.getTrArray(i - 1));
        }
        ctTbl.setTrArray(pos, newCt);
    }

    /**
     * 删除指定位置的行（使用 CTTbl removeTr 操作，避免 XmlValueDisconnectedException）
     */
    private void removeRowAt(XWPFTable table, int pos) {
        table.getCTTbl().removeTr(pos);
    }

    /**
     * 用数据填充一行
     */
    private void fillRowWithData(XWPFTableRow row, List<String> colFields, Map<String, Object> dataMap) {
        List<XWPFTableCell> cells = row.getTableCells();
        int colLimit = Math.min(cells.size(), colFields.size());
        for (int ci = 0; ci < colLimit; ci++) {
            String fieldName = colFields.get(ci);
            String cellVal;
            if (fieldName != null && dataMap.containsKey(fieldName)) {
                Object val = dataMap.get(fieldName);
                cellVal = val != null ? val.toString() : "";
            } else {
                cellVal = "";
            }
            setCellText(cells.get(ci), cellVal);
        }
    }

    /**
     * 从 cell 文本中提取 {{_col_字段名}} 的字段名部分。
     * 例如 "{{_tpl_xxx}} {{_col_供应商名称}}" → "供应商名称"
     * 若无 {{_col_}} 标记，返回 null。
     */
    private String extractColFieldName(String cellText) {
        if (cellText == null) return null;
        int start = cellText.indexOf("{{_col_");
        if (start < 0) return null;
        int end = cellText.indexOf("}}", start + 7);
        if (end < 0) return null;
        return cellText.substring(start + 7, end).trim();
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

    /**
     * 判断指定占位符是否具有 columnDefs（即是否为 TABLE_ROW_TEMPLATE 类型）。
     * 从注册表查询，有 columnDefs 且非空返回 true，否则返回 false（TABLE_CLEAR_FULL 等类型）。
     * 与 getColumnDefsForPlaceholder 的区别：本方法无居底逻辑，仅用于类型判断。
     */
    private boolean hasColumnDefs(String placeholderName, String companyId) {
        if (placeholderRegistryService != null) {
            try {
                List<com.fileproc.report.service.ReverseTemplateEngine.RegistryEntry> registry =
                        placeholderRegistryService.getEffectiveRegistry(companyId);
                for (com.fileproc.report.service.ReverseTemplateEngine.RegistryEntry entry : registry) {
                    if (placeholderName.equals(entry.getDisplayName())
                            && entry.getColumnDefs() != null
                            && !entry.getColumnDefs().isEmpty()) {
                        return true;
                    }
                }
            } catch (Exception e) {
                log.warn("[ReportEngine] hasColumnDefs 查询失败: {}", e.getMessage());
            }
        }
        return false;
    }
    
    /**
     * 获取指定占位符的 column_defs（企业级优先，系统级居底，最终默认 ["#","COMPANY"]）。
     * 从 PlaceholderRegistryService 获取有效注册表，找到对应条目的 columnDefs。
     * 注意：占位符名（ph.getName()）存的是展示名，应按 displayName 匹配。
     *
     * @param placeholderName 占位符展示名（如“关联交易汇总”）
     * @param companyId       企业ID（null 时只查系统级）
     * @return 有效的 column_defs 列表
     */
    private List<String> getColumnDefsForPlaceholder(String placeholderName, String companyId) {
        // 从注册表服务获取企业级/系统级 columnDefs
        if (placeholderRegistryService != null) {
            try {
                List<com.fileproc.report.service.ReverseTemplateEngine.RegistryEntry> registry =
                        placeholderRegistryService.getEffectiveRegistry(companyId);
                for (com.fileproc.report.service.ReverseTemplateEngine.RegistryEntry entry : registry) {
                    // 按 displayName 匹配（ph.getName() 存的是展示名）
                    if (placeholderName.equals(entry.getDisplayName())
                            && entry.getColumnDefs() != null
                            && !entry.getColumnDefs().isEmpty()) {
                        log.debug("[ReportEngine] 占位符 '{}' columnDefs 来自注册表: {}", placeholderName, entry.getColumnDefs());
                        return entry.getColumnDefs();
                    }
                }
            } catch (Exception e) {
                log.warn("[ReportEngine] 读取注册表 columnDefs 失败，使用默认值: {}", e.getMessage());
            }
        }
        // 居底：系统默认只填 # 和 COMPANY
        return List.of("#", "COMPANY");
    }

    /**
     * 从 SummaryYear sheet 按 columnDefs 动态提取可比公司数据行。
     * <p>
     * 规则：
     * <ul>
     *   <li>跳过 row[0]（表头行）</li>
     *   <li>遍历到 D列（index=3）含 MIN/LQ/MED/UQ/MAX 任一关键词行时停止</li>
     *   <li>过滤 B列（index=1）为空的完全空行</li>
     *   <li>按 columnDefs 中 fieldKey→colIndex 映射（依据 BVD_COLUMN_KEYWORD_MAP 反查）提取数据</li>
     * </ul>
     * </p>
     *
     * @param rows       SummaryYear sheet 全部行数据（EasyExcel 读取，无表头模式）
     * @param columnDefs 需要提取的字段名列表，如 ["#","COMPANY","NCP_CURRENT"]
     * @return 数据行列表，每行为字段名→值的 Map，含 _rowType=data
     */
    private List<Map<String, Object>> extractSummaryYearRowData(
            List<Map<Integer, Object>> rows, List<String> columnDefs) {

        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        if (columnDefs == null || columnDefs.isEmpty()) {
            columnDefs = List.of("#", "COMPANY");
        }

        // 构建 fieldKey → 列索引 映射，依据 SummaryYear 表头行（row[0]）动态解析
        // 如果表头行能匹配 BVD_COLUMN_KEYWORD_MAP，使用动态列索引；否则使用内置默认索引
        Map<String, Integer> fieldToColIndex = buildSummaryYearFieldColMap(rows.get(0));
        log.debug("[ReportEngine-SummaryYear] fieldKey→colIndex映射: {}", fieldToColIndex);

        // 五分位关键词（D列出现这些关键词时停止读取）
        final Set<String> STOP_KEYWORDS = Set.of("MIN", "LQ", "MED", "UQ", "MAX");

        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = 1; i < rows.size(); i++) { // 跳过第0行表头
            Map<Integer, Object> row = rows.get(i);

            // D列（index=3）含停止关键词时结束
            Object dCell = row.get(3);
            if (dCell != null) {
                String dStr = dCell.toString().trim().toUpperCase();
                if (STOP_KEYWORDS.stream().anyMatch(dStr::contains)) {
                    log.debug("[ReportEngine-SummaryYear] 在行{}遇到停止关键词 '{}'，结束读取", i, dStr);
                    break;
                }
            }

            // 过滤 B列（index=1）为空的完全空行
            Object bCell = row.get(1);
            if (bCell == null || bCell.toString().trim().isEmpty()) continue;

            // 按 columnDefs 提取字段值
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (String fieldKey : columnDefs) {
                if (fieldKey == null) continue; // inferredColDefs 中可能有 null 占位
                Integer colIdx = fieldToColIndex.get(fieldKey);
                if (colIdx != null) {
                    Object val = row.get(colIdx);
                    rowMap.put(fieldKey, toPlainString(val));
                } else {
                    rowMap.put(fieldKey, "");
                }
            }
            rowMap.put("_rowType", "data");
            result.add(rowMap);
        }

        log.debug("[ReportEngine-SummaryYear] 提取完成，共 {} 行可比公司数据", result.size());
        return result;
    }

    /**
     * 解析 SummaryYear 表头行（row[0]），按 BVD_COLUMN_KEYWORD_MAP 构建 fieldKey→列索引 映射。
     * 若表头行无法识别，则返回内置默认映射（#→0, COMPANY→1, FY2023_STATUS→2 等）。
     */
    private Map<String, Integer> buildSummaryYearFieldColMap(Map<Integer, Object> headerRow) {
        // 尝试动态解析
        Map<String, Integer> result = new LinkedHashMap<>();
        boolean anyMatched = false;

        if (headerRow != null) {
            for (Map.Entry<Integer, Object> entry : headerRow.entrySet()) {
                int colIdx = entry.getKey();
                Object cellVal = entry.getValue();
                if (cellVal == null) continue;
                String lower = cellVal.toString().trim().toLowerCase();
                for (Map.Entry<String, String> kv : ReverseTemplateEngine.BVD_COLUMN_KEYWORD_MAP.entrySet()) {
                    if (lower.contains(kv.getKey())) {
                        result.put(kv.getValue(), colIdx);
                        anyMatched = true;
                        break;
                    }
                }
            }
        }

        if (!anyMatched) {
            // 内置默认映射（基于已知 SummaryYear 表结构）
            result.put("#",             0);
            result.put("COMPANY",       1);
            result.put("FY2023_STATUS", 2);
            result.put("FY2022_STATUS", 3);
            result.put("NCP_CURRENT",   4);
            result.put("NCP_PRIOR",     5);
            result.put("Remarks",       6);
            result.put("Sales",         7);
            result.put("CoGS",          8);
            result.put("SGA",           9);
            result.put("Depreciation",  10);
            result.put("OP",            11);
            log.debug("[ReportEngine-SummaryYear] 表头识别失败，使用内置默认列索引映射");
        }
        return result;
    }
}
