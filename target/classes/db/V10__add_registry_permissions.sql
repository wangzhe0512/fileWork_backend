-- V10：新增占位符库权限（registry:list / registry:edit）
--      并给所有现有"管理员"角色补充授权，同时给 init.sql 中的 role-001 补充授权。
-- 说明：
--   V9 建表时遗漏了向 sys_permission 注册 registry 相关权限，
--   导致通过 TenantService 创建的租户管理员角色没有这两个权限。
--   本脚本统一修复：
--   1. 清理之前手动插入的无序 UUID id 记录（若存在），改用固定可读 id
--   2. 将权限插入 sys_permission（使用固定 id 保证幂等）
--   3. 给所有 code='admin' 的管理员角色补充授权（INSERT IGNORE 保证幂等）

-- ============================================================
-- 1. 清理手动插入的随机 UUID id 的 registry 权限（若存在）
-- ============================================================
DELETE FROM sys_permission
WHERE code IN ('registry:list', 'registry:edit')
  AND id NOT IN ('perm-a-registry-list', 'perm-a-registry-edit');

-- ============================================================
-- 2. 插入占位符库菜单权限（幂等，已存在则跳过）
-- ============================================================
INSERT IGNORE INTO sys_permission (id, name, code, type, parent_id, path, icon, sort) VALUES
('perm-m-07', '占位符库', 'registry', 'menu', NULL, '/registry', 'DataBase', 7);

-- ============================================================
-- 3. 插入占位符库操作权限（幂等，已存在则跳过）
-- ============================================================
INSERT IGNORE INTO sys_permission (id, name, code, type, parent_id, path, icon, sort) VALUES
('perm-a-registry-list', '查看占位符库', 'registry:list', 'action', 'perm-m-07', NULL, NULL, 1),
('perm-a-registry-edit', '管理占位符库', 'registry:edit', 'action', 'perm-m-07', NULL, NULL, 2);

-- ============================================================
-- 4. 给所有现有"管理员"角色（code='admin'）补充授权（幂等）
-- ============================================================
INSERT IGNORE INTO sys_role_permission (id, role_id, permission_code)
SELECT CONCAT('rp-', r.id, '-reg-list'), r.id, 'registry:list'
FROM sys_role r
WHERE r.code = 'admin'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission
      WHERE role_id = r.id AND permission_code = 'registry:list'
  );

INSERT IGNORE INTO sys_role_permission (id, role_id, permission_code)
SELECT CONCAT('rp-', r.id, '-reg-edit'), r.id, 'registry:edit'
FROM sys_role r
WHERE r.code = 'admin'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission
      WHERE role_id = r.id AND permission_code = 'registry:edit'
  );

-- ============================================================
-- 5. 同时清理 sys_role_permission 中已存在的针对 role-001 的
--    随机 UUID id 授权记录，统一用可读 id（幂等）
-- ============================================================
DELETE FROM sys_role_permission
WHERE role_id = 'role-001'
  AND permission_code IN ('registry:list', 'registry:edit')
  AND id NOT IN ('rp-role-001-reg-list', 'rp-role-001-reg-edit');

INSERT IGNORE INTO sys_role_permission (id, role_id, permission_code) VALUES
('rp-role-001-reg-list', 'role-001', 'registry:list'),
('rp-role-001-reg-edit', 'role-001', 'registry:edit');
