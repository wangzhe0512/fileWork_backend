-- ============================================================
-- V3：重新设计模板系统
-- 新增：system_template, system_module, system_placeholder, company_template
-- 修改：report 表新增 template_id, generation_status, generation_error
-- 旧表 template / placeholder / report_module 保留不动
-- ============================================================

-- ============================================================
-- 1. 系统标准模板表（系统级，不绑定租户）
-- ============================================================
CREATE TABLE IF NOT EXISTS system_template (
  id               VARCHAR(36)   NOT NULL PRIMARY KEY COMMENT '主键UUID',
  name             VARCHAR(256)  NOT NULL COMMENT '模板名称',
  version          VARCHAR(32)   NOT NULL DEFAULT '1.0' COMMENT '版本号',
  word_file_path   VARCHAR(512)  DEFAULT NULL COMMENT '标准Word模板文件路径',
  list_excel_path  VARCHAR(512)  DEFAULT NULL COMMENT '清单Excel模板文件路径',
  bvd_excel_path   VARCHAR(512)  DEFAULT NULL COMMENT 'BVD数据Excel模板文件路径',
  status           VARCHAR(16)   NOT NULL DEFAULT 'active' COMMENT 'active|archived',
  description      VARCHAR(512)  DEFAULT '' COMMENT '描述',
  created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted          TINYINT(1)    NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统标准模板（系统级，不绑定租户）';

-- ============================================================
-- 2. 系统模块表（系统级，不绑定租户）
-- ============================================================
CREATE TABLE IF NOT EXISTS system_module (
  id                 VARCHAR(36)   NOT NULL PRIMARY KEY COMMENT '主键UUID',
  system_template_id VARCHAR(36)   NOT NULL COMMENT '关联系统模板ID',
  name               VARCHAR(128)  NOT NULL COMMENT '模块名称',
  code               VARCHAR(64)   NOT NULL COMMENT '模块编码（如：基本信息、关联交易）',
  description        VARCHAR(512)  DEFAULT '' COMMENT '描述',
  sort               INT           NOT NULL DEFAULT 0 COMMENT '排序',
  deleted            TINYINT(1)    NOT NULL DEFAULT 0,
  KEY idx_system_template_id (system_template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统模块（系统级，不绑定租户）';

-- ============================================================
-- 3. 系统占位符规则表（系统级，不绑定租户）
-- ============================================================
CREATE TABLE IF NOT EXISTS system_placeholder (
  id                 VARCHAR(36)   NOT NULL PRIMARY KEY COMMENT '主键UUID',
  system_template_id VARCHAR(36)   NOT NULL COMMENT '关联系统模板ID',
  module_code        VARCHAR(64)   DEFAULT '' COMMENT '所属模块编码',
  name               VARCHAR(256)  NOT NULL COMMENT '占位符完整名称，如：清单模板-数据表-B3',
  display_name       VARCHAR(256)  DEFAULT '' COMMENT '显示名称',
  type               VARCHAR(16)   NOT NULL DEFAULT 'text' COMMENT 'text|table|chart|image',
  data_source        VARCHAR(16)   NOT NULL DEFAULT 'list' COMMENT 'list|bvd',
  source_sheet       VARCHAR(128)  DEFAULT '' COMMENT 'Excel Sheet名称',
  source_field       VARCHAR(64)   DEFAULT '' COMMENT '单元格地址，如：B3',
  chart_type         VARCHAR(16)   DEFAULT NULL COMMENT '图表类型：bar|line|pie（type=chart时有效）',
  sort               INT           NOT NULL DEFAULT 0 COMMENT '排序',
  description        VARCHAR(512)  DEFAULT '' COMMENT '描述',
  deleted            TINYINT(1)    NOT NULL DEFAULT 0,
  KEY idx_system_template_id (system_template_id),
  KEY idx_module_code (module_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统占位符规则（系统级，不绑定租户）';

-- ============================================================
-- 4. 企业子模板表（企业级，绑定租户）
-- ============================================================
CREATE TABLE IF NOT EXISTS company_template (
  id                 VARCHAR(36)   NOT NULL PRIMARY KEY COMMENT '主键UUID',
  tenant_id          VARCHAR(36)   NOT NULL COMMENT '租户ID',
  company_id         VARCHAR(36)   NOT NULL COMMENT '企业ID',
  system_template_id VARCHAR(36)   NOT NULL COMMENT '关联系统模板ID',
  name               VARCHAR(256)  NOT NULL COMMENT '子模板名称',
  year               INT           NOT NULL COMMENT '来源年份',
  source_report_id   VARCHAR(36)   DEFAULT NULL COMMENT '来源历史报告ID（反向生成时的历史报告）',
  file_path          VARCHAR(512)  DEFAULT NULL COMMENT '子模板Word文件路径',
  file_size          VARCHAR(32)   DEFAULT NULL COMMENT '文件大小显示',
  status             VARCHAR(16)   NOT NULL DEFAULT 'active' COMMENT 'active|archived',
  created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME      DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  deleted            TINYINT(1)    NOT NULL DEFAULT 0,
  KEY idx_tenant_company (tenant_id, company_id),
  KEY idx_company_year (company_id, year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业子模板（企业级）';

-- ============================================================
-- 5. 修改 report 表：新增 template_id, generation_status, generation_error
-- ============================================================
ALTER TABLE report
  ADD COLUMN template_id        VARCHAR(36)   DEFAULT NULL COMMENT '关联企业子模板ID（company_template.id）' AFTER company_id,
  ADD COLUMN generation_status  VARCHAR(16)   DEFAULT NULL COMMENT '生成状态：pending|running|success|failed' AFTER is_manual_upload,
  ADD COLUMN generation_error   TEXT          DEFAULT NULL COMMENT '生成失败错误信息' AFTER generation_status;

-- 为 template_id 添加索引
ALTER TABLE report ADD KEY idx_template_id (template_id);
