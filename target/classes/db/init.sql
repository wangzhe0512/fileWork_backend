-- ============================================================
-- 企业文件处理平台 — 数据库初始化脚本
-- 数据库：file_proc_db  字符集：utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS file_proc_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE file_proc_db;

-- ============================================================
-- 1. 超管账号表（全局，无 tenant_id）
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_admin (
  id           VARCHAR(36)  NOT NULL PRIMARY KEY COMMENT '主键UUID',
  username     VARCHAR(64)  NOT NULL UNIQUE COMMENT '超管用户名',
  password     VARCHAR(128) NOT NULL COMMENT 'BCrypt加密密码',
  real_name    VARCHAR(64)  DEFAULT '' COMMENT '真实姓名',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='超管账号';

-- ============================================================
-- 2. 租户表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_tenant (
  id           VARCHAR(36)  NOT NULL PRIMARY KEY,
  name         VARCHAR(128) NOT NULL COMMENT '租户名称',
  code         VARCHAR(64)  NOT NULL UNIQUE COMMENT '租户编码',
  status       VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active|disabled',
  admin_count  INT          NOT NULL DEFAULT 0,
  logo_url     VARCHAR(512) DEFAULT NULL,
  description  VARCHAR(512) DEFAULT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户表';

-- ============================================================
-- 3. 权限表（全局，无 tenant_id）
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_permission (
  id           VARCHAR(36)  NOT NULL PRIMARY KEY,
  name         VARCHAR(64)  NOT NULL COMMENT '权限名称',
  code         VARCHAR(128) NOT NULL UNIQUE COMMENT '权限code如 company:read',
  type         VARCHAR(16)  NOT NULL DEFAULT 'action' COMMENT 'menu|action',
  parent_id    VARCHAR(36)  DEFAULT NULL,
  path         VARCHAR(256) DEFAULT NULL COMMENT '路由路径',
  icon         VARCHAR(64)  DEFAULT NULL,
  sort         INT          NOT NULL DEFAULT 0,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表（全局共享）';

-- ============================================================
-- 4. 用户表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_user (
  id           VARCHAR(36)  NOT NULL PRIMARY KEY,
  tenant_id    VARCHAR(36)  NOT NULL,
  username     VARCHAR(64)  NOT NULL COMMENT '登录名',
  real_name    VARCHAR(64)  DEFAULT '' COMMENT '真实姓名',
  password     VARCHAR(128) NOT NULL COMMENT 'BCrypt',
  email        VARCHAR(128) DEFAULT '',
  phone        VARCHAR(32)  DEFAULT '',
  role_id      VARCHAR(36)  DEFAULT NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active|disabled',
  avatar       VARCHAR(512) DEFAULT NULL,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  last_login_at  DATETIME     DEFAULT NULL COMMENT '最后登录时间',
  last_login_ip  VARCHAR(64)  DEFAULT NULL COMMENT '最后登录IP',
  deleted        TINYINT(1)   NOT NULL DEFAULT 0,
  UNIQUE KEY uk_tenant_username (tenant_id, username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户用户表';

-- ============================================================
-- 5. 角色表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_role (
  id           VARCHAR(36)  NOT NULL PRIMARY KEY,
  tenant_id    VARCHAR(36)  NOT NULL,
  name         VARCHAR(64)  NOT NULL,
  code         VARCHAR(64)  NOT NULL,
  description  VARCHAR(256) DEFAULT '',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0,
  UNIQUE KEY uk_tenant_role_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- ============================================================
-- 6. 角色权限关联表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_role_permission (
  id               VARCHAR(36)  NOT NULL PRIMARY KEY,
  role_id          VARCHAR(36)  NOT NULL,
  permission_code  VARCHAR(128) NOT NULL,
  KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联';

-- ============================================================
-- 7. 操作日志表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_log (
  id           VARCHAR(36)   NOT NULL PRIMARY KEY,
  tenant_id    VARCHAR(36)   NOT NULL,
  user_id      VARCHAR(36)   DEFAULT '',
  user_name    VARCHAR(64)   DEFAULT '',
  action       VARCHAR(64)   NOT NULL COMMENT '操作动作',
  module       VARCHAR(64)   NOT NULL COMMENT '操作模块',
  detail       TEXT          COMMENT '操作详情',
  ip           VARCHAR(64)   DEFAULT '',
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志';

-- ============================================================
-- 8. 系统配置表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_config (
  id             VARCHAR(36)  NOT NULL PRIMARY KEY,
  tenant_id      VARCHAR(36)  NOT NULL UNIQUE COMMENT '每个租户一条',
  site_name      VARCHAR(128) NOT NULL DEFAULT '文件解析处理平台',
  logo_url       VARCHAR(512) DEFAULT '',
  icp            VARCHAR(128) DEFAULT '',
  max_file_size  INT          NOT NULL DEFAULT 52428800 COMMENT '50MB in bytes'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置';

-- ============================================================
-- 9. 企业表
-- ============================================================
CREATE TABLE IF NOT EXISTS company (
  id               VARCHAR(36)   NOT NULL PRIMARY KEY,
  tenant_id        VARCHAR(36)   NOT NULL,
  name             VARCHAR(256)  NOT NULL COMMENT '企业全称',
  alias            VARCHAR(128)  DEFAULT '' COMMENT '别名',
  industry         VARCHAR(64)   DEFAULT '',
  tax_id           VARCHAR(64)   DEFAULT '' COMMENT '纳税人识别号',
  establish_date   DATE          DEFAULT NULL,
  address          VARCHAR(512)  DEFAULT '',
  business_scope   TEXT          COMMENT '经营范围',
  created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted          TINYINT(1)    NOT NULL DEFAULT 0,
  KEY idx_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业档案';

-- ============================================================
-- 10. 企业联系人表
-- ============================================================
CREATE TABLE IF NOT EXISTS company_contact (
  id           VARCHAR(36)  NOT NULL PRIMARY KEY,
  company_id   VARCHAR(36)  NOT NULL,
  tenant_id    VARCHAR(36)  NOT NULL,
  name         VARCHAR(64)  NOT NULL,
  position     VARCHAR(64)  DEFAULT '',
  phone        VARCHAR(32)  DEFAULT '',
  email        VARCHAR(128) DEFAULT '',
  KEY idx_company_id (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业联系人';

-- ============================================================
-- 11. 数据文件表
-- ============================================================
CREATE TABLE IF NOT EXISTS data_file (
  id           VARCHAR(36)  NOT NULL PRIMARY KEY,
  tenant_id    VARCHAR(36)  NOT NULL,
  company_id   VARCHAR(36)  NOT NULL,
  name         VARCHAR(256) NOT NULL,
  type         VARCHAR(16)  NOT NULL COMMENT 'list|bvd',
  year         INT          NOT NULL,
  size         VARCHAR(32)  DEFAULT '' COMMENT '文件大小显示字符串',
  file_path    VARCHAR(512) NOT NULL COMMENT '服务器存储路径',
  upload_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0,
  KEY idx_company_year (company_id, year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据文件';

-- ============================================================
-- 12. 报告表
-- ============================================================
CREATE TABLE IF NOT EXISTS report (
  id                VARCHAR(36)   NOT NULL PRIMARY KEY,
  tenant_id         VARCHAR(36)   NOT NULL,
  company_id        VARCHAR(36)   NOT NULL,
  name              VARCHAR(256)  NOT NULL,
  year              INT           NOT NULL,
  status            VARCHAR(16)   NOT NULL DEFAULT 'editing' COMMENT 'history|editing',
  is_manual_upload  TINYINT(1)    NOT NULL DEFAULT 0,
  file_path         VARCHAR(512)  DEFAULT NULL,
  file_size         VARCHAR(32)   DEFAULT NULL,
  created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME      DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  deleted           TINYINT(1)    NOT NULL DEFAULT 0,
  KEY idx_company_year (company_id, year),
  KEY idx_tenant_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报告表';

-- ============================================================
-- 13. 模板表
-- ============================================================
CREATE TABLE IF NOT EXISTS template (
  id           VARCHAR(36)  NOT NULL PRIMARY KEY,
  tenant_id    VARCHAR(36)  NOT NULL,
  company_id   VARCHAR(36)  NOT NULL,
  name         VARCHAR(256) NOT NULL,
  year         INT          NOT NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active|archived',
  description  VARCHAR(512) DEFAULT '',
  file_path    VARCHAR(512) DEFAULT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报告模板';

-- ============================================================
-- 14. 占位符表
-- ============================================================
CREATE TABLE IF NOT EXISTS placeholder (
  id            VARCHAR(36)  NOT NULL PRIMARY KEY,
  tenant_id     VARCHAR(36)  NOT NULL,
  company_id    VARCHAR(36)  NOT NULL,
  name          VARCHAR(128) NOT NULL,
  type          VARCHAR(16)  NOT NULL DEFAULT 'text' COMMENT 'text|table|chart',
  data_source   VARCHAR(16)  NOT NULL DEFAULT 'list' COMMENT 'list|bvd',
  source_sheet  VARCHAR(128) DEFAULT '',
  source_field  VARCHAR(128) DEFAULT '',
  chart_type    VARCHAR(16)  DEFAULT NULL COMMENT 'bar|line|pie',
  description   VARCHAR(512) DEFAULT '',
  deleted       TINYINT(1)   NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='占位符定义';

-- ============================================================
-- 15. 报告模块表
-- ============================================================
CREATE TABLE IF NOT EXISTS report_module (
  id            VARCHAR(36)   NOT NULL PRIMARY KEY,
  tenant_id     VARCHAR(36)   NOT NULL,
  company_id    VARCHAR(36)   NOT NULL,
  name          VARCHAR(128)  NOT NULL,
  code          VARCHAR(64)   NOT NULL,
  description   VARCHAR(512)  DEFAULT '',
  placeholders  JSON          COMMENT '占位符id列表',
  sort          INT           NOT NULL DEFAULT 0,
  deleted       TINYINT(1)    NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报告模块';

-- ============================================================
-- 16. 消息通知表
-- ============================================================
CREATE TABLE IF NOT EXISTS notice (
  id                VARCHAR(36)   NOT NULL PRIMARY KEY,
  tenant_id         VARCHAR(36)   NOT NULL,
  title             VARCHAR(256)  NOT NULL,
  content           TEXT,
  target_type       VARCHAR(16)   NOT NULL DEFAULT 'all' COMMENT 'all|role',
  target_role_ids   JSON          DEFAULT NULL,
  published_by      VARCHAR(36)   DEFAULT '',
  published_by_name VARCHAR(64)   DEFAULT '',
  status            VARCHAR(16)   NOT NULL DEFAULT 'draft' COMMENT 'published|draft',
  read_count        INT           NOT NULL DEFAULT 0,
  total_count       INT           NOT NULL DEFAULT 0,
  created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME      DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  deleted           TINYINT(1)    NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息通知';

-- ============================================================
-- 17. 通知用户关联表（投送记录）
-- ============================================================
CREATE TABLE IF NOT EXISTS notice_user (
  id           VARCHAR(36)  NOT NULL PRIMARY KEY,
  notice_id    VARCHAR(36)  NOT NULL,
  tenant_id    VARCHAR(36)  NOT NULL,
  user_id      VARCHAR(36)  NOT NULL,
  is_read      TINYINT(1)   NOT NULL DEFAULT 0,
  read_at      DATETIME     DEFAULT NULL,
  UNIQUE KEY uk_notice_user (notice_id, user_id),
  KEY idx_user_read (user_id, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知投送记录';


-- ============================================================
-- 初始数据
-- ============================================================

-- 超管账号（密码：admin@123，BCrypt加密）
INSERT INTO sys_admin (id, username, password, real_name) VALUES
('admin-001', 'superadmin', '$2b$10$5HP2rOWMXAyw5qSAv161YeIoMoWIOqR5KFtRarwlJGPUp959.NDsm', '超级管理员');

-- 演示租户
INSERT INTO sys_tenant (id, name, code, status, admin_count, description) VALUES
('tenant-001', '演示企业集团', 'demo', 'active', 1, '演示用租户');

-- 权限树（6个菜单 + 19个操作权限）
INSERT INTO sys_permission (id, name, code, type, parent_id, path, icon, sort) VALUES
-- 菜单
('perm-m-01', '企业档案', 'company', 'menu', NULL, '/company', 'Building', 1),
('perm-m-02', '数据管理', 'data', 'menu', NULL, '/data', 'DataBoard', 2),
('perm-m-03', '报告管理', 'report', 'menu', NULL, '/report', 'Document', 3),
('perm-m-04', '模板管理', 'template', 'menu', NULL, '/template', 'FileText', 4),
('perm-m-05', '系统管理', 'system', 'menu', NULL, '/system', 'Setting', 5),
('perm-m-06', '消息通知', 'notice', 'menu', NULL, '/notice', 'Bell', 6),
-- 企业档案操作
('perm-a-01', '查看企业', 'company:read', 'action', 'perm-m-01', NULL, NULL, 1),
('perm-a-02', '新建企业', 'company:create', 'action', 'perm-m-01', NULL, NULL, 2),
('perm-a-03', '编辑企业', 'company:update', 'action', 'perm-m-01', NULL, NULL, 3),
('perm-a-04', '删除企业', 'company:delete', 'action', 'perm-m-01', NULL, NULL, 4),
-- 数据管理操作
('perm-a-05', '查看数据文件', 'data:read', 'action', 'perm-m-02', NULL, NULL, 1),
('perm-a-06', '上传数据文件', 'data:upload', 'action', 'perm-m-02', NULL, NULL, 2),
('perm-a-07', '删除数据文件', 'data:delete', 'action', 'perm-m-02', NULL, NULL, 3),
-- 报告管理操作
('perm-a-08', '查看报告', 'report:read', 'action', 'perm-m-03', NULL, NULL, 1),
('perm-a-09', '生成报告', 'report:generate', 'action', 'perm-m-03', NULL, NULL, 2),
('perm-a-10', '归档报告', 'report:archive', 'action', 'perm-m-03', NULL, NULL, 3),
('perm-a-11', '删除报告', 'report:delete', 'action', 'perm-m-03', NULL, NULL, 4),
-- 系统管理操作
('perm-a-12', '用户管理', 'system:user', 'action', 'perm-m-05', NULL, NULL, 1),
('perm-a-13', '角色管理', 'system:role', 'action', 'perm-m-05', NULL, NULL, 2),
('perm-a-14', '权限管理', 'system:permission', 'action', 'perm-m-05', NULL, NULL, 3),
('perm-a-15', '日志查看', 'system:log', 'action', 'perm-m-05', NULL, NULL, 4),
('perm-a-16', '系统配置', 'system:config', 'action', 'perm-m-05', NULL, NULL, 5),
-- 模板管理操作
('perm-a-17', '查看模板', 'template:read', 'action', 'perm-m-04', NULL, NULL, 1),
('perm-a-18', '管理模板', 'template:manage', 'action', 'perm-m-04', NULL, NULL, 2),
-- 通知管理操作
('perm-a-19', '发布通知', 'notice:publish', 'action', 'perm-m-06', NULL, NULL, 1);

-- 演示角色
INSERT INTO sys_role (id, tenant_id, name, code, description) VALUES
('role-001', 'tenant-001', '管理员', 'admin', '租户管理员，拥有全部权限'),
('role-002', 'tenant-001', '普通用户', 'user', '普通操作员');

-- 管理员角色权限（全部）
INSERT INTO sys_role_permission (id, role_id, permission_code)
SELECT CONCAT('rp-admin-', id), 'role-001', code FROM sys_permission WHERE type = 'action';

-- 普通用户权限（只读+基础操作）
INSERT INTO sys_role_permission (id, role_id, permission_code) VALUES
('rp-user-01', 'role-002', 'company:read'),
('rp-user-02', 'role-002', 'data:read'),
('rp-user-03', 'role-002', 'data:upload'),
('rp-user-04', 'role-002', 'report:read'),
('rp-user-05', 'role-002', 'report:generate'),
('rp-user-06', 'role-002', 'template:read');

-- 演示用户（密码：test@123，BCrypt加密）
INSERT INTO sys_user (id, tenant_id, username, real_name, password, email, phone, role_id, status) VALUES
('user-001', 'tenant-001', 'admin', '系统管理员', '$2b$10$yawoS2N//6ek9IQ5bkK1euD7.E.RHAVy9N/WEieSwM0lC9PZQFVye', 'admin@demo.com', '13800001111', 'role-001', 'active'),
('user-002', 'tenant-001', 'operator', '操作员小王', '$2b$10$yawoS2N//6ek9IQ5bkK1euD7.E.RHAVy9N/WEieSwM0lC9PZQFVye', 'operator@demo.com', '13800002222', 'role-002', 'active');

-- 默认系统配置
INSERT INTO sys_config (id, tenant_id, site_name, logo_url, icp, max_file_size) VALUES
('cfg-001', 'tenant-001', '文件解析处理平台', '', 'ICP备xxxxxxxx号', 52428800);
