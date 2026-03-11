-- 修复 Flyway V6 checksum 不匹配问题
-- 执行此 SQL 后重启服务

UPDATE flyway_schema_history 
SET checksum = -1481277134 
WHERE version = '6';
