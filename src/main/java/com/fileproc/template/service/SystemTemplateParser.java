package com.fileproc.template.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.metadata.data.ReadCellData;
import com.alibaba.excel.read.metadata.ReadSheet;
import com.fileproc.template.entity.SystemModule;
import com.fileproc.template.entity.SystemPlaceholder;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 系统标准模板解析器
 * <p>
 * 负责：
 * 1. 扫描标准Word模板，提取所有 {{数据源-Sheet名-单元格}} 格式的占位符
 * 2. 扫描Excel模板的Sheet结构（列头映射）
 * 3. 按占位符数据源前缀分组生成模块列表
 * </p>
 */
@Slf4j
@Component
public class SystemTemplateParser {

    /**
     * 占位符正则：匹配 {{清单模板-数据表-B3}} 格式
     * 三段式：数据源-Sheet名-单元格地址
     */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{([^}]+)\\}\\}");

    /**
     * 占位符名称拆分正则：名称-Sheet-单元格
     * 单元格格式：字母(列)+数字(行)，如 B3、AA10
     */
    private static final Pattern PLACEHOLDER_NAME_PATTERN =
            Pattern.compile("^([^-]+)-([^-]+)-([A-Za-z]+\\d+)$");

    /**
     * 解析Word模板，提取所有占位符规则
     *
     * @param wordFilePath Word模板绝对路径
     * @param systemTemplateId 系统模板ID
     * @return 解析出的占位符列表
     */
    public List<SystemPlaceholder> parseWordTemplate(String wordFilePath, String systemTemplateId) {
        List<SystemPlaceholder> result = new ArrayList<>();
        // 去重：同一占位符名只保留一条（Word中可能多处出现）
        Set<String> seenNames = new LinkedHashSet<>();

        try (FileInputStream fis = new FileInputStream(wordFilePath);
             XWPFDocument document = new XWPFDocument(fis)) {

            int sortIndex = 0;

            // 1. 扫描段落（正文文本）
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String fullText = paragraph.getText();
                sortIndex = extractPlaceholders(fullText, systemTemplateId, "text",
                        seenNames, result, sortIndex);
            }

            // 2. 扫描表格单元格
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String cellText = cell.getText();
                        sortIndex = extractPlaceholders(cellText, systemTemplateId, "table",
                                seenNames, result, sortIndex);
                    }
                }
            }

            // 3. 扫描页眉页脚（兼容无页眉页脚的文档）
            List<XWPFHeader> headers = document.getHeaderList();
            if (headers != null) {
                for (XWPFHeader header : headers) {
                    if (header != null && header.getParagraphs() != null) {
                        for (XWPFParagraph p : header.getParagraphs()) {
                            sortIndex = extractPlaceholders(p.getText(), systemTemplateId, "text",
                                    seenNames, result, sortIndex);
                        }
                    }
                }
            }
            List<XWPFFooter> footers = document.getFooterList();
            if (footers != null) {
                for (XWPFFooter footer : footers) {
                    if (footer != null && footer.getParagraphs() != null) {
                        for (XWPFParagraph p : footer.getParagraphs()) {
                            sortIndex = extractPlaceholders(p.getText(), systemTemplateId, "text",
                                    seenNames, result, sortIndex);
                        }
                    }
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("解析Word模板失败: " + wordFilePath, e);
        }

        log.info("[SystemTemplateParser] 解析Word完成，共提取占位符 {} 个", result.size());
        return result;
    }

    /**
     * 从文本中提取占位符，构建 SystemPlaceholder 对象
     */
    private int extractPlaceholders(String text, String systemTemplateId, String defaultType,
                                     Set<String> seenNames, List<SystemPlaceholder> result, int sortIndex) {
        if (text == null || text.isBlank()) return sortIndex;

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            String placeholderName = matcher.group(1).trim();
            if (seenNames.contains(placeholderName)) continue;
            seenNames.add(placeholderName);

            SystemPlaceholder ph = buildPlaceholder(placeholderName, systemTemplateId, defaultType, sortIndex++);
            result.add(ph);
        }
        return sortIndex;
    }

    /**
     * 根据占位符名称构建 SystemPlaceholder 对象
     * 格式：数据源-Sheet名-单元格，如 清单模板-数据表-B3
     */
    private SystemPlaceholder buildPlaceholder(String name, String systemTemplateId,
                                                String defaultType, int sort) {
        SystemPlaceholder ph = new SystemPlaceholder();
        ph.setSystemTemplateId(systemTemplateId);
        ph.setName(name);
        ph.setDisplayName(name);
        ph.setSort(sort);
        ph.setDescription("");

        Matcher m = PLACEHOLDER_NAME_PATTERN.matcher(name);
        if (m.matches()) {
            String dataSourceRaw = m.group(1).trim();   // 如：清单模板
            String sheetName = m.group(2).trim();        // 如：数据表
            String cellAddress = m.group(3).trim();      // 如：B3

            ph.setSourceSheet(sheetName);
            ph.setSourceField(cellAddress);

            // 数据源映射
            if (dataSourceRaw.contains("清单")) {
                ph.setDataSource("list");
                ph.setModuleCode(sheetName);
            } else if (dataSourceRaw.contains("BVD") || dataSourceRaw.contains("bvd")) {
                ph.setDataSource("bvd");
                ph.setModuleCode(sheetName);
            } else {
                ph.setDataSource("list");
                ph.setModuleCode(dataSourceRaw);
            }
        } else {
            // 无法解析的格式，作为文本类型保留
            log.warn("[SystemTemplateParser] 无法解析占位符格式: {}", name);
            ph.setDataSource("list");
            ph.setModuleCode("unknown");
            ph.setSourceSheet("");
            ph.setSourceField("");
        }

        ph.setType(defaultType);
        return ph;
    }

    /**
     * 扫描Excel模板的Sheet结构，建立 sheet名 → 列头列表 的映射
     *
     * @param excelFilePath Excel文件绝对路径
     * @return Map<sheetName, List<columnHeader>>
     */
    public Map<String, List<String>> parseExcelTemplate(String excelFilePath) {
        Map<String, List<String>> sheetColumns = new LinkedHashMap<>();

        try {
            // 先获取所有 Sheet 名
            List<ReadSheet> sheets = EasyExcel.read(excelFilePath).build()
                    .excelExecutor().sheetList();

            for (ReadSheet sheet : sheets) {
                String sheetName = sheet.getSheetName();
                List<String> headers = new ArrayList<>();

                EasyExcel.read(excelFilePath, new AnalysisEventListener<Map<Integer, ReadCellData<?>>>() {
                    private boolean firstRow = true;

                    @Override
                    public void invoke(Map<Integer, ReadCellData<?>> rowData, AnalysisContext context) {
                        if (firstRow) {
                            firstRow = false;
                            // 第一行作为列头
                            rowData.entrySet().stream()
                                    .sorted(Map.Entry.comparingByKey())
                                    .forEach(entry -> {
                                        ReadCellData<?> cell = entry.getValue();
                                        String headerValue = cell.getStringValue() != null
                                                ? cell.getStringValue() : "";
                                        if (!headerValue.isBlank()) {
                                            headers.add(headerValue);
                                        }
                                    });
                        }
                    }

                    @Override
                    public void doAfterAllAnalysed(AnalysisContext context) {
                        // do nothing
                    }
                }).sheet(sheetName).headRowNumber(0).doRead();

                sheetColumns.put(sheetName, headers);
                log.debug("[SystemTemplateParser] Sheet={}, 列头数={}", sheetName, headers.size());
            }
        } catch (Exception e) {
            log.error("[SystemTemplateParser] 解析Excel模板失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析Excel模板失败: " + excelFilePath, e);
        }

        log.info("[SystemTemplateParser] 解析Excel完成，共 {} 个Sheet", sheetColumns.size());
        return sheetColumns;
    }

    /**
     * 根据占位符列表按 moduleCode 分组生成模块列表
     *
     * @param placeholders 占位符列表
     * @param systemTemplateId 系统模板ID
     * @return 模块列表（已按出现顺序去重）
     */
    public List<SystemModule> buildModules(List<SystemPlaceholder> placeholders, String systemTemplateId) {
        List<SystemModule> modules = new ArrayList<>();
        Map<String, Integer> seenModuleCodes = new LinkedHashMap<>();

        int sortIndex = 0;
        for (SystemPlaceholder ph : placeholders) {
            String code = ph.getModuleCode();
            if (code == null || code.isBlank()) continue;
            if (seenModuleCodes.containsKey(code)) continue;

            seenModuleCodes.put(code, sortIndex);

            SystemModule module = new SystemModule();
            module.setSystemTemplateId(systemTemplateId);
            module.setCode(code);
            module.setName(code);
            module.setDescription("");
            module.setSort(sortIndex++);
            modules.add(module);
        }

        log.info("[SystemTemplateParser] 生成模块 {} 个", modules.size());
        return modules;
    }
}
