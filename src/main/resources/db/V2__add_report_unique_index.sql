-- P2-10：为 report 表添加唯一索引，防止并发竞态条件导致同一 company_id+year 产生多条 editing 记录
-- 注意：执行前请确认 report 表中不存在重复数据，否则需先清理重复记录

-- 添加唯一索引（仅针对 editing 状态使用部分索引，其他状态允许同 company+year 多条 history 记录）
-- MySQL 不直接支持部分索引，使用 filtered approach：
-- 如果数据库引擎为 MySQL 8.0+，可以使用函数索引实现软约束
-- 以下方案：在业务层已通过 @Transactional + DuplicateKeyException 处理，此唯一索引作为数据库级别保障

-- 若 report 表目前已有数据，执行前确认无冲突：
-- SELECT company_id, year, status, COUNT(*) FROM report GROUP BY company_id, year, status HAVING COUNT(*) > 1;

-- 为 editing 状态的报告添加唯一约束（使用唯一索引 + 代码层面过滤）
ALTER TABLE report ADD UNIQUE KEY `uk_report_company_year_editing` (company_id, year, status);

-- 注意：此唯一索引会限制同一 company_id + year 只能有一条 history 记录
-- 若业务允许多条 history（归档多版本），请改用以下方案：
-- 删除上面的唯一索引，改在代码层用分布式锁（Redis setNX）控制并发
