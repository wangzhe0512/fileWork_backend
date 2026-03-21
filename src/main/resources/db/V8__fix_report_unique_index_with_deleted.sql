-- V8：修复 report 表唯一索引策略
-- 问题：原索引 uk_report_company_year_editing(company_id, year, status) 未包含 deleted 列，
--       软删除后旧记录仍占用索引槽位，导致再次 INSERT 报唯一索引冲突。
-- 修复：删除旧索引，改用虚拟生成列 editing_key 实现仅对 editing+deleted=0 施加唯一约束，
--       NULL 值不参与 UNIQUE 约束，history 多条记录和软删除记录均不冲突。

-- 第1步：清理脏数据
UPDATE report SET deleted = 1, updated_at = NOW()
WHERE status = 'editing' AND deleted = 0;

-- 第2步：条件删除旧索引（索引已不存在时跳过）
DROP PROCEDURE IF EXISTS v8_drop_old_index;
CREATE PROCEDURE v8_drop_old_index()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'report'
          AND INDEX_NAME = 'uk_report_company_year_editing'
    ) THEN
        ALTER TABLE report DROP INDEX `uk_report_company_year_editing`;
    END IF;
END;
CALL v8_drop_old_index();
DROP PROCEDURE IF EXISTS v8_drop_old_index;

-- 第3步：条件添加虚拟生成列
DROP PROCEDURE IF EXISTS v8_add_editing_key_col;
CREATE PROCEDURE v8_add_editing_key_col()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'report'
          AND COLUMN_NAME = 'editing_key'
    ) THEN
        ALTER TABLE report
            ADD COLUMN `editing_key` VARCHAR(120)
                GENERATED ALWAYS AS (
                    IF(status = 'editing' AND deleted = 0, CONCAT(company_id, '-', year), NULL)
                ) VIRTUAL;
    END IF;
END;
CALL v8_add_editing_key_col();
DROP PROCEDURE IF EXISTS v8_add_editing_key_col;

-- 第4步：条件新建唯一索引
DROP PROCEDURE IF EXISTS v8_add_editing_key_idx;
CREATE PROCEDURE v8_add_editing_key_idx()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'report'
          AND INDEX_NAME = 'uk_report_editing_key'
    ) THEN
        ALTER TABLE report ADD UNIQUE KEY `uk_report_editing_key` (`editing_key`);
    END IF;
END;
CALL v8_add_editing_key_idx();
DROP PROCEDURE IF EXISTS v8_add_editing_key_idx;
