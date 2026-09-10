-- ============================================================
-- 角色体系：user 表增加 role 列
--
-- 三级内置角色（不建角色表，避免为一个内部系统引入 RBAC 的复杂度）：
--   USER        普通员工    —— 只管自己的记录，本科室数据只读
--   DEPT_ADMIN  科室管理员  —— 本科室记录可编辑，可维护本科室工作分类/登记渠道
--   ADMIN       系统管理员  —— 跨科室数据 + 员工/科室/系统参数
--
-- 应用启动时 UserRoleMigrationRunner 会自动做同样的事，本文件用于人工审阅与生产变更单。
-- ============================================================

-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，这里用 information_schema 判断后动态执行，保证可重复运行
SET @ddl := (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'role'),
    'SELECT ''role 列已存在，跳过'' AS msg',
    'ALTER TABLE `user` ADD COLUMN `role` VARCHAR(20) NOT NULL DEFAULT ''USER'' COMMENT ''角色：USER-普通员工，DEPT_ADMIN-科室管理员，ADMIN-系统管理员'' AFTER `dept_id`'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 兜底：历史数据 role 为空一律按普通员工，避免出现「无角色」的越权口子
UPDATE `user` SET `role` = 'USER' WHERE `role` IS NULL OR `role` = '';

-- 引导：内置 admin 账号必须升为系统管理员，否则升级后没人能进系统管理改角色
UPDATE `user` SET `role` = 'ADMIN' WHERE `username` = 'admin';

-- 授予科室管理员（示例，按需调整）：
-- UPDATE `user` SET `role` = 'DEPT_ADMIN' WHERE `username` = 'zjl';