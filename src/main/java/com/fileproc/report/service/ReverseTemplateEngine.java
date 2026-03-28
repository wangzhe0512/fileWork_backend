package com.fileproc.report.service;

import com.alibaba.excel.EasyExcel;
import com.fileproc.common.BizException;
import com.fileproc.template.entity.SystemPlaceholder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;
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

    // ========== 静态占位符注册表 ==========

    /**
     * 占位符类型枚举（静态注册表驱动引擎的核心分类）
     */
    private enum PlaceholderType {
        /** 数据表单元格：按坐标读取单值，按长度/词边界正则替换 */
        DATA_CELL,
        /** 整表/区域清空（财务表专用）：不读值，识别Word中对应表格后仅清空数字列，写入占位符标记 */
        TABLE_CLEAR,
        /** 整表全清空（非财务整表）：不读值，识别Word中对应表格后整张表全部清空，仅第一格写入占位符标记 */
        TABLE_CLEAR_FULL,
        /** 行模板克隆（动态行数表）：逆向时保留表头行+1行列占位符模板行（以{{_tpl_}}标记），生成时克隆该行N次填数据 */
        TABLE_ROW_TEMPLATE,
        /** 长文本：按坐标读值，在Word中做整段精确字符串替换 */
        LONG_TEXT,
        /** BVD数据：按坐标读值，全部标 uncertain 由用户确认 */
        BVD
    }

    /**
     * 静态占位符注册表条目
     * <p>
     * 每条记录对应标准模板中一个 {{占位符}} 的完整定义，包含：
     * 占位符名称、可读展示名、类型、数据来源（list/bvd）、SheetName、单元格坐标。
     * TABLE_CLEAR 类型无 cellAddress（整块清空，不需坐标读值）。
     * </p>
     */
    @Data
    static class RegistryEntry {
        /** 占位符标准名（对应 Word 模板中 {{...}} 内的完整名称） */
        String placeholderName;
        /** 可读展示名（用于日志/前端提示，如"企业全称"） */
        String displayName;
        /** 占位符类型 */
        PlaceholderType type;
        /** 数据来源：list（清单Excel）或 bvd（BVD Excel） */
        String dataSource;
        /** Excel Sheet 名（TABLE_CLEAR 类型可为 null） */
        String sheetName;
        /** 单元格坐标（如 B1），TABLE_CLEAR 类型为 null */
        String cellAddress;
        /**
         * TABLE_CLEAR 专用：该占位符对应 Word 表格的前置标题关键词列表。
         * clearTableBlock 阶段通过表格前最近段落文本 contains 任一关键词来精确绑定表格。
         * DATA_CELL / LONG_TEXT / BVD 类型为 null。
         */
        List<String> titleKeywords;

        /**
         * TABLE_ROW_TEMPLATE 专用：列字段名定义列表，index 对应 Word 表格列索引。
         * 反向生成时每列写入 {{_col_字段名}}；生成时按字段名从 Excel 行 Map 取值。
         * 其他类型为 null。
         */
        List<String> columnDefs;

        /** 构造方法（原6参数，DATA_CELL/LONG_TEXT/BVD 使用，titleKeywords=null） */
        RegistryEntry(String placeholderName, String displayName, PlaceholderType type,
                      String dataSource, String sheetName, String cellAddress) {
            this.placeholderName = placeholderName;
            this.displayName = displayName;
            this.type = type;
            this.dataSource = dataSource;
            this.sheetName = sheetName;
            this.cellAddress = cellAddress;
            this.titleKeywords = null;
            this.columnDefs = null;
        }

        /** 构造方法（7参数，TABLE_CLEAR 使用，额外传入前置标题关键词列表） */
        RegistryEntry(String placeholderName, String displayName, PlaceholderType type,
                      String dataSource, String sheetName, String cellAddress,
                      List<String> titleKeywords) {
            this.placeholderName = placeholderName;
            this.displayName = displayName;
            this.type = type;
            this.dataSource = dataSource;
            this.sheetName = sheetName;
            this.cellAddress = cellAddress;
            this.titleKeywords = titleKeywords;
            this.columnDefs = null;
        }

        /** 构造方法（8参数，TABLE_ROW_TEMPLATE 使用，额外传入列字段名列表） */
        RegistryEntry(String placeholderName, String displayName, PlaceholderType type,
                      String dataSource, String sheetName, String cellAddress,
                      List<String> titleKeywords, List<String> columnDefs) {
            this.placeholderName = placeholderName;
            this.displayName = displayName;
            this.type = type;
            this.dataSource = dataSource;
            this.sheetName = sheetName;
            this.cellAddress = cellAddress;
            this.titleKeywords = titleKeywords;
            this.columnDefs = columnDefs;
        }
    }

    /**
     * 静态占位符注册表 —— 内置全部标准模板占位符定义（约40条）
     *
     * <p>维护原则：
     * <ul>
     *   <li>新增占位符只在此处添加一行 RegistryEntry，不改替换逻辑</li>
     *   <li>Sheet 名与实际清单 Excel 中的 Sheet 名保持一致（含 trim/忽略大小写容错）</li>
     *   <li>TABLE_CLEAR 类型的 sheetName/cellAddress 均填 null</li>
     * </ul>
     */
    private static final List<RegistryEntry> PLACEHOLDER_REGISTRY;

    static {
        List<RegistryEntry> reg = new ArrayList<>();

        // ===== 第一类：数据表单元格占位符（清单.xlsx → 数据表 Sheet B1~B8） =====
        // 来源：标准模板"数据表" Sheet，A列=字段名，B列=值
        reg.add(new RegistryEntry("清单模板-数据表-B1", "企业全称",   PlaceholderType.DATA_CELL, "list", "数据表", "B1"));
        reg.add(new RegistryEntry("清单模板-数据表-B2", "年度",       PlaceholderType.DATA_CELL, "list", "数据表", "B2"));
        reg.add(new RegistryEntry("清单模板-数据表-B3", "事务所名称", PlaceholderType.DATA_CELL, "list", "数据表", "B3"));
        reg.add(new RegistryEntry("清单模板-数据表-B4", "事务所简称", PlaceholderType.DATA_CELL, "list", "数据表", "B4"));
        reg.add(new RegistryEntry("清单模板-数据表-B5", "企业简称",   PlaceholderType.DATA_CELL, "list", "数据表", "B5"));
        reg.add(new RegistryEntry("清单模板-数据表-B6", "母公司全称", PlaceholderType.DATA_CELL, "list", "数据表", "B6"));
        reg.add(new RegistryEntry("清单模板-数据表-B7", "集团简介",   PlaceholderType.LONG_TEXT, "list", "数据表", "B7"));
        reg.add(new RegistryEntry("清单模板-数据表-B8", "公司概况",   PlaceholderType.LONG_TEXT, "list", "数据表", "B8"));

        // ===== 第二类：整表/区域占位符，分两种策略 =====
        // TABLE_CLEAR_FULL（财务状况表整表展开）：整张表全部清空，仅第一格写占位符，生成时从 PL Sheet 逐行展开三列数据
        reg.add(new RegistryEntry("清单模板-PL",                   "PL全表",       PlaceholderType.TABLE_CLEAR_FULL, "list", "PL", null,
                List.of("财务状况", "财务数据")));
        reg.add(new RegistryEntry("清单模板-PL含特殊因素调整",      "PL含特殊因素", PlaceholderType.TABLE_CLEAR_FULL, "list", "PL含特殊因素调整", null,
                List.of("含特殊", "特殊因素调整")));

        // TABLE_CLEAR_FULL（非财务整表）：整张表全部清空，仅第一格写占位符
        reg.add(new RegistryEntry("清单模板-1_组织结构及管理架构",  "组织结构",   PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("组织结构", "部门结构", "管理架构", "组织架构")));
        reg.add(new RegistryEntry("清单模板-主要产品-A列中所列所有产品", "主要产品", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("主要产品", "产品清单", "产品信息", "产品列表")));
        reg.add(new RegistryEntry("清单模板-2_关联公司信息",        "关联公司信息", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("关联公司", "关联方公司", "关联企业")));
        reg.add(new RegistryEntry("清单模板-关联方个人信息",        "关联方个人信息", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("关联方个人", "关联个人", "个人信息")));
        reg.add(new RegistryEntry("清单模板-关联关系变化情况",      "关联关系变化", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("关联关系变化", "关联变化", "关系变化")));
        reg.add(new RegistryEntry("清单模板-关联交易汇总表",        "关联交易汇总", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("关联交易汇总", "关联交易总", "关联交易合计")));
        // TABLE_ROW_TEMPLATE：行模板克隆（动态行数明细表），注意需在 客户清单/供应商清单 之前注册，防止关键词抢先匹配
        reg.add(new RegistryEntry("清单模板-4_供应商关联采购明细", "关联采购明细",
                PlaceholderType.TABLE_ROW_TEMPLATE, "list", "4 供应商清单", null,
                List.of("关联采购交易明细", "关联采购明细表", "采购交易明细表"),
                List.of("供应商名称", "金额（人民币）", "占关联采购总金额比例")));
        reg.add(new RegistryEntry("清单模板-5_客户关联销售明细", "关联销售明细",
                PlaceholderType.TABLE_ROW_TEMPLATE, "list", "5 客户清单", null,
                List.of("关联销售交易明细", "关联销售明细表", "销售交易明细表"),
                List.of("客户名称", "金额（人民币）", "占营业收入比例")));
        reg.add(new RegistryEntry("清单模板-5_客户清单",            "客户清单",   PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("客户清单", "主要客户", "前五大客户", "主要客户情况")));
        reg.add(new RegistryEntry("清单模板-4_供应商清单",          "供应商清单", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("供应商清单", "主要供应商", "前五大供应商", "供应商情况")));
        // TABLE_ROW_TEMPLATE：劳务支出/收入明细表（动态行数，共用 "6 劳务交易表" Sheet，按行次范围区分）
        // 注意：需在下方 TABLE_CLEAR_FULL 劳务成本条目之前注册，防止关键词被后者抢先匹配
        reg.add(new RegistryEntry("清单模板-6_劳务支出明细", "接受关联劳务明细",
                PlaceholderType.TABLE_ROW_TEMPLATE, "list", "6 劳务交易表", null,
                List.of("接受关联劳务明细", "关联劳务支出明细", "劳务支出明细表"),
                List.of("关联方名称", "交易金额", "占总经营成本费用比重（%）")));
        reg.add(new RegistryEntry("清单模板-6_劳务收入明细", "提供关联劳务明细",
                PlaceholderType.TABLE_ROW_TEMPLATE, "list", "6 劳务交易表", null,
                List.of("提供关联劳务明细", "关联劳务收入明细", "劳务收入明细表"),
                List.of("关联方名称", "交易金额", "占营业收入比重（%）")));
        reg.add(new RegistryEntry("清单模板-劳务成本费用归集",      "劳务成本费用", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("劳务成本", "费用归集", "成本归集")));
        reg.add(new RegistryEntry("清单模板-资金融通",              "资金融通",   PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("资金融通", "资金借贷", "融通")));
        reg.add(new RegistryEntry("清单模板-有形资产信息",          "有形资产信息", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("有形资产", "固定资产", "资产信息")));
        reg.add(new RegistryEntry("清单模板-功能风险汇总表",        "功能风险汇总", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("功能风险", "功能分析", "风险汇总", "功能与风险")));
        reg.add(new RegistryEntry("清单模板-3_分部财务数据",        "分部财务数据", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("分部财务", "财务数据", "分部数据")));
        reg.add(new RegistryEntry("清单模板-公司经营背景资料",      "公司经营背景", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("经营背景", "公司背景", "背景资料", "经营情况")));
        reg.add(new RegistryEntry("清单数据模板-公司间资金融通交易总结", "公司间资金融通", PlaceholderType.TABLE_CLEAR_FULL, "list", null, null,
                List.of("公司间资金", "资金融通交易总结", "公司间融通", "资金融通总结")));

        // ===== 第三类：行业情况长文本占位符（清单 → 行业情况 Sheet B1~B5） =====
        // 来源：标准模板"行业情况" Sheet，B1~B5 各为一段长文本
        reg.add(new RegistryEntry("清单模板-行业情况-B1", "行业情况B1", PlaceholderType.LONG_TEXT, "list", "行业情况", "B1"));
        reg.add(new RegistryEntry("清单模板-行业情况-B2", "行业情况B2", PlaceholderType.LONG_TEXT, "list", "行业情况", "B2"));
        reg.add(new RegistryEntry("清单模板-行业情况-B3", "行业情况B3", PlaceholderType.LONG_TEXT, "list", "行业情况", "B3"));
        reg.add(new RegistryEntry("清单模板-行业情况-B4", "行业情况B4", PlaceholderType.LONG_TEXT, "list", "行业情况", "B4"));
        reg.add(new RegistryEntry("清单模板-行业情况-B5", "行业情况B5", PlaceholderType.LONG_TEXT, "list", "行业情况", "B5"));

        // ===== 第四类：BVD 数据占位符（BVD Excel 按坐标读取，全部标 uncertain） =====
        // 来源：BVD Excel 各 Sheet
        // 数据表 B1~B4：独立交易区间表4行数据（B1=可比公司数量, B2=上四分位值, B3=中位值, B4=下四分位值）
        reg.add(new RegistryEntry("BVD数据模板-数据表-B1",                "BVD-可比公司数量",    PlaceholderType.BVD, "bvd", "数据表", "B1"));
        reg.add(new RegistryEntry("BVD数据模板-数据表-B2",                "BVD-上四分位值",      PlaceholderType.BVD, "bvd", "数据表", "B2"));
        reg.add(new RegistryEntry("BVD数据模板-数据表-B3",                "BVD-中位值",          PlaceholderType.BVD, "bvd", "数据表", "B3"));
        reg.add(new RegistryEntry("BVD数据模板-数据表-B4",                "BVD-下四分位值",      PlaceholderType.BVD, "bvd", "数据表", "B4"));
        reg.add(new RegistryEntry("BVD数据模板-AP_YEAR",                 "BVD-AP年度",        PlaceholderType.BVD, "bvd", "AP",     "A1"));
        reg.add(new RegistryEntry("BVD数据模板-AP_Lead_Sheet_YEAR-13-19", "BVD-AP Lead 13~19行", PlaceholderType.BVD, "bvd", "AP Lead Sheet", "A13"));
        reg.add(new RegistryEntry("BVD数据模板-SummaryYear-第一张表格",   "BVD-SummaryYear表1", PlaceholderType.BVD, "bvd", "SummaryYear", "A1"));
        reg.add(new RegistryEntry("BVD数据模板-SummaryYear-第二张表格",   "BVD-SummaryYear表2", PlaceholderType.BVD, "bvd", "SummaryYear", "A20"));

        PLACEHOLDER_REGISTRY = Collections.unmodifiableList(reg);
    }

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
        /**
         * 注册表中有定义但未能自动处理的占位符列表（TABLE_CLEAR未定位 + LONG_TEXT未匹配等）。
         * 前端可据此精确提示用户："以下占位符未能自动替换，请手动处理"。
         */
        private List<RegistryEntry> unmatchedRegistryEntries;
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
        /**
         * 占位符类型（由注册表驱动，用于 replaceInRunsNew 分支路由）：
         * DATA_CELL / TABLE_CLEAR / LONG_TEXT / BVD
         * 旧动态扫描路径构建的条目保持 null，兼容旧逻辑。
         */
        private PlaceholderType placeholderType;
        /** 可读展示名（用于日志/前端提示，如"企业全称"），来自注册表 displayName */
        private String displayName;
        /**
         * TABLE_CLEAR 专用：前置标题关键词列表，来自注册表 RegistryEntry.titleKeywords。
         * clearTableBlock 阶段用于精确匹配表格前置标题段落，其余类型为 null。
         */
        private List<String> titleKeywords;
        /**
         * TABLE_ROW_TEMPLATE 专用：列字段名定义列表，来自注册表 RegistryEntry.columnDefs。
         * 反向生成时每列写入 {{_col_字段名}}；生成时按字段名从 Excel 行 Map 取值。
         * 其他类型为 null。
         */
        private List<String> columnDefs;
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
     * 执行反向生成（注册表驱动引擎：静态占位符映射表 + 坐标精确读值）
     *
     * <p>处理策略：
     * <ol>
     *   <li>第0层：LONG_TEXT 条目（行业情况/集团背景等），整段精确匹配，先于数据表执行</li>
     *   <li>第1层：DATA_CELL 条目（数据表字段），按注册表坐标读值，词边界/精确替换</li>
     *   <li>第2层：TABLE_CLEAR 条目（PL等财务表），整块清空数字列，不逐数字匹配</li>
     *   <li>第3层：BVD 条目，按注册表坐标读值，全部标 uncertain 由用户确认</li>
     * </ol>
     *
     * @param historicalReportPath 历史报告Word绝对路径
     * @param listExcelPath        历史年份清单Excel绝对路径
     * @param bvdExcelPath         历史年份BVD数据Excel绝对路径（可选，传null则跳过）
     * @param outputPath           输出子模板Word的绝对路径
     * @return ReverseResult（包含匹配数量、待确认列表、未匹配注册表条目）
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

        // 1. 基于注册表按坐标构建 ExcelEntry 列表（LONG_TEXT → DATA_CELL → TABLE_CLEAR）
        List<ExcelEntry> entries = buildExcelEntries(listExcelPath);

        // 1b. 构建 BVD 条目（注册表坐标直读，全部标 uncertain）
        if (bvdExcelPath != null && Files.exists(Paths.get(bvdExcelPath))) {
            String companyName = extractCompanyNameFromEntries(entries);
            List<ExcelEntry> bvdEntries = buildBvdEntries(bvdExcelPath,
                    companyName != null ? companyName : "（未获取企业名）");
            if (!bvdEntries.isEmpty()) {
                entries.addAll(bvdEntries);
                log.info("[ReverseEngine] BVD条目构建：企业='{}', 找到={}条", companyName, bvdEntries.size());
            }
        }

        // 分离各类型条目，用于后续未匹配检测
        List<ExcelEntry> longTextEntries = entries.stream()
                .filter(ExcelEntry::isLongText).toList();
        List<ExcelEntry> tableClearEntries = entries.stream()
                .filter(e -> e.getPlaceholderType() == PlaceholderType.TABLE_CLEAR
                        || e.getPlaceholderType() == PlaceholderType.TABLE_CLEAR_FULL
                        || e.getPlaceholderType() == PlaceholderType.TABLE_ROW_TEMPLATE).toList();

        log.info("[ReverseEngine] ExcelEntry总计={} （长文本={}, 数据字段={}, 整表清空={}, BVD={}）",
                entries.size(),
                longTextEntries.size(),
                entries.stream().filter(e -> e.getPlaceholderType() == PlaceholderType.DATA_CELL).count(),
                tableClearEntries.size(),
                entries.stream().filter(e -> e.getPlaceholderType() == PlaceholderType.BVD).count());

        // 2. 读取历史报告，合并 Run 后做替换，写出子模板
        List<PendingConfirmItem> pendingList = new ArrayList<>();
        List<MatchedPlaceholder> matchedList = new ArrayList<>();
        List<ExcelEntry> tableClearUnmatched = new ArrayList<>();
        int[] matchedCount = {0};

        try (FileInputStream fis = new FileInputStream(historicalReportPath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            mergeAllRunsInDocument(doc);

            // 2a. 替换段落文本（LONG_TEXT / DATA_CELL / BVD）
            matchedCount[0] += replaceParagraphValuesNew(doc, entries, pendingList, matchedList);

            // 2b. 替换表格单元格（LONG_TEXT / DATA_CELL / BVD）
            matchedCount[0] += replaceTableCellValuesNew(doc, entries, pendingList, matchedList);

            // 2c-pre. BVD区间表：按表格结构识别独立交易区间表，按行位置写入BVD占位符
            matchedCount[0] += replaceBvdRangeTable(doc, matchedList);

            // 2c. TABLE_CLEAR：整块清空财务表格数字列
            if (!tableClearEntries.isEmpty()) {
                int cleared = clearTableBlock(doc, tableClearEntries, matchedList, tableClearUnmatched);
                matchedCount[0] += tableClearEntries.size() - tableClearUnmatched.size();
                log.info("[ReverseEngine] TABLE_CLEAR处理：清空{}个单元格，处理占位符={}, 未定位={}",
                        cleared, tableClearEntries.size() - tableClearUnmatched.size(), tableClearUnmatched.size());
            }

            // 2d. 全文占位符规范化（去除 {{...}} 内多余空格，修正合并Run时产生的格式问题）
            normalizeAllPlaceholdersInDocument(doc);

            // 写出子模板文件
            try {
                Files.createDirectories(Paths.get(outputPath).getParent());
            } catch (IOException e) {
                throw BizException.of("创建输出目录失败：" + e.getMessage());
            }
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                doc.write(fos);
            }
            log.info("[ReverseEngine] 子模板已生成：{}，匹配数={}，待确认数={}，总占位符数={}",
                    outputPath, matchedCount[0], pendingList.size(), matchedList.size());

        } catch (IOException e) {
            throw BizException.of("反向生成失败：" + e.getMessage());
        }

        // 3. 检测未匹配的长文本条目
        List<ExcelEntry> unmatchedLongText = detectUnmatchedLongText(longTextEntries, matchedList);
        if (!unmatchedLongText.isEmpty()) {
            log.warn("[ReverseEngine] 以下长文本条目未能在Word中匹配：{}",
                    unmatchedLongText.stream().map(ExcelEntry::getPlaceholderName).toList());
        }

        // 4. 检测注册表中有定义但整体未能处理的条目（TABLE_CLEAR未定位 + 长文本未匹配）
        List<RegistryEntry> unmatchedRegistry = detectUnmatchedRegistry(matchedList, tableClearUnmatched);

        ReverseResult result = new ReverseResult();
        result.setMatchedCount(matchedCount[0]);
        result.setPendingConfirmList(pendingList);
        result.setAllMatchedPlaceholders(matchedList);
        result.setUnmatchedLongTextEntries(unmatchedLongText);
        result.setUnmatchedRegistryEntries(unmatchedRegistry);
        return result;
    }

    /**
     * 基于静态注册表 {@link #PLACEHOLDER_REGISTRY} 构建 ExcelEntry 列表（注册表驱动模式）。
     *
     * <p>策略：
     * <ol>
     *   <li>遍历注册表所有条目</li>
     *   <li>DATA_CELL / LONG_TEXT / BVD：按 sheetName + cellAddress 精确读取单元格值</li>
     *   <li>TABLE_CLEAR：不读值，生成 value=null 的清空标记条目</li>
     *   <li>Sheet 名做 trim + 忽略大小写容错匹配；读取失败 warn 日志 + 跳过，不中断流程</li>
     * </ol>
     *
     * <p>构建顺序：LONG_TEXT 条目在前，DATA_CELL 按值长度降序在后，TABLE_CLEAR 最后，BVD 由调用方追加。
     *
     * @param listExcelPath 清单Excel绝对路径
     * @return 有序的 ExcelEntry 列表（不含 BVD，BVD 由 buildBvdEntries 单独构建）
     */
    private List<ExcelEntry> buildExcelEntries(String listExcelPath) {
        // 按 sheetName 缓存已读行数据，避免同一 Sheet 重复读取
        Map<String, List<Map<Integer, Object>>> sheetCache = new LinkedHashMap<>();

        List<ExcelEntry> longTextEntries = new ArrayList<>();
        List<ExcelEntry> dataCellEntries = new ArrayList<>();
        List<ExcelEntry> tableClearEntries = new ArrayList<>();

        for (RegistryEntry reg : PLACEHOLDER_REGISTRY) {
            // BVD 类型由 buildBvdEntries 处理，此处跳过
            if ("bvd".equals(reg.getDataSource())) continue;

            if (reg.getType() == PlaceholderType.TABLE_CLEAR || reg.getType() == PlaceholderType.TABLE_CLEAR_FULL
                    || reg.getType() == PlaceholderType.TABLE_ROW_TEMPLATE) {
                // TABLE_CLEAR / TABLE_CLEAR_FULL：不读值，直接生成清空标记条目，携带标题关键词用于精确表格匹配
                ExcelEntry entry = new ExcelEntry();
                entry.setPlaceholderName(reg.getPlaceholderName());
                entry.setDisplayName(reg.getDisplayName());
                entry.setDataSource(reg.getDataSource());
                entry.setSourceSheet(reg.getSheetName());
                entry.setSourceField(null);
                entry.setLongText(false);
                entry.setPlaceholderType(reg.getType());
                entry.setTitleKeywords(reg.getTitleKeywords());
                entry.setColumnDefs(reg.getColumnDefs());
                tableClearEntries.add(entry);
                continue;
            }

            // DATA_CELL / LONG_TEXT：按坐标读取值
            if (reg.getSheetName() == null || reg.getCellAddress() == null) {
                log.warn("[ReverseEngine-Registry] 注册表条目 '{}' 缺少 sheetName/cellAddress，跳过", reg.getPlaceholderName());
                continue;
            }

            // 容错读取：先尝试精确 Sheet 名，找不到则 trim+忽略大小写匹配
            List<Map<Integer, Object>> rows = getSheetRowsWithFallback(listExcelPath, reg.getSheetName(), sheetCache);
            if (rows == null || rows.isEmpty()) {
                log.warn("[ReverseEngine-Registry] Sheet '{}' 读取失败或为空，占位符 '{}' 跳过",
                        reg.getSheetName(), reg.getPlaceholderName());
                continue;
            }

            String cellValue = readCellValue(rows, reg.getCellAddress());
            if (cellValue == null || cellValue.isBlank()) {
                log.debug("[ReverseEngine-Registry] 占位符 '{}' 对应单元格 {}!{} 值为空，跳过",
                        reg.getPlaceholderName(), reg.getSheetName(), reg.getCellAddress());
                continue;
            }

            ExcelEntry entry = new ExcelEntry();
            entry.setValue(cellValue);
            entry.setPlaceholderName(reg.getPlaceholderName());
            entry.setDisplayName(reg.getDisplayName());
            entry.setDataSource(reg.getDataSource());
            entry.setSourceSheet(reg.getSheetName());
            entry.setSourceField(reg.getCellAddress());
            entry.setPlaceholderType(reg.getType());

            if (reg.getType() == PlaceholderType.LONG_TEXT) {
                entry.setLongText(true);
                longTextEntries.add(entry);
            } else {
                entry.setLongText(false);
                dataCellEntries.add(entry);
            }
        }

        // 数据表条目按值长度降序（先替换长值，避免短值干扰）
        dataCellEntries.sort((a, b) -> b.getValue().length() - a.getValue().length());

        List<ExcelEntry> result = new ArrayList<>();
        result.addAll(longTextEntries);
        result.addAll(dataCellEntries);
        result.addAll(tableClearEntries);

        log.info("[ReverseEngine-Registry] ExcelEntry构建完成：长文本={}, 数据表字段={}, TABLE_CLEAR={}",
                longTextEntries.size(), dataCellEntries.size(), tableClearEntries.size());
        return result;
    }

    /**
     * 容错读取 Sheet 行数据：先精确匹配 sheetName，失败则 trim+忽略大小写遍历所有 Sheet 名匹配。
     * 结果缓存到 sheetCache，避免同一 Sheet 重复读取。
     */
    private List<Map<Integer, Object>> getSheetRowsWithFallback(
            String filePath, String sheetName,
            Map<String, List<Map<Integer, Object>>> sheetCache) {

        String cacheKey = filePath + "::" + sheetName;
        if (sheetCache.containsKey(cacheKey)) {
            return sheetCache.get(cacheKey);
        }

        // 先尝试精确读取
        List<Map<Integer, Object>> rows = readSheet(filePath, sheetName);
        if (rows != null && !rows.isEmpty()) {
            sheetCache.put(cacheKey, rows);
            return rows;
        }

        // 精确读取失败：遍历所有 Sheet 名，trim+忽略大小写匹配
        List<String> allSheets = readSheetNames(filePath);
        for (int i = 0; i < allSheets.size(); i++) {
            if (allSheets.get(i).trim().equalsIgnoreCase(sheetName.trim())) {
                rows = readSheetByIndex(filePath, i);
                if (rows != null && !rows.isEmpty()) {
                    log.info("[ReverseEngine-Registry] Sheet '{}' 容错匹配到 '{}'", sheetName, allSheets.get(i));
                    sheetCache.put(cacheKey, rows);
                    return rows;
                }
            }
        }

        sheetCache.put(cacheKey, Collections.emptyList());
        return Collections.emptyList();
    }

    /**
     * Sheet 类型枚举（保留，供旧逻辑路径引用）
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
     * 自动识别 Sheet 类型（保留，供旧兼容路径引用，新引擎已改为注册表驱动）。
     */
    private SheetType detectSheetType(String sheetName, List<Map<Integer, Object>> rows) {
        String lowerName = sheetName.trim().toLowerCase();
        if (lowerName.contains("数据") || lowerName.equals("基本信息")
                || lowerName.equals("data") || lowerName.equals("info")
                || lowerName.equals("基础数据") || lowerName.equals("基本数据")) {
            return SheetType.DATA_TABLE;
        }
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
        return SheetType.SKIP;
    }

    // ========== BVD 精准行扫描 ==========

    /**
     * 从注册表 DATA_CELL 条目中提取企业名称（B1=企业全称）。
     * 优先从 entries 中找 placeholderName 为"清单模板-数据表-B1"的条目，
     * 其次 fallback 到 entries 中名含"企业"的首个非长文本条目。
     */
    private String extractCompanyNameFromEntries(List<ExcelEntry> entries) {
        // 优先：注册表 B1（企业全称）
        for (ExcelEntry e : entries) {
            if ("清单模板-数据表-B1".equals(e.getPlaceholderName()) && e.getValue() != null) {
                return e.getValue();
            }
        }
        // 兼容旧动态路径：占位符名含"企业名称"
        for (ExcelEntry e : entries) {
            if (!e.isLongText() && "企业名称".equals(e.getPlaceholderName())) {
                return e.getValue();
            }
        }
        // 最终兜底：占位符名含"企业"
        for (ExcelEntry e : entries) {
            if (!e.isLongText() && e.getPlaceholderName() != null
                    && e.getPlaceholderName().contains("企业")) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 基于注册表 BVD 条目构建 BVD ExcelEntry 列表（注册表坐标直读模式）。
     *
     * <p>策略：
     * <ol>
     *   <li>遍历注册表中 dataSource=bvd 的所有条目</li>
     *   <li>按 sheetName + cellAddress 精确读取单元格值</li>
     *   <li>所有 BVD 条目的 placeholderType=BVD，标记为 uncertain，由用户确认</li>
     * </ol>
     *
     * @param bvdExcelPath BVD Excel绝对路径
     * @param companyName  企业名称（仅用于日志，不再用于行定位）
     * @return BVD ExcelEntry 列表
     */
    private List<ExcelEntry> buildBvdEntries(String bvdExcelPath, String companyName) {
        List<ExcelEntry> result = new ArrayList<>();
        Map<String, List<Map<Integer, Object>>> sheetCache = new LinkedHashMap<>();

        for (RegistryEntry reg : PLACEHOLDER_REGISTRY) {
            if (!"bvd".equals(reg.getDataSource())) continue;
            if (reg.getSheetName() == null || reg.getCellAddress() == null) continue;

            List<Map<Integer, Object>> rows = getSheetRowsWithFallback(bvdExcelPath, reg.getSheetName(), sheetCache);
            if (rows == null || rows.isEmpty()) {
                log.warn("[ReverseEngine-BVD] Sheet '{}' 读取失败，占位符 '{}' 跳过",
                        reg.getSheetName(), reg.getPlaceholderName());
                continue;
            }

            String cellValue = readCellValue(rows, reg.getCellAddress());
            if (cellValue == null || cellValue.isBlank()) {
                log.debug("[ReverseEngine-BVD] 占位符 '{}' 对应单元格 {}!{} 值为空，跳过",
                        reg.getPlaceholderName(), reg.getSheetName(), reg.getCellAddress());
                continue;
            }

            ExcelEntry entry = new ExcelEntry();
            entry.setValue(cellValue);
            entry.setPlaceholderName(reg.getPlaceholderName());
            entry.setDisplayName(reg.getDisplayName());
            entry.setDataSource("bvd");
            entry.setSourceSheet(reg.getSheetName());
            entry.setSourceField(reg.getCellAddress());
            entry.setLongText(false);
            entry.setPlaceholderType(PlaceholderType.BVD);
            result.add(entry);
        }

        log.info("[ReverseEngine-BVD] 注册表坐标读取：企业='{}', 找到BVD条目={}", companyName, result.size());
        return result;
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
        // 处理页眉
        for (XWPFHeader header : doc.getHeaderList()) {
            int hParaIndex = 0;
            for (XWPFParagraph paragraph : header.getParagraphs()) {
                count += replaceInRunsNew(paragraph.getRuns(), entries,
                        pendingList, matchedList, "页眉段落#" + hParaIndex, hParaIndex, -1, -1, -1, fullDocText);
                hParaIndex++;
            }
            for (XWPFTable table : header.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        int cParaIndex = 0;
                        for (XWPFParagraph paragraph : cell.getParagraphs()) {
                            count += replaceInRunsNew(paragraph.getRuns(), entries,
                                    pendingList, matchedList, "页眉表格段落#" + cParaIndex, cParaIndex, -1, -1, -1, fullDocText);
                            cParaIndex++;
                        }
                    }
                }
            }
        }
        // 处理页脚
        for (XWPFFooter footer : doc.getFooterList()) {
            int fParaIndex = 0;
            for (XWPFParagraph paragraph : footer.getParagraphs()) {
                count += replaceInRunsNew(paragraph.getRuns(), entries,
                        pendingList, matchedList, "页脚段落#" + fParaIndex, fParaIndex, -1, -1, -1, fullDocText);
                fParaIndex++;
            }
            for (XWPFTable table : footer.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        int cParaIndex = 0;
                        for (XWPFParagraph paragraph : cell.getParagraphs()) {
                            count += replaceInRunsNew(paragraph.getRuns(), entries,
                                    pendingList, matchedList, "页脚表格段落#" + cParaIndex, cParaIndex, -1, -1, -1, fullDocText);
                            cParaIndex++;
                        }
                    }
                }
            }
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
     * 在 Run 列表中执行注册表驱动的替换逻辑（按 placeholderType 四路分支）：
     * <ul>
     *   <li>LONG_TEXT：整段精确全字符串替换</li>
     *   <li>DATA_CELL（短值 &lt; {@value #MEDIUM_VALUE_THRESHOLD} 字）：词边界正则替换</li>
     *   <li>DATA_CELL（长值 &ge; {@value #MEDIUM_VALUE_THRESHOLD} 字）：直接精确 replace</li>
     *   <li>TABLE_CLEAR：value=null，本方法不处理（由 clearTableBlock 单独调用）</li>
     *   <li>BVD / type=null（兼容旧路径）：全部标 uncertain，不自动替换</li>
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
                // TABLE_CLEAR / TABLE_CLEAR_FULL 类型由 clearTableBlock 处理，此处跳过
                if (entry.getPlaceholderType() == PlaceholderType.TABLE_CLEAR
                        || entry.getPlaceholderType() == PlaceholderType.TABLE_CLEAR_FULL) continue;

                String value = entry.getValue();
                if (value == null || value.isBlank()) continue;
                String phMark = "{{" + entry.getPlaceholderName() + "}}";
                if (text.contains(phMark)) continue;
                if (!text.contains(value)) {
                    // 特例：年度字段（2位数字）允许通过变体检测放行
                    if (!isYearFieldEntry(entry)) continue;
                    List<String> yearVariants = buildYearVariants(value);
                    boolean anyVariantPresent = yearVariants.stream().anyMatch(text::contains);
                    if (!anyVariantPresent) continue;
                }

                PlaceholderType pType = entry.getPlaceholderType();

                if (pType == PlaceholderType.LONG_TEXT || entry.isLongText()) {
                    // ① LONG_TEXT：整段精确匹配，直接替换
                    String newText = text.replace(value, phMark);
                    if (!newText.equals(text)) {
                        text = newText;
                        runModified = true;
                        count++;
                        addMatchedRecord(matchedList, entry, value, originalText, location,
                                "confirmed", paragraphIndex, tableIndex, rowIndex, cellIndex);
                        log.debug("[ReverseEngine] 长文本替换: '{}...' -> {}",
                                value.substring(0, Math.min(20, value.length())), phMark);
                    }

                } else if (pType == PlaceholderType.BVD) {
                    // ② BVD：全部标 uncertain，不修改文本（BVD分位数值与文档实际值不对应，由 replaceBvdRangeTable 按位置替换）
                    addMatchedRecord(matchedList, entry, value, originalText, location,
                            "uncertain", paragraphIndex, tableIndex, rowIndex, cellIndex);
                    log.debug("[ReverseEngine] BVD-uncertain: '{}' -> {}", value, phMark);

                } else if (pType == PlaceholderType.DATA_CELL) {
                    // ③ DATA_CELL：按值类型分支
                    if (isYearFieldEntry(entry)) {
                        // 年度字段特殊处理：将2位年份扩展为多格式变体逐一替换
                        // 变体末尾含汉字（如"2024年"）→ 精确替换；裸数字（"2024"）→ 词边界替换（防误替换）
                        List<String> yearVariants = buildYearVariants(value);
                        boolean replaced = false;
                        for (String variant : yearVariants) {
                            if (!text.contains(variant)) continue;
                            char lastChar = variant.charAt(variant.length() - 1);
                            boolean endsWithChinese = lastChar >= '\u4e00' && lastChar <= '\u9fa5';
                            String newText = endsWithChinese
                                    ? text.replace(variant, phMark)
                                    : replaceWithWordBoundary(text, variant, phMark);
                            if (!newText.equals(text)) {
                                text = newText;
                                runModified = true;
                                if (!replaced) {
                                    count++;
                                    replaced = true;
                                    addMatchedRecord(matchedList, entry, variant, originalText, location,
                                            "confirmed", paragraphIndex, tableIndex, rowIndex, cellIndex);
                                }
                                log.debug("[ReverseEngine] 年度变体替换[{}]: '{}' -> {}", entry.getDisplayName(), variant, phMark);
                            }
                        }
                        // 年度-2 偏移替换：将「B2年度值-2」对应的年份文本替换为 {{phName}}-2
                        // 例：B2=24 → fullYear=2024，offsetYear=2022，
                        //     "2022财务年度" → "{{清单模板-数据表-B2}}-2"
                        String phMarkMinus2 = phMark + "-2";
                        List<String> offsetVariants = buildYearVariants(String.valueOf(Integer.parseInt("20" + value) - 2).substring(2));
                        boolean replacedMinus2 = false;
                        for (String variant : offsetVariants) {
                            if (!text.contains(variant)) continue;
                            char lastChar = variant.charAt(variant.length() - 1);
                            boolean endsWithChinese = lastChar >= '\u4e00' && lastChar <= '\u9fa5';
                            String newText = endsWithChinese
                                    ? text.replace(variant, phMarkMinus2)
                                    : replaceWithWordBoundary(text, variant, phMarkMinus2);
                            if (!newText.equals(text)) {
                                text = newText;
                                runModified = true;
                                if (!replacedMinus2) {
                                    replacedMinus2 = true;
                                    addMatchedRecord(matchedList, entry, variant, originalText, location,
                                            "confirmed", paragraphIndex, tableIndex, rowIndex, cellIndex);
                                }
                                log.debug("[ReverseEngine] 年度-2偏移替换[{}]: '{}' -> {}", entry.getDisplayName(), variant, phMarkMinus2);
                            }
                        }
                    } else if (isShortAbbrevField(entry)) {
                        // 短简称字段（如B4事务所简称）：使用词边界替换，防止误替换已被替换为占位符的长值内的子串
                        // 注：buildExcelEntries 已按值长度降序排序，B3全称先于B4简称处理，此处为额外防护
                        String newText = replaceWithWordBoundary(text, value, phMark);
                        if (!newText.equals(text)) {
                            text = newText;
                            runModified = true;
                            count++;
                            addMatchedRecord(matchedList, entry, value, originalText, location,
                                    "confirmed", paragraphIndex, tableIndex, rowIndex, cellIndex);
                            log.debug("[ReverseEngine] 词边界替换[简称][{}]: '{}' -> {}", entry.getDisplayName(), value, phMark);
                        }
                    } else {
                        // 非年度/非短简称字段：精确替换
                        String newText = text.replace(value, phMark);
                        if (!newText.equals(text)) {
                            text = newText;
                            runModified = true;
                            count++;
                            addMatchedRecord(matchedList, entry, value, originalText, location,
                                    "confirmed", paragraphIndex, tableIndex, rowIndex, cellIndex);
                            log.debug("[ReverseEngine] 精确替换[{}]: '{}' -> {}", entry.getDisplayName(), value, phMark);
                        }
                    }

                } else {
                    // ④ type=null（兼容旧动态扫描路径）：保留原有 decideReplaceStrategy 判断
                    boolean isAbbrevField = entry.getPlaceholderName() != null
                            && entry.getPlaceholderName().contains("简称");
                    ReplaceDecision decision;
                    if ("bvd".equals(entry.getDataSource())) {
                        decision = ReplaceDecision.UNCERTAIN_SHORT;
                    } else if (isAbbrevField) {
                        decision = ReplaceDecision.AUTO;
                    } else {
                        decision = decideReplaceStrategy(value, fullDocText);
                    }
                    if (decision == ReplaceDecision.AUTO) {
                        String newText = text.replace(value, phMark);
                        if (!newText.equals(text)) {
                            text = newText;
                            runModified = true;
                            count++;
                            addMatchedRecord(matchedList, entry, value, originalText, location,
                                    "confirmed", paragraphIndex, tableIndex, rowIndex, cellIndex);
                        }
                    } else {
                        addMatchedRecord(matchedList, entry, value, originalText, location,
                                "uncertain", paragraphIndex, tableIndex, rowIndex, cellIndex);
                    }
                }
            }
            if (runModified) {
                // 对生成的占位符做规范化：去除 {{ 后、}} 前的多余空格，以及占位符名内部多余空格
                text = normalizePlaceholders(text);
                run.setText(text, 0);
            }
        }
        return count;
    }

    /**
     * 规范化占位符格式：去除 {{ 与 }} 内多余空格，保证格式为 {{清单模板-xxx}}。
     * 解决 Run 合并时因空格 Run 导致的 {{ 清单模板...}} 或 {{清单模板-数据 表-B5}} 等问题。
     */
    private String normalizePlaceholders(String text) {
        if (text == null || !text.contains("{{")) return text;
        // 1. 去除 {{ 紧跟的空格：{{ 清单 → {{清单
        text = text.replaceAll("\\{\\{\\s+", "{{");
        // 2. 去除 }} 前的空格：清单 }} → 清单}}
        text = text.replaceAll("\\s+\\}\\}", "}}");
        // 3. 去除占位符名内部多余空格（如 "数据 表" → "数据表"）
        //    只处理 {{ 和 }} 之间的空格
        StringBuffer sb = new StringBuffer();
        java.util.regex.Matcher m = Pattern.compile("\\{\\{([^}]+)\\}\\}").matcher(text);
        while (m.find()) {
            String inner = m.group(1).replaceAll("\\s+", "");
            m.appendReplacement(sb, "{{" + java.util.regex.Matcher.quoteReplacement(inner) + "}}");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 遍历文档所有位置（正文段落、表格、页眉、页脚）的每个 Run，
     * 对含有 {{ 的 Run 文本调用 normalizePlaceholders 做占位符格式规范化。
     * 在写出文件前调用，确保所有 Run 合并阶段产生的多余空格被清除。
     */
    private void normalizeAllPlaceholdersInDocument(XWPFDocument doc) {
        // 正文段落
        for (XWPFParagraph para : doc.getParagraphs()) {
            normalizeRunsInParagraph(para);
        }
        // 正文表格
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph para : cell.getParagraphs()) {
                        normalizeRunsInParagraph(para);
                    }
                }
            }
        }
        // 页眉
        for (XWPFHeader header : doc.getHeaderList()) {
            for (XWPFParagraph para : header.getParagraphs()) {
                normalizeRunsInParagraph(para);
            }
            for (XWPFTable table : header.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph para : cell.getParagraphs()) {
                            normalizeRunsInParagraph(para);
                        }
                    }
                }
            }
        }
        // 页脚
        for (XWPFFooter footer : doc.getFooterList()) {
            for (XWPFParagraph para : footer.getParagraphs()) {
                normalizeRunsInParagraph(para);
            }
            for (XWPFTable table : footer.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph para : cell.getParagraphs()) {
                            normalizeRunsInParagraph(para);
                        }
                    }
                }
            }
        }
    }

    private void normalizeRunsInParagraph(XWPFParagraph para) {
        for (XWPFRun run : para.getRuns()) {
            String text = run.getText(0);
            if (text != null && text.contains("{{")) {
                String normalized = normalizePlaceholders(text);
                if (!normalized.equals(text)) {
                    run.setText(normalized, 0);
                    log.debug("[ReverseEngine] 规范化占位符: '{}' -> '{}'", text, normalized);
                }
            }
        }
    }

    /**
     * 词边界正则替换（适用于中文/中英文混合短值）。
     *
     * <p>使用 Unicode 非字符边界断言，确保替换目标前后不紧跟汉字/字母/数字，
     * 避免短值（如企业简称 4~6 字）误替换长字符串中间的子串。
     *
     * <p>例："松莉" 不会替换 "松莉科技" 中的"松莉"，但会替换 "（松莉）" 中的"松莉"。
     *
     * @param text        原文本
     * @param value       待替换的值
     * @param replacement 替换为的字符串（占位符标记）
     * @return 替换后的文本（若无匹配则返回原文本）
     */
    private String replaceWithWordBoundary(String text, String value, String replacement) {
        if (text == null || value == null || value.isBlank()) return text;
        // 中文词边界：前面不是汉字/字母/数字（lookbehind），后面不是汉字/字母/数字（lookahead）
        String prefix = "(?<![\\u4e00-\\u9fa5A-Za-z0-9])";
        String suffix = "(?![\\u4e00-\\u9fa5A-Za-z0-9])";
        String pattern = prefix + Pattern.quote(value) + suffix;
        try {
            return text.replaceAll(pattern, Matcher.quoteReplacement(replacement));
        } catch (Exception e) {
            log.warn("[ReverseEngine] 词边界替换正则异常，回退到精确替换: value='{}', err={}", value, e.getMessage());
            return text.replace(value, replacement);
        }
    }

    /**
     * 判断 entry 是否为年度字段：占位符名为 "清单模板-数据表-B2" 且值为2位纯数字（如 "24"）。
     *
     * @param entry Excel 条目
     * @return true 表示需要走年度多格式匹配逻辑
     */
    private boolean isYearFieldEntry(ExcelEntry entry) {
        return "清单模板-数据表-B2".equals(entry.getPlaceholderName())
                && entry.getValue() != null
                && entry.getValue().matches("\\d{2}");
    }

    /**
     * 判断是否为短简称字段（需要词边界保护替换）。
     *
     * <p>当前规则：清单数据表 B4（事务所简称）且值长度 ≤ 6 字时，认为是短简称字段。
     * 短简称可能作为全称的子串出现（如"立信税务"是"立信税务师事务所有限公司"的子串），
     * 使用词边界替换可防止误替换全称已被替换为占位符后残留子串的情况。
     *
     * @param entry Excel 条目
     * @return true 表示需要走词边界替换逻辑
     */
    private boolean isShortAbbrevField(ExcelEntry entry) {
        if (entry.getValue() == null || entry.getPlaceholderName() == null) return false;
        // B4 事务所简称：值长度 ≤ 6 字
        return entry.getValue().length() <= 6
                && entry.getPlaceholderName().endsWith("-B4");
    }

    /**
     * 根据2位年份缩写构建完整年份变体列表，按最长形式优先排序，防止"2024年"先被替换后"2024年度"找不到匹配。
     *
     * <p>示例："24" → ["2024财务年度", "2024财年", "2024年度", "2024年", "2024"]
     *
     * @param twoDigitYear 2位年份字符串，如 "24"
     * @return 年份变体列表（从长到短）
     */
    private List<String> buildYearVariants(String twoDigitYear) {
        String fullYear = "20" + twoDigitYear;
        return List.of(
                fullYear + "财务年度",  // 最长优先
                fullYear + "财年",
                fullYear + "年度",
                fullYear + "年",
                fullYear              // 兜底
        );
    }

    /**
     * BVD 独立交易区间表按位置替换。
     *
     * <p>通过表格首列关键词（"四分位区间"或"可比公司数量"）识别独立交易区间表，
     * 然后跳过表头行，按数据行顺序将第2列单元格依次写入
     * {@code {{BVD数据模板-数据表B1}}} ~ {@code {{BVD数据模板-数据表B4}}}。
     *
     * @param doc         Word 文档
     * @param matchedList 用于记录处理结果
     * @return 实际写入的占位符数量
     */
    private int replaceBvdRangeTable(XWPFDocument doc, List<MatchedPlaceholder> matchedList) {
        String[] bvdPlaceholders = {
            "BVD数据模板-数据表-B1",
            "BVD数据模板-数据表-B2",
            "BVD数据模板-数据表-B3",
            "BVD数据模板-数据表-B4"
        };

        int count = 0;
        List<XWPFTable> allTables = doc.getTables();
        for (int tIdx = 0; tIdx < allTables.size(); tIdx++) {
            XWPFTable table = allTables.get(tIdx);
            List<XWPFTableRow> rows = table.getRows();
            if (rows.size() < 2) continue;

            // 识别：首列任意行含"四分位区间"或"可比公司数量"视为独立交易区间表
            boolean isBvdTable = false;
            for (XWPFTableRow row : rows) {
                List<XWPFTableCell> cells = row.getTableCells();
                if (cells.isEmpty()) continue;
                String firstCell = cells.get(0).getText().trim();
                if (firstCell.contains("四分位区间") || firstCell.contains("可比公司数量")) {
                    isBvdTable = true;
                    break;
                }
            }
            if (!isBvdTable) continue;

            log.info("[ReverseEngine-BVD] 识别到独立交易区间表#{}，开始按行写入BVD占位符", tIdx);

            // 跳过表头行（首列含"四分位区间"的行），数据行依次对应 B1~B4
            int bvdIdx = 0;
            for (int rIdx = 0; rIdx < rows.size() && bvdIdx < bvdPlaceholders.length; rIdx++) {
                XWPFTableRow row = rows.get(rIdx);
                List<XWPFTableCell> cells = row.getTableCells();
                if (cells.size() < 2) continue;
                String firstCellText = cells.get(0).getText().trim();
                if (firstCellText.contains("四分位区间")) continue; // 跳过表头

                XWPFTableCell dataCell = cells.get(1);
                String phMark = "{{" + bvdPlaceholders[bvdIdx] + "}}";

                // 写入占位符到第2列单元格
                writeCellText(dataCell, phMark);

                // 记录匹配
                MatchedPlaceholder mp = new MatchedPlaceholder();
                mp.setPlaceholderName(bvdPlaceholders[bvdIdx]);
                mp.setExpectedValue("[BVD按位置]");
                mp.setActualValue(phMark);
                mp.setLocation("表格#" + tIdx + "-行" + rIdx + "-列1");
                mp.setStatus("confirmed");
                mp.setDataSource("bvd");
                mp.setSourceSheet("数据表");
                mp.setSourceField("B" + (bvdIdx + 1));
                mp.setModuleName("BVD数据");
                mp.setModuleCode("bvd");
                mp.setPositionJson("{\"elementType\":\"table\",\"tableIndex\":" + tIdx
                        + ",\"rowIndex\":" + rIdx + ",\"cellIndex\":1}");
                matchedList.add(mp);

                log.info("[ReverseEngine-BVD] 表格#{}行{} → {}", tIdx, rIdx, phMark);
                bvdIdx++;
                count++;
            }
            break; // 只处理第一个识别到的区间表
        }
        return count;
    }

    /**
     * TABLE_CLEAR 类型整块清空处理（路径A：精确标题匹配）。
     *
     * <p><b>两阶段策略：</b>
     * <ol>
     *   <li><b>Phase 1</b>：遍历 {@code doc.getBodyElements()} 建立"表格 → 前置标题段落文本"映射表
     *       （维护滑动窗口保留最近3个非空段落）</li>
     *   <li><b>Phase 2</b>：每个 TABLE_CLEAR 条目通过其 {@code titleKeywords} 在映射表中精确查找对应表格，
     *       找到后清空该表格数字列并从候选池中移除（防止复用）；
     *       未命中关键词时 fallback 到 {@link #isFinancialTable} 的第一个未绑定财务表格；
     *       依然找不到则加入 unmatchedEntries。</li>
     * </ol>
     *
     * @param doc               Word 文档
     * @param tableClearEntries TABLE_CLEAR 类型的 ExcelEntry 列表
     * @param matchedList       用于记录成功处理的条目
     * @param unmatchedEntries  用于记录无法定位的条目（降级保护）
     * @return 实际清空处理的单元格数
     */
    private int clearTableBlock(XWPFDocument doc,
                                 List<ExcelEntry> tableClearEntries,
                                 List<MatchedPlaceholder> matchedList,
                                 List<ExcelEntry> unmatchedEntries) {
        if (tableClearEntries == null || tableClearEntries.isEmpty()) return 0;
        int clearedCount = 0;

        // ===== Phase 1：遍历 body elements，建立 tableHeadMap（表格 → 前置标题文本） =====
        // key: 表格在文档中的顺序索引（由 doc.getTables() 的顺序确定），value: 前置标题段落拼接文本
        // 同时维护 tableIndexMap 用于记录表格对应 tIdx（供日志/positionJson 使用）
        List<XWPFTable> allTables = doc.getTables();
        Map<XWPFTable, String> tableHeadMap = new LinkedHashMap<>();
        Map<XWPFTable, Integer> tableIndexMap = new LinkedHashMap<>();

        {
            // 滑动窗口：保留最近 3 个非空段落文本
            java.util.Deque<String> recentParas = new java.util.ArrayDeque<>(3);
            int tIdx = 0;
            for (org.apache.poi.xwpf.usermodel.IBodyElement elem : doc.getBodyElements()) {
                if (elem instanceof XWPFParagraph para) {
                    String text = para.getText();
                    if (text != null && !text.isBlank()) {
                        if (recentParas.size() >= 3) recentParas.pollFirst();
                        recentParas.addLast(text.trim());
                    }
                } else if (elem instanceof XWPFTable table) {
                    // 将滑动窗口中的段落拼接为候选标题文本
                    String candidateTitle = String.join(" ", recentParas);
                    tableHeadMap.put(table, candidateTitle);
                    tableIndexMap.put(table, tIdx);
                    tIdx++;
                    // 遇到表格后不清空滑动窗口，下一个表格仍能沿用附近段落
                }
            }
        }

        // 用于 fallback 的"未被精确绑定的财务表格"候选池（有序，按 tIdx 升序）
        Set<XWPFTable> boundTables = new LinkedHashSet<>();

        // ===== Phase 2：按条目查找对应表格 =====
        for (ExcelEntry entry : tableClearEntries) {
            String phMark = "{{" + entry.getPlaceholderName() + "}}";
            List<String> keywords = entry.getTitleKeywords();

            XWPFTable targetTable = null;
            int targetIdx = -1;

            // 2a. 通过 titleKeywords 精确匹配
            if (keywords != null && !keywords.isEmpty()) {
                outer:
                for (Map.Entry<XWPFTable, String> e : tableHeadMap.entrySet()) {
                    if (boundTables.contains(e.getKey())) continue; // 已被其他条目绑定
                    String headText = e.getValue();
                    for (String kw : keywords) {
                        if (headText.contains(kw)) {
                            // 精确匹配命中后，对所有 TABLE_CLEAR_FULL 类型均排除独立交易区间表：
                            // 独立交易区间表（首列含"四分位区间"等关键词）仅应由 BVD 占位符消费，
                            // 任何清单模板的表格占位符（组织结构/分部财务数据/PL 等）都不应绑到它上面
                            if (entry.getPlaceholderType() == PlaceholderType.TABLE_CLEAR_FULL
                                    && isIndependentRangeTable(e.getKey())) {
                                log.warn("[ReverseEngine-TableClear] 占位符 '{}' 关键词 '{}' 命中表格#{} 但该表为独立交易区间表，跳过，继续搜索",
                                        entry.getPlaceholderName(), kw, tableIndexMap.getOrDefault(e.getKey(), -1));
                                break; // 跳出内层 keywords 循环，继续尝试下一张表
                            }
                            targetTable = e.getKey();
                            targetIdx = tableIndexMap.getOrDefault(e.getKey(), -1);
                            log.info("[ReverseEngine-TableClear] 占位符 '{}' 通过关键词 '{}' 精确匹配到表格#{}，前置标题：[{}]",
                                    entry.getPlaceholderName(), kw, targetIdx, headText);
                            break outer;
                        }
                    }
                }
            }

            // 2b. 精确匹配失败 → fallback：取第一个未绑定的财务表格
            if (targetTable == null) {
                for (Map.Entry<XWPFTable, Integer> e : tableIndexMap.entrySet()) {
                    XWPFTable t = e.getKey();
                    if (boundTables.contains(t)) continue;
                    if (isFinancialTable(t)) {
                        targetTable = t;
                        targetIdx = e.getValue();
                        log.warn("[ReverseEngine-TableClear] 占位符 '{}' 关键词未命中，fallback 到财务表格#{}（前置标题：[{}]）",
                                entry.getPlaceholderName(), targetIdx,
                                tableHeadMap.getOrDefault(t, ""));
                        break;
                    }
                }
            }

            if (targetTable == null) {
                log.warn("[ReverseEngine-TableClear] 占位符 '{}' 未能定位到表格，加入未匹配列表",
                        entry.getPlaceholderName());
                unmatchedEntries.add(entry);
                continue;
            }

            // 标记该表格已绑定，防止其他条目复用
            boundTables.add(targetTable);

            // 清空该表格：根据类型选择策略
            List<XWPFTableRow> rows = targetTable.getRows();
            if (rows.isEmpty()) {
                unmatchedEntries.add(entry);
                continue;
            }
            int colCount = rows.get(0).getTableCells().size();
            int[] cellsCleared = {0}; // 使用数组绕过lambda的final限制

            if (entry.getPlaceholderType() == PlaceholderType.TABLE_CLEAR_FULL) {
                // 整表全清空策略：遍历所有行所有列，第一个非空单元格写占位符，其余全部清空
                boolean firstCellWritten = false;
                for (XWPFTableRow row : rows) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String writeText = firstCellWritten ? "" : phMark;
                        writeCellText(cell, writeText);
                        if (!firstCellWritten) {
                            firstCellWritten = true;
                        }
                        cellsCleared[0]++;
                    }
                }
                // 删除第2行起的所有多余空行，只保留第一行（含占位符）
                int totalRows = targetTable.getNumberOfRows();
                for (int ri = totalRows - 1; ri >= 1; ri--) {
                    targetTable.removeRow(ri);
                }
                log.debug("[ReverseEngine-TableClear] TABLE_CLEAR_FULL 占位符 '{}' 删除多余行 {} 行，仅保留第1行",
                        entry.getPlaceholderName(), totalRows - 1);
            } else if (entry.getPlaceholderType() == PlaceholderType.TABLE_ROW_TEMPLATE) {
                // 行模板克隆策略（保留所有行，每行写占位符）：
                // - 第0行（表头行）：原样保留
                // - 第1行起：每行识别行类型（group/data/subtotal），写入对应占位符
                //   * data行（明细行）：第0列写 "{{_tpl_占位符名}} {{_row_data}} {{_col_字段名}}"
                //   * group行（分组标题行）：第0列写 "{{_row_group}} {{_col_字段名}}"
                //   * subtotal行（小计/合计行）：第0列写 "{{_row_subtotal}} {{_col_字段名}}"
                //   * 其余列按 columnDefs[ci] 写 "{{_col_字段名}}"
                // - 保留原表格所有行，不删除任何行
                if (rows.size() < 2) {
                    log.warn("[ReverseEngine-TableClear] TABLE_ROW_TEMPLATE 占位符 '{}' 表格行数不足2行，跳过",
                            entry.getPlaceholderName());
                    unmatchedEntries.add(entry);
                    continue;
                }
                String tplMark = "{{_tpl_" + entry.getPlaceholderName() + "}}";
                List<String> colDefs = entry.getColumnDefs();

                // 辅助方法：写占位符文本到一行的各列
                java.util.function.BiConsumer<XWPFTableRow, String> writeRowMarkers = (row, rowTypeMark) -> {
                    List<XWPFTableCell> tplCells2 = row.getTableCells();
                    // 找到第一个非垂直合并延续的单元格索引（用于写行标记）
                    int firstNonMergeColIdx = -1;
                    for (int i = 0; i < tplCells2.size(); i++) {
                        if (!isVerticalMergeContinue(tplCells2.get(i))) {
                            firstNonMergeColIdx = i;
                            break;
                        }
                    }
                    if (firstNonMergeColIdx < 0) {
                        log.debug("[ReverseEngine-TableClear] 该行所有单元格都是垂直合并延续，跳过写入");
                        return;
                    }

                    for (int ci = 0; ci < tplCells2.size(); ci++) {
                        XWPFTableCell cell = tplCells2.get(ci);

                        // 跳过垂直合并的延续单元格（这些单元格属于上一行的一部分）
                        if (isVerticalMergeContinue(cell)) {
                            log.debug("[ReverseEngine-TableClear] 跳过垂直合并延续单元格 ci={}", ci);
                            continue;
                        }

                        // 列字段名映射：根据 tplCells2.size() 动态调整，处理合并单元格导致的列错位
                        String colMark = "";
                        if (colDefs != null && !colDefs.isEmpty()) {
                            int sizeDiff = colDefs.size() - tplCells2.size();
                            int defIdx = ci + sizeDiff;
                            if (defIdx >= 0 && defIdx < colDefs.size()) {
                                colMark = "{{_col_" + colDefs.get(defIdx) + "}}";
                            }
                        }
                        final String writeText;
                        if (ci == firstNonMergeColIdx) {
                            // 第一个非合并单元格：写入 tplMark + rowTypeMark + colMark
                            // 所有行类型（data/group/subtotal）都加上 tplMark，以便识别属于哪个数据表
                            String prefix = tplMark + " " + rowTypeMark;
                            writeText = colMark.isEmpty() ? prefix : prefix + " " + colMark;
                        } else {
                            writeText = colMark;
                        }
                        writeCellText(cell, writeText);
                        cellsCleared[0]++;
                    }
                };

                // 行类型判断规则
                //   subtotal：任意列文本含"小计"/"合计"/"总计"，或末行且整行合并
                //   group：整行合并（gridSpan之和 = 表头列数）且不是末行
                //   data：其他情况（普通数据行）
                int headerColCount = rows.get(0).getTableCells().size();
                int processedRows = 0;

                for (int ri = 1; ri < rows.size(); ri++) {
                    XWPFTableRow row = rows.get(ri);
                    List<XWPFTableCell> rowCells = row.getTableCells();

                    // 拼接所有列文本用于判断
                    StringBuilder allText = new StringBuilder();
                    for (XWPFTableCell cell : rowCells) {
                        allText.append(cell.getText() != null ? cell.getText().trim() : "");
                    }
                    String allStr = allText.toString();

                    // 判断行类型
                    String rowTypeMark;
                    if (allStr.contains("小计") || allStr.contains("合计") || allStr.contains("总计")) {
                        // 明确包含合计关键词的行标记为subtotal
                        rowTypeMark = "{{_row_subtotal}}";
                    } else if (isGroupRow(row)) {
                        // Group行（分组标题行）：第一列有内容且有垂直合并特征
                        rowTypeMark = "{{_row_group}}";
                    } else if (isFullRowMerged(row, headerColCount)) {
                        // 整行合并的情况：根据是否是最后一行判断类型
                        if (ri == rows.size() - 1) {
                            // 末行整行合并 → 合计行
                            rowTypeMark = "{{_row_subtotal}}";
                        } else {
                            // 中间整行合并 → 分组标题行
                            rowTypeMark = "{{_row_group}}";
                        }
                    } else {
                        // 普通数据行（单元格数=表头列数，无整行合并）
                        rowTypeMark = "{{_row_data}}";
                    }

                    writeRowMarkers.accept(row, rowTypeMark);
                    processedRows++;
                }

                // 精简行数：只保留第1行data行和第1行group行作为模板，删除其他多余行，保留subtotal行
                // 重新获取rows（因为可能已被修改）
                rows = targetTable.getRows();
                List<Integer> dataRowIndices = new ArrayList<>();
                List<Integer> groupRowIndices = new ArrayList<>();
                List<Integer> subtotalRowIndices = new ArrayList<>();
                headerColCount = rows.get(0).getTableCells().size();

                for (int ri = 1; ri < rows.size(); ri++) {
                    XWPFTableRow row = rows.get(ri);
                    List<XWPFTableCell> rowCells = row.getTableCells();

                    // 拼接所有列文本用于判断行类型（此时单元格中已是占位符）
                    StringBuilder allText = new StringBuilder();
                    for (XWPFTableCell cell : rowCells) {
                        allText.append(cell.getText() != null ? cell.getText().trim() : "");
                    }
                    String allStr = allText.toString();

                    // 判断行类型（根据占位符判断，因为此时单元格中已是占位符）
                    if (allStr.contains("{{_row_subtotal}}")) {
                        // subtotal行（含{{_row_subtotal}}占位符）
                        subtotalRowIndices.add(ri);
                    } else if (allStr.contains("{{_row_group}}")) {
                        // group行（含{{_row_group}}占位符）
                        groupRowIndices.add(ri);
                    } else if (allStr.contains("{{_row_data}}") || allStr.contains("{{_tpl_")) {
                        // data行（含{{_row_data}}或{{_tpl_占位符名}}）
                        dataRowIndices.add(ri);
                    } else {
                        // 其他情况：根据整行合并判断
                        if (isFullRowMerged(row, headerColCount) && ri == rows.size() - 1) {
                            subtotalRowIndices.add(ri);
                        } else {
                            dataRowIndices.add(ri);
                        }
                    }
                }

                // 删除多余的group行（只保留第1个group行），从后往前删避免索引错乱
                if (groupRowIndices.size() > 1) {
                    log.debug("[ReverseEngine-TableClear] TABLE_ROW_TEMPLATE 占位符 '{}' 删除多余group行 {} 行，保留第1行",
                            entry.getPlaceholderName(), groupRowIndices.size() - 1);
                    for (int i = groupRowIndices.size() - 1; i >= 1; i--) {
                        int rowIdx = groupRowIndices.get(i);
                        targetTable.removeRow(rowIdx);
                    }
                }

                // 删除多余的data行（只保留第1个data行），从后往前删避免索引错乱
                if (dataRowIndices.size() > 1) {
                    log.debug("[ReverseEngine-TableClear] TABLE_ROW_TEMPLATE 占位符 '{}' 删除多余data行 {} 行，保留第1行",
                            entry.getPlaceholderName(), dataRowIndices.size() - 1);
                    for (int i = dataRowIndices.size() - 1; i >= 1; i--) {
                        int rowIdx = dataRowIndices.get(i);
                        targetTable.removeRow(rowIdx);
                    }
                }

                // 统计保留的行类型
                int finalGroupRowCount = 0;
                int finalSubtotalRowCount = 0;
                int finalDataRowCount = 0;
                for (int ri = 1; ri < targetTable.getRows().size(); ri++) {
                    XWPFTableRow row = targetTable.getRows().get(ri);
                    StringBuilder rowText = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        rowText.append(cell.getText() != null ? cell.getText().trim() : "");
                    }
                    String rowStr = rowText.toString();
                    if (rowStr.contains("{{_row_group}}")) {
                        finalGroupRowCount++;
                    } else if (rowStr.contains("{{_row_subtotal}}")) {
                        finalSubtotalRowCount++;
                    } else if (rowStr.contains("{{_row_data}}")) {
                        finalDataRowCount++;
                    }
                }

                log.debug("[ReverseEngine-TableClear] TABLE_ROW_TEMPLATE 占位符 '{}' 处理完成，保留 {} 行（表头+{}group+{}data+{}subtotal），tplMark={}",
                        entry.getPlaceholderName(), targetTable.getNumberOfRows(), finalGroupRowCount, finalDataRowCount, finalSubtotalRowCount, tplMark);
            } else {
                // 财务表策略：跳过首列，只清数字列
                for (XWPFTableRow row : rows) {
                    List<XWPFTableCell> cells = row.getTableCells();
                    for (int cIdx = 1; cIdx < cells.size() && cIdx < colCount; cIdx++) {
                        XWPFTableCell cell = cells.get(cIdx);
                        String cellText = cell.getText().trim();
                        if (cellText.isBlank()) continue;
                        // 只清空数字类内容（整数/小数/千分位/百分比/负数）
                        if (!cellText.matches("-?[\\d,]+(\\.[\\d]+)?%?") &&
                                !cellText.matches("-?[\\d,.\\s]+")) continue;

                        // 清空该单元格并写入占位符标记
                        writeCellText(cell, phMark);
                        cellsCleared[0]++;
                    }
                }
            }
            clearedCount += cellsCleared[0];

            // 记录匹配
            MatchedPlaceholder mp = new MatchedPlaceholder();
            mp.setPlaceholderName(entry.getPlaceholderName());
            mp.setExpectedValue("[TABLE_CLEAR]");
            mp.setActualValue("[整表清空]");
            mp.setLocation("表格#" + targetIdx);
            mp.setStatus("confirmed");
            mp.setDataSource(entry.getDataSource());
            mp.setSourceSheet(entry.getSourceSheet());
            mp.setSourceField(null);
            mp.setModuleName(entry.getSourceSheet() != null ? entry.getSourceSheet() : "财务数据");
            mp.setModuleCode(sheetNameToCode(mp.getModuleName()));
            mp.setPositionJson("{\"elementType\":\"table\",\"tableIndex\":" + targetIdx + "}");
            matchedList.add(mp);
            log.info("[ReverseEngine-TableClear] 占位符 '{}' 已清空表格#{}，本次清空{}个单元格（累计{}）",
                    entry.getPlaceholderName(), targetIdx, cellsCleared, clearedCount);
        }
        return clearedCount;
    }

    /**
     * 判断一个 Word 表格是否为独立交易区间表。
     * <p>检测依据：首列任意单元格含"可比公司数量"/"上四分位"/"中位值"/"下四分位"/"四分位区间"关键词。</p>
     */
    private boolean isIndependentRangeTable(XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            List<XWPFTableCell> cells = row.getTableCells();
            if (cells.isEmpty()) continue;
            String firstCellText = cells.get(0).getText().trim();
            if (firstCellText.contains("可比公司数量") || firstCellText.contains("上四分位")
                    || firstCellText.contains("中位值") || firstCellText.contains("下四分位")
                    || firstCellText.contains("四分位区间")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断一个 Word 表格是否为财务数字表（用于 TABLE_CLEAR fallback 定位）。
     *
     * <p>判断规则：
     * <ul>
     *   <li>行数 &ge; 3（避免小表格误判）</li>
     *   <li>列数 &ge; 2（至少有一个数字列）</li>
     *   <li>数据行（跳过首行表头）中，数字内容单元格占比 &ge; 40%</li>
     *   <li><b>排除</b>：若首列任意单元格含 {@code x.x.x} 格式章节编号（如 2.2.2、4.1），则不视为财务表</li>
     *   <li><b>排除</b>：若表格首行含"报告索引"或"章节"文本，则不视为财务表</li>
     *   <li><b>排除</b>：若表格首行含"主要部门"或"人数"，则不视为财务表（部门结构表）</li>
     *   <li><b>排除</b>：若表格首行含"关联交易类型"，则不视为财务表（关联交易汇总表）</li>
     *   <li><b>排除</b>：若首列含"四分位区间"等关键词，则不视为财务表（独立交易区间表）</li>
     * </ul>
     */
    private boolean isFinancialTable(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.size() < 3) return false;
        if (rows.get(0).getTableCells().size() < 2) return false;

        // 排除规则1：首行含"报告索引"或"章节"关键词（同期资料本地文档对照表等）
        String headerRowText = rows.get(0).getTableCells().stream()
                .map(c -> c.getText().trim())
                .collect(java.util.stream.Collectors.joining(" "));
        if (headerRowText.contains("报告索引") || headerRowText.contains("章节")) {
            return false;
        }

        // 排除规则2：首列（含表头行）存在章节编号格式（x.x 或 x.x.x 等）
        java.util.regex.Pattern chapterPattern = java.util.regex.Pattern.compile("^\\d+(\\.\\d+)+$");
        for (XWPFTableRow row : rows) {
            List<XWPFTableCell> cells = row.getTableCells();
            if (cells.isEmpty()) continue;
            String firstCellText = cells.get(0).getText().trim();
            if (chapterPattern.matcher(firstCellText).matches()) {
                return false;
            }
        }

        // 排除规则3（新）：部门结构表（表头含"主要部门"或"人数"关键词）
        if (headerRowText.contains("主要部门") || headerRowText.contains("人数")) {
            return false;
        }

        // 排除规则4：关联交易汇总表（表头含"关联交易类型"关键词）
        if (headerRowText.contains("关联交易类型")) {
            return false;
        }

        // 排除规则4b：关联采购/销售明细表（表头含"关联方名称"关键词）
        if (headerRowText.contains("关联方名称")) {
            return false;
        }

        // 排除规则5：独立交易区间表（首列含四分位/可比公司相关关键词）
        if (isIndependentRangeTable(table)) {
            return false;
        }


        int totalDataCells = 0;
        int numericCells = 0;
        // 跳过首行（表头），从第1行开始统计
        for (int i = 1; i < rows.size(); i++) {
            List<XWPFTableCell> cells = rows.get(i).getTableCells();
            // 跳过首列（通常为描述/项目名文字列）
            for (int j = 1; j < cells.size(); j++) {
                String cellText = cells.get(j).getText().trim();
                if (cellText.isBlank()) continue;
                totalDataCells++;
                if (cellText.matches("-?[\\d,]+(\\.[\\d]+)?%?") ||
                        cellText.matches("-?[\\d,.\\s]+")) {
                    numericCells++;
                }
            }
        }
        if (totalDataCells == 0) return false;
        return (double) numericCells / totalDataCells >= 0.4;
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
     * 检测注册表中有定义但未能自动处理的占位符（用于前端精确提示）。
     *
     * <p>未匹配范围：
     * <ul>
     *   <li>TABLE_CLEAR 类型中，tableClearUnmatched 里的条目（未能定位到财务表格）</li>
     *   <li>DATA_CELL / LONG_TEXT / BVD 类型中，在 matchedList 里找不到对应记录的注册表条目</li>
     * </ul>
     *
     * @param matchedList         实际已匹配的占位符列表
     * @param tableClearUnmatched TABLE_CLEAR 类型中未能定位的条目
     * @return 未能自动处理的注册表条目列表
     */
    private List<RegistryEntry> detectUnmatchedRegistry(List<MatchedPlaceholder> matchedList,
                                                         List<ExcelEntry> tableClearUnmatched) {
        // 收集所有已处理的占位符名称（confirmed 或 uncertain 均算"已处理"）
        Set<String> processedNames = matchedList.stream()
                .map(MatchedPlaceholder::getPlaceholderName)
                .collect(Collectors.toSet());

        // TABLE_CLEAR 未定位的条目名称集合
        Set<String> tableClearUnmatchedNames = tableClearUnmatched.stream()
                .map(ExcelEntry::getPlaceholderName)
                .collect(Collectors.toSet());

        List<RegistryEntry> result = new ArrayList<>();
        for (RegistryEntry reg : PLACEHOLDER_REGISTRY) {
            // TABLE_CLEAR 未定位 → 加入未匹配
            if (reg.getType() == PlaceholderType.TABLE_CLEAR
                    && tableClearUnmatchedNames.contains(reg.getPlaceholderName())) {
                result.add(reg);
                continue;
            }
            // 其他类型：在 matchedList 中找不到 → 加入未匹配
            if (reg.getType() != PlaceholderType.TABLE_CLEAR
                    && !processedNames.contains(reg.getPlaceholderName())) {
                result.add(reg);
            }
        }

        if (!result.isEmpty()) {
            log.warn("[ReverseEngine-Registry] 以下注册表占位符未能自动处理，请前端提示用户手动处理（共{}个）：{}",
                    result.size(),
                    result.stream().map(r -> r.getPlaceholderName() + "（" + r.getDisplayName() + "）").toList());
        }
        return result;
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
        // 页眉段落及表格
        for (XWPFHeader header : doc.getHeaderList()) {
            for (XWPFParagraph paragraph : header.getParagraphs()) {
                mergeRunsInParagraph(paragraph);
            }
            for (XWPFTable table : header.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph paragraph : cell.getParagraphs()) {
                            mergeRunsInParagraph(paragraph);
                        }
                    }
                }
            }
        }
        // 页脚段落及表格
        for (XWPFFooter footer : doc.getFooterList()) {
            for (XWPFParagraph paragraph : footer.getParagraphs()) {
                mergeRunsInParagraph(paragraph);
            }
            for (XWPFTable table : footer.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph paragraph : cell.getParagraphs()) {
                            mergeRunsInParagraph(paragraph);
                        }
                    }
                }
            }
        }
    }

    /**
     * 合并段落内所有 Run 的文本，避免占位符被拆入多个 Run。
     * <p>
     * 使用底层 CTP.getRList() 代替 POI 高层 paragraph.getRuns()，
     * 原因：getRuns() 会跳过含脚注引用（w:footnoteReference）等特殊子元素的 CTRun，
     * 导致含脚注的段落合并不完整，占位符文本（如 {{清单模板-数据表-B5}}）碎片分散于前后两个 Run。
     * <p>
     * 本实现直接遍历 XML 层所有 CTRun，拼接全部 w:t 文本到第一个 CTRun，
     * 清空其余 CTRun 的 w:t（不删除节点，保留脚注引用等结构）。
     */
    private void mergeRunsInParagraph(XWPFParagraph paragraph) {
        List<CTR> ctRuns = paragraph.getCTP().getRList();
        if (ctRuns == null || ctRuns.size() <= 1) return;

        // 拼接所有 CTRun 的 w:t 文本
        StringBuilder sb = new StringBuilder();
        for (CTR ctRun : ctRuns) {
            List<CTText> tList = ctRun.getTList();
            if (tList != null) {
                for (CTText t : tList) {
                    String val = t.getStringValue();
                    sb.append(val != null ? val : "");
                }
            }
        }

        // 写入第一个 CTRun
        CTR firstRun = ctRuns.get(0);
        List<CTText> firstTList = firstRun.getTList();
        CTText firstT;
        if (firstTList != null && !firstTList.isEmpty()) {
            firstT = firstTList.get(0);
            // 清空第一个 CTRun 中多余的 w:t（保留一个）
            for (int i = firstTList.size() - 1; i >= 1; i--) {
                firstRun.removeT(i);
            }
        } else {
            firstT = firstRun.addNewT();
        }
        firstT.setStringValue(sb.toString());
        // 设置 xml:space="preserve" 防止 Word 截断首尾空格
        firstT.setSpace(org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute.Space.PRESERVE);

        // 清空其余 CTRun 的所有 w:t（保留节点结构，不破坏脚注等引用）
        for (int i = 1; i < ctRuns.size(); i++) {
            CTR ctRun = ctRuns.get(i);
            List<CTText> tList = ctRun.getTList();
            if (tList != null) {
                for (CTText t : tList) {
                    t.setStringValue("");
                }
            }
        }
    }

    /**
     * 向单元格写入文本。
     *
     * <p>使用 XML 游标深度遍历段落内的所有 {@code <w:t>} 节点（无论嵌套在什么 XML 结构中），
     * 彻底避免 POI {@code para.getRuns()} 遗漏部分 Run 导致旧文字残留。
     *
     * <ul>
     *   <li>第一个 {@code <w:t>}：设置为目标文本，并添加 {@code xml:space="preserve"} 防止空格截断。</li>
     *   <li>其余 {@code <w:t>}：设置为字符串 ""（保留节点结构，避免破坏脚注引用等）。</li>
     *   <li>若段落内无任何 {@code <w:t>}，则用 {@code para.createRun().setText(text)} 新建 Run 写入。</li>
     *   <li>只向第一个非空段落写入文本；后续段落的所有 {@code <w:t>} 一律清空。</li>
     * </ul>
     *
     * @param cell 目标单元格
     * @param text 要写入的文本（可为空字符串，表示清空）
     */
    private void writeCellText(XWPFTableCell cell, String text) {
        List<XWPFParagraph> paras = cell.getParagraphs();
        if (paras.isEmpty()) {
            if (!text.isEmpty()) {
                XWPFRun run = cell.addParagraph().createRun();
                run.setText(text);
            }
            return;
        }
        final String tNamespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        final String localName = "t";
        boolean written = false;
        for (XWPFParagraph para : paras) {
            // 使用 XML 游标深度遍历所有 <w:t> 节点
            org.apache.xmlbeans.XmlCursor cursor = para.getCTP().newCursor();
            java.util.List<org.apache.xmlbeans.XmlObject> tNodes = new java.util.ArrayList<>();
            try {
                // 先收集所有 <w:t> 节点
                if (cursor.toFirstChild()) {
                    do {
                        if (tNamespace.equals(cursor.getName().getNamespaceURI())
                                && localName.equals(cursor.getName().getLocalPart())) {
                            org.apache.xmlbeans.XmlObject tObj = cursor.getObject();
                            if (tObj != null) tNodes.add(tObj);
                        }
                        // 递归遍历子元素
                        collectTNodes(cursor, tNamespace, localName, tNodes);
                    } while (cursor.toNextSibling());
                }
            } finally {
                cursor.dispose();
            }
            if (tNodes.isEmpty()) {
                // 无 <w:t> 节点：若需要写入，新建 Run
                if (!written && !text.isEmpty()) {
                    XWPFRun run = para.createRun();
                    run.setText(text);
                    written = true;
                }
                continue;
            }
            // 第一个 <w:t>：写入目标文本
            if (!written) {
                org.apache.xmlbeans.XmlObject firstT = tNodes.get(0);
                if (firstT instanceof CTText) {
                    CTText ct = (CTText) firstT;
                    ct.setStringValue(text);
                    ct.setSpace(org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute.Space.PRESERVE);
                } else {
                    // 如果是 XmlAnyType 等其他类型，尝试用 DOM 操作
                    org.apache.xmlbeans.XmlCursor tCursor = firstT.newCursor();
                    try {
                        if (tCursor.toFirstContentToken() != org.apache.xmlbeans.XmlCursor.TokenType.NONE) {
                            tCursor.removeXmlContents();
                        }
                        tCursor.toStartDoc();
                        tCursor.insertChars(text);
                    } finally {
                        tCursor.dispose();
                    }
                }
                written = true;
                // 其余 <w:t>：清空
                for (int i = 1; i < tNodes.size(); i++) {
                    org.apache.xmlbeans.XmlObject tObj = tNodes.get(i);
                    if (tObj instanceof CTText) {
                        ((CTText) tObj).setStringValue("");
                    } else {
                        org.apache.xmlbeans.XmlCursor tc = tObj.newCursor();
                        try {
                            tc.removeXmlContents();
                            tc.toStartDoc();
                            tc.insertChars("");
                        } finally {
                            tc.dispose();
                        }
                    }
                }
            } else {
                // 后续段落：所有 <w:t> 清空
                for (org.apache.xmlbeans.XmlObject tObj : tNodes) {
                    if (tObj instanceof CTText) {
                        ((CTText) tObj).setStringValue("");
                    } else {
                        org.apache.xmlbeans.XmlCursor tc = tObj.newCursor();
                        try {
                            tc.removeXmlContents();
                            tc.toStartDoc();
                            tc.insertChars("");
                        } finally {
                            tc.dispose();
                        }
                    }
                }
            }
        }
    }

    /**
     * 递归收集游标下的所有 <w:t> 节点。
     */
    private void collectTNodes(org.apache.xmlbeans.XmlCursor cursor, String tNamespace,
                               String localName, java.util.List<org.apache.xmlbeans.XmlObject> tNodes) {
        org.apache.xmlbeans.XmlCursor child = cursor.newCursor();
        try {
            if (child.toFirstChild()) {
                do {
                    if (tNamespace.equals(child.getName().getNamespaceURI())
                            && localName.equals(child.getName().getLocalPart())) {
                        org.apache.xmlbeans.XmlObject tObj = child.getObject();
                        if (tObj != null) tNodes.add(tObj);
                    }
                    // 递归
                    collectTNodes(child, tNamespace, localName, tNodes);
                } while (child.toNextSibling());
            }
        } finally {
            child.dispose();
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 判断一行是否是"整行合并"（即该行所有单元格的gridSpan之和等于表头列数，且单元格数少于表头列数）。
     * <p>
     * 用于区分：
     * - group行：整行合并的分类标题行（如"关联方A"横跨整表）
     * - subtotal行：整行合并的合计行（末行）
     * - data行：普通数据行（单元格数=表头列数，无合并）
     * </p>
     *
     * @param row 表格行
     * @param headerColCount 表头列数（标准列数）
     * @return true表示该行是整行合并（所有单元格的gridSpan之和等于headerColCount）
     */
    private boolean isFullRowMerged(XWPFTableRow row, int headerColCount) {
        List<XWPFTableCell> cells = row.getTableCells();
        // 如果单元格数等于表头列数，说明没有合并（或每列都是独立单元格）
        if (cells.size() == headerColCount) {
            return false;
        }
        // 计算所有单元格的gridSpan之和
        int totalSpan = 0;
        for (XWPFTableCell cell : cells) {
            totalSpan += getCellGridSpan(cell);
        }
        // 如果gridSpan之和等于表头列数，说明是整行合并
        return totalSpan == headerColCount;
    }

    /**
     * 获取单元格的gridSpan值（跨列数）。
     * <p>通过CTTcPr获取gridSpan，默认为1（无跨列）。</p>
     *
     * @param cell 表格单元格
     * @return gridSpan值，默认为1
     */
    private int getCellGridSpan(XWPFTableCell cell) {
        try {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc tc = cell.getCTTc();
            if (tc != null && tc.isSetTcPr()) {
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr tcPr = tc.getTcPr();
                if (tcPr.isSetGridSpan()) {
                    return tcPr.getGridSpan().getVal().intValue();
                }
            }
        } catch (Exception e) {
            log.debug("[ReverseEngine] 获取单元格gridSpan失败: {}", e.getMessage());
        }
        return 1; // 默认无跨列
    }

    /**
     * 判断一行是否是group行（分组标题行）。
     * <p>
     * Group行特征：
     * - 第一列有内容（如"境外采购"）
     * - 第一列是垂直合并的起始行（vMerge=restart）
     * - 后续列可能为空或被合并
     * - 不是subtotal行（不包含"小计/合计/总计"关键词）
     * </p>
     *
     * @param row 表格行
     * @return true表示该行是group行
     */
    private boolean isGroupRow(XWPFTableRow row) {
        List<XWPFTableCell> cells = row.getTableCells();
        if (cells.isEmpty()) {
            return false;
        }

        // 获取第一列
        XWPFTableCell firstCell = cells.get(0);
        String firstCellText = firstCell.getText() != null ? firstCell.getText().trim() : "";

        // 第一列为空，不是group行
        if (firstCellText.isEmpty()) {
            return false;
        }

        // 如果包含"小计/合计/总计"，则是subtotal行，不是group行
        if (firstCellText.contains("小计") || firstCellText.contains("合计") || firstCellText.contains("总计")) {
            return false;
        }

        // 检查第一列是否是垂直合并的起始行（vMerge=restart 或 val=null）
        // 只有垂直合并的起始行才是 group 行，延续行（continue）不是
        try {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc tc = firstCell.getCTTc();
            if (tc != null && tc.isSetTcPr()) {
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr tcPr = tc.getTcPr();
                if (tcPr.isSetVMerge()) {
                    Object vMergeObj = tcPr.getVMerge();
                    if (vMergeObj != null) {
                        String vMergeStr = vMergeObj.toString().toLowerCase();
                        // 只有不是 continue 的才是起始行（restart 或 null）
                        if (!vMergeStr.contains("continue")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[ReverseEngine] 检查垂直合并失败: {}", e.getMessage());
        }

        // 检查是否是"整行合并"的情况（第一列有内容，其他列被合并）
        if (cells.size() == 1) {
            // 只有一列单元格，可能是整行合并的情况
            return true;
        }

        // 检查第一列之后的所有列是否为空或被合并
        boolean otherColumnsEmpty = true;
        for (int i = 1; i < cells.size(); i++) {
            String cellText = cells.get(i).getText() != null ? cells.get(i).getText().trim() : "";
            if (!cellText.isEmpty()) {
                otherColumnsEmpty = false;
                break;
            }
        }

        // 如果后续列为空且第一列有内容，可能是group行
        return otherColumnsEmpty;
    }

    /**
     * 判断某行是否是垂直合并的延续行（第一列单元格是vMerge continue）。
     * 这类行是group行的从属行，在子模板中不需要保留。
     *
     * @param row 表格行
     * @return true表示该行的第一列是垂直合并延续单元格（应被删除）
     */
    private boolean isVMergeContinueRow(XWPFTableRow row) {
        List<XWPFTableCell> cells = row.getTableCells();
        if (cells.isEmpty()) {
            return false;
        }
        // 判断第一列是否是垂直合并延续单元格
        return isVerticalMergeContinue(cells.get(0));
    }

    /**
     * 判断单元格是否是垂直合并的延续单元格（vMerge=continue）。
     * <p>
     * 垂直合并的单元格在Word XML中表示为：
     * - 起始单元格：vMerge="restart" 或 vMerge属性存在但val为null
     * - 延续单元格：vMerge="continue"
     * </p>
     *
     * @param cell 单元格
     * @return true表示该单元格是垂直合并的延续部分（不应单独写入标记）
     */
    private boolean isVerticalMergeContinue(XWPFTableCell cell) {
        try {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc tc = cell.getCTTc();
            if (tc != null && tc.isSetTcPr()) {
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr tcPr = tc.getTcPr();
                if (tcPr.isSetVMerge()) {
                    Object vMergeObj = tcPr.getVMerge();
                    if (vMergeObj != null) {
                        String vMergeStr = vMergeObj.toString().toLowerCase();
                        // 只有明确包含continue的才是延续单元格
                        // restart或null表示起始单元格
                        return vMergeStr.contains("continue");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[ReverseEngine] 检查垂直合并延续失败: {}", e.getMessage());
        }
        return false;
    }

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
