package com.fileproc.datafile.service;

import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileproc.common.BizException;
import com.fileproc.common.TenantContext;
import com.fileproc.datafile.entity.DataFile;
import com.fileproc.datafile.entity.DataSourceSchema;
import com.fileproc.datafile.mapper.DataFileMapper;
import com.fileproc.datafile.mapper.DataSourceSchemaMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileInputStream;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 数据源 Schema 解析服务
 * <p>
 * 职责：解析 Excel 文件（清单/BVD）的 Sheet 结构，提取字段坐标、标签、推断类型和样本值，
 * 结果持久化到 data_source_schema 表，供前端可视化绑定占位符时使用。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceSchemaService {

    private final DataSourceSchemaMapper dataSourceSchemaMapper;
    private final DataFileMapper dataFileMapper;
    private final ObjectMapper objectMapper;

    // ========== DTO ==========

    /** 单个字段描述 */
    @Data
    public static class FieldInfo {
        /** 单元格地址，如 B1 */
        private String address;
        /** 字段标签（A列文本或首行文本） */
        private String label;
        /** 样本值（B列或第一数据行的值，截断到50字） */
        private String sampleValue;
        /** 推断类型：TEXT / NUMBER / LONG_TEXT */
        private String inferredType;
    }

    /** Sheet 树节点（供前端展示） */
    @Data
    public static class SheetNode {
        private String sheetName;
        private int sheetIndex;
        private List<FieldInfo> fields;
    }

    // ========== 核心接口 ==========

    /**
     * 解析指定 dataFile 的 Excel 结构，结果持久化到数据库。
     * 若已有解析结果，先删除再重新解析（支持手动刷新）。
     *
     * @param dataFileId data_file.id
     * @return 解析出的 SheetNode 列表
     */
    @Transactional
    public List<SheetNode> parseSchema(String dataFileId) {
        DataFile dataFile = dataFileMapper.selectById(dataFileId);
        if (dataFile == null) {
            throw BizException.notFound("数据文件");
        }

        String filePath = dataFileMapper.selectFilePathById(dataFileId);
        if (filePath == null) {
            throw BizException.of(400, "文件路径不存在，请重新上传");
        }

        // 先清旧数据
        dataSourceSchemaMapper.deleteByDataFileId(dataFileId);

        String tenantId = TenantContext.getTenantId();
        List<SheetNode> result = new ArrayList<>();

        // 读取所有 Sheet 名
        List<String> sheetNames = readSheetNames(filePath);
        log.info("[SchemaService] 文件[{}] 共{}个Sheet: {}", dataFileId, sheetNames.size(), sheetNames);

        for (int i = 0; i < sheetNames.size(); i++) {
            String sheetName = sheetNames.get(i);
            List<FieldInfo> fields = parseSheetFields(filePath, i, sheetName);

            // 持久化
            DataSourceSchema schema = new DataSourceSchema();
            schema.setDataFileId(dataFileId);
            schema.setTenantId(tenantId);
            schema.setSheetName(sheetName);
            schema.setSheetIndex(i);
            schema.setParsedAt(LocalDateTime.now());
            try {
                schema.setFields(objectMapper.writeValueAsString(fields));
            } catch (JsonProcessingException e) {
                schema.setFields("[]");
            }
            dataSourceSchemaMapper.insert(schema);

            // 构建返回节点
            SheetNode node = new SheetNode();
            node.setSheetName(sheetName);
            node.setSheetIndex(i);
            node.setFields(fields);
            result.add(node);
        }

        log.info("[SchemaService] 文件[{}] 解析完成，共{}个Sheet", dataFileId, result.size());
        return result;
    }

    /**
     * 查询已解析的 Schema（已有缓存则直接返回，未解析则返回空列表）
     *
     * @param dataFileId data_file.id
     * @return SheetNode 列表，若未解析返回空列表
     */
    public List<SheetNode> getSchemaTree(String dataFileId) {
        List<DataSourceSchema> schemas = dataSourceSchemaMapper.selectByDataFileId(dataFileId);
        if (schemas == null || schemas.isEmpty()) {
            return Collections.emptyList();
        }

        List<SheetNode> result = new ArrayList<>();
        for (DataSourceSchema schema : schemas) {
            SheetNode node = new SheetNode();
            node.setSheetName(schema.getSheetName());
            node.setSheetIndex(schema.getSheetIndex());
            try {
                List<FieldInfo> fields = objectMapper.readValue(
                        schema.getFields() != null ? schema.getFields() : "[]",
                        objectMapper.getTypeFactory().constructCollectionType(List.class, FieldInfo.class)
                );
                node.setFields(fields);
            } catch (Exception e) {
                node.setFields(Collections.emptyList());
            }
            result.add(node);
        }
        return result;
    }

    /**
     * 检查指定文件是否已有解析结果
     */
    public boolean hasSchema(String dataFileId) {
        return dataSourceSchemaMapper.countByDataFileId(dataFileId) > 0;
    }

    // ========== 内部工具方法 ==========

    /**
     * 解析单个 Sheet 的字段列表
     * <p>
     * 解析策略：
     * 1. 若 A 列有文本、B 列有值（A-B 键值对结构，如"数据表"/"行业情况"）
     *    → 每行 A 列作为 label，B 列坐标作为 address，B 列值作为 sampleValue
     * 2. 若首行为表头（多列结构，如"供应商清单"/"PL"）
     *    → 首行各列标题作为 label，按列索引生成坐标，第2行作为 sampleValue
     * 3. 其他情况：扫描前20行，取非空单元格
     * </p>
     */
    private List<FieldInfo> parseSheetFields(String filePath, int sheetIndex, String sheetName) {
        try {
            List<Map<Integer, Object>> rows = readSheetByIndex(filePath, sheetIndex);
            if (rows == null || rows.isEmpty()) {
                return Collections.emptyList();
            }

            // 判断是否为 A-B 键值对结构（A列有标签，B列有值）
            if (isKeyValueStructure(rows)) {
                return parseKeyValueSheet(rows);
            } else {
                return parseTableSheet(rows);
            }
        } catch (Exception e) {
            log.warn("[SchemaService] Sheet[{}][{}] 解析失败: {}", sheetIndex, sheetName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 判断是否为 A-B 键值对结构（前5行中，A列非空且B列非空的行数 >= 3）
     */
    private boolean isKeyValueStructure(List<Map<Integer, Object>> rows) {
        int kvCount = 0;
        int checkRows = Math.min(rows.size(), 10);
        for (int i = 0; i < checkRows; i++) {
            Map<Integer, Object> row = rows.get(i);
            Object aVal = row.get(0);
            Object bVal = row.get(1);
            if (aVal != null && !aVal.toString().trim().isEmpty()
                    && bVal != null && !bVal.toString().trim().isEmpty()) {
                kvCount++;
            }
        }
        return kvCount >= 3;
    }

    /**
     * 解析 A-B 键值对 Sheet（如清单的"数据表"、"行业情况"）
     */
    private List<FieldInfo> parseKeyValueSheet(List<Map<Integer, Object>> rows) {
        List<FieldInfo> fields = new ArrayList<>();
        for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
            Map<Integer, Object> row = rows.get(rowIdx);
            Object aVal = row.get(0);
            Object bVal = row.get(1);
            if (aVal == null || aVal.toString().trim().isEmpty()) continue;

            String label = aVal.toString().trim();
            String address = "B" + (rowIdx + 1);
            String sampleValue = bVal != null ? truncate(bVal.toString(), 50) : "";
            String inferredType = inferType(bVal != null ? bVal.toString() : "", label);

            FieldInfo fi = new FieldInfo();
            fi.setAddress(address);
            fi.setLabel(label);
            fi.setSampleValue(sampleValue);
            fi.setInferredType(inferredType);
            fields.add(fi);
        }
        return fields;
    }

    /**
     * 解析表格结构 Sheet（首行为表头，按列）
     */
    private List<FieldInfo> parseTableSheet(List<Map<Integer, Object>> rows) {
        List<FieldInfo> fields = new ArrayList<>();
        if (rows.isEmpty()) return fields;

        Map<Integer, Object> headerRow = rows.get(0);
        Map<Integer, Object> dataRow = rows.size() > 1 ? rows.get(1) : Collections.emptyMap();

        for (Map.Entry<Integer, Object> entry : headerRow.entrySet()) {
            int colIdx = entry.getKey();
            Object headerVal = entry.getValue();
            if (headerVal == null || headerVal.toString().trim().isEmpty()) continue;

            String label = headerVal.toString().trim();
            String address = colIndexToLetter(colIdx) + "1";
            Object sampleVal = dataRow.get(colIdx);
            String sampleValue = sampleVal != null ? truncate(sampleVal.toString(), 50) : "";
            String inferredType = inferType(sampleValue, label);

            FieldInfo fi = new FieldInfo();
            fi.setAddress(address);
            fi.setLabel(label);
            fi.setSampleValue(sampleValue);
            fi.setInferredType(inferredType);
            fields.add(fi);
        }
        return fields;
    }

    /**
     * 推断字段类型
     */
    private String inferType(String value, String label) {
        if (value == null || value.isEmpty()) return "TEXT";
        // 长文本
        if (value.length() > 50) return "LONG_TEXT";
        // 数字
        try {
            Double.parseDouble(value.replace(",", "").replace("%", "").trim());
            return "NUMBER";
        } catch (NumberFormatException ignore) {
        }
        return "TEXT";
    }

    /**
     * 列索引转Excel字母（0→A, 25→Z, 26→AA...）
     */
    private String colIndexToLetter(int colIndex) {
        StringBuilder sb = new StringBuilder();
        colIndex++;
        while (colIndex > 0) {
            colIndex--;
            sb.insert(0, (char) ('A' + colIndex % 26));
            colIndex /= 26;
        }
        return sb.toString();
    }

    /**
     * 截断字符串
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 读取 Excel 所有 Sheet 名称（复用 ReverseTemplateEngine 的相同逻辑）
     */
    private List<String> readSheetNames(String filePath) {
        try (FileInputStream fis = new FileInputStream(filePath)) {
            XSSFWorkbook wb = new XSSFWorkbook(fis);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                names.add(wb.getSheetName(i));
            }
            wb.close();
            return names;
        } catch (Exception e) {
            log.error("[SchemaService] 读取Sheet名称失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按 Sheet 索引读取所有行（无表头模式，复用 ReverseTemplateEngine 的相同逻辑）
     */
    private List<Map<Integer, Object>> readSheetByIndex(String filePath, int sheetIndex) {
        try {
            return EasyExcel.read(filePath).sheet(sheetIndex).headRowNumber(0).doReadSync();
        } catch (Exception e) {
            log.warn("[SchemaService] 读取Sheet[{}]失败: {}", sheetIndex, e.getMessage());
            return Collections.emptyList();
        }
    }
}
