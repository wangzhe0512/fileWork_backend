-- V4: 新增企业子模板占位符状态表
-- 用于持久化每个子模板中占位符的确认状态（支持分次确认和草稿保存）

CREATE TABLE IF NOT EXISTS company_template_placeholder (
    id VARCHAR(36) PRIMARY KEY COMMENT '主键ID',
    company_template_id VARCHAR(36) NOT NULL COMMENT '子模板ID',
    placeholder_name VARCHAR(100) NOT NULL COMMENT '占位符名称',
    status VARCHAR(20) NOT NULL DEFAULT 'uncertain' COMMENT '状态: uncertain(待确认)/confirmed(已确认)/ignored(忽略)',
    confirmed_type VARCHAR(20) COMMENT '确认后的类型: text/table/chart/image',
    position_json TEXT COMMENT '位置信息JSON: {"paragraphIndex":0,"runIndex":1,"offset":10,"elementType":"paragraph"}',
    expected_value VARCHAR(500) COMMENT '期望值（从Excel读取）',
    actual_value VARCHAR(500) COMMENT '实际值（在Word中找到）',
    reason VARCHAR(200) COMMENT '冲突/不确定原因',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_template_id (company_template_id),
    INDEX idx_template_name (company_template_id, placeholder_name),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业子模板占位符状态表';
