-- V6: 新增企业子模板模块表，扩展占位符表字段
-- 实现模块化管理占位符，支持批量同步功能

-- 1. 创建企业子模板模块表
CREATE TABLE IF NOT EXISTS company_template_module (
    id VARCHAR(36) PRIMARY KEY COMMENT '主键ID',
    company_template_id VARCHAR(36) NOT NULL COMMENT '子模板ID',
    code VARCHAR(100) NOT NULL COMMENT '模块编码（由Sheet名转换）',
    name VARCHAR(100) NOT NULL COMMENT '模块名称（Sheet原名）',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序序号',
    description VARCHAR(500) COMMENT '模块说明',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志：0-未删除，1-已删除',
    
    INDEX idx_template_id (company_template_id),
    INDEX idx_code (company_template_id, code),
    INDEX idx_sort (company_template_id, sort),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业子模板模块表';

-- 2. 扩展企业子模板占位符表字段（逐个判断是否存在）
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
               WHERE TABLE_NAME = 'company_template_placeholder' AND COLUMN_NAME = 'module_id' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template_placeholder ADD COLUMN module_id VARCHAR(36) COMMENT "所属模块ID" AFTER company_template_id',
    'SELECT "Column module_id already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
               WHERE TABLE_NAME = 'company_template_placeholder' AND COLUMN_NAME = 'name' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template_placeholder ADD COLUMN name VARCHAR(200) COMMENT "占位符显示名称" AFTER module_id',
    'SELECT "Column name already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
               WHERE TABLE_NAME = 'company_template_placeholder' AND COLUMN_NAME = 'type' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template_placeholder ADD COLUMN type VARCHAR(20) COMMENT "类型：text/table/chart/image/ignore" AFTER name',
    'SELECT "Column type already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
               WHERE TABLE_NAME = 'company_template_placeholder' AND COLUMN_NAME = 'data_source' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template_placeholder ADD COLUMN data_source VARCHAR(100) COMMENT "数据源标识" AFTER type',
    'SELECT "Column data_source already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
               WHERE TABLE_NAME = 'company_template_placeholder' AND COLUMN_NAME = 'source_sheet' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template_placeholder ADD COLUMN source_sheet VARCHAR(100) COMMENT "来源Sheet名称" AFTER data_source',
    'SELECT "Column source_sheet already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
               WHERE TABLE_NAME = 'company_template_placeholder' AND COLUMN_NAME = 'source_field' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template_placeholder ADD COLUMN source_field VARCHAR(100) COMMENT "来源字段名称" AFTER source_sheet',
    'SELECT "Column source_field already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
               WHERE TABLE_NAME = 'company_template_placeholder' AND COLUMN_NAME = 'description' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template_placeholder ADD COLUMN description VARCHAR(500) COMMENT "占位符说明" AFTER source_field',
    'SELECT "Column description already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
               WHERE TABLE_NAME = 'company_template_placeholder' AND COLUMN_NAME = 'sort' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template_placeholder ADD COLUMN sort INT NOT NULL DEFAULT 0 COMMENT "排序序号" AFTER description',
    'SELECT "Column sort already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 为新增字段添加索引（如果不存在）
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS 
               WHERE TABLE_NAME = 'company_template_placeholder' AND INDEX_NAME = 'idx_module_id' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template_placeholder ADD INDEX idx_module_id (module_id)',
    'SELECT "Index idx_module_id already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS 
               WHERE TABLE_NAME = 'company_template_placeholder' AND INDEX_NAME = 'idx_sort' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template_placeholder ADD INDEX idx_sort (company_template_id, sort)',
    'SELECT "Index idx_sort already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS 
               WHERE TABLE_NAME = 'company_template_placeholder' AND INDEX_NAME = 'idx_type' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template_placeholder ADD INDEX idx_type (type)',
    'SELECT "Index idx_type already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 添加外键约束（可选，视业务需求）
-- ALTER TABLE company_template_placeholder
-- ADD CONSTRAINT fk_module_id 
-- FOREIGN KEY (module_id) REFERENCES company_template_module(id) ON DELETE CASCADE;

-- 5. 为模块表添加逻辑删除字段（如果不存在）
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
               WHERE TABLE_NAME = 'company_template_module' AND COLUMN_NAME = 'deleted' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template_module ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT "逻辑删除标志：0-未删除，1-已删除" AFTER updated_at',
    'SELECT "Column deleted already exists in company_template_module"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6. 为占位符表添加逻辑删除字段（如果不存在）
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
               WHERE TABLE_NAME = 'company_template_placeholder' AND COLUMN_NAME = 'deleted' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template_placeholder ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT "逻辑删除标志：0-未删除，1-已删除" AFTER updated_at',
    'SELECT "Column deleted already exists in company_template_placeholder"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
