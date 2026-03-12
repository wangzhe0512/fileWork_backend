-- V7: 为 company_template_placeholder 表新增 confidence 字段（大模型置信度）
-- confidence: 0.0~1.0，NULL 表示未经过大模型分析（旧引擎生成的数据）
-- 低于阈值（默认0.8）的占位符在前端标记为低置信度提示

ALTER TABLE company_template_placeholder
    ADD COLUMN confidence FLOAT DEFAULT NULL COMMENT '大模型置信度(0.0~1.0)，NULL表示旧引擎生成，<阈值时前端标记低置信度提示';

-- 新增索引，便于查询低置信度占位符
CREATE INDEX idx_ctp_confidence ON company_template_placeholder (confidence);
