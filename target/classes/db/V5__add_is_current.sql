-- V5: 企业子模板表新增 is_current 字段，分离"当前使用"与"归档"状态
-- 用于区分模板的生命周期状态(status)和当前使用状态(is_current)

-- 1. 新增 is_current 字段（如果不存在）
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
               WHERE TABLE_NAME = 'company_template' AND COLUMN_NAME = 'is_current' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template ADD COLUMN is_current TINYINT(1) NOT NULL DEFAULT 0 COMMENT "是否为当前使用版本：1-是，0-否"',
    'SELECT "Column is_current already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 为 is_current 添加索引（如果不存在）
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS 
               WHERE TABLE_NAME = 'company_template' AND INDEX_NAME = 'idx_is_current' 
               AND TABLE_SCHEMA = DATABASE());
SET @sql := IF(@exist = 0, 
    'ALTER TABLE company_template ADD KEY idx_is_current (is_current)',
    'SELECT "Index idx_is_current already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 数据迁移：将现有 status='active' 的记录中，按企业+年度分组，每组创建时间最新的一条设为 is_current=true
-- 注意：这只是一种合理的默认策略，实际业务中应由前端调用 set-active 接口手动设置
UPDATE company_template ct
JOIN (
    SELECT company_id, year, MAX(created_at) as max_created_at
    FROM company_template
    WHERE status = 'active' AND deleted = 0
    GROUP BY company_id, year
) latest ON ct.company_id = latest.company_id 
    AND ct.year = latest.year 
    AND ct.created_at = latest.max_created_at
SET ct.is_current = 1
WHERE ct.status = 'active' AND ct.deleted = 0;
