-- ============================================================
-- 数据库文档模块 v2：表结构资产目录
--
-- 为什么要有这一版：v1 只把「一份 SQL 脚本」整存整取（db_doc 一行 = 一次分析），
-- 既分不清这张表属于哪个业务系统，也分不清属于哪个数据库用户，
-- 后续再分析只能又存一份快照，看不出「新增了哪些表、哪些表改了结构」。
--
-- v2 把资产拆成三级，全部逻辑关联、不建外键（与 ticket/contract 模块风格一致）：
--   db_system        业务系统（HIS、LIS、体检系统……）
--   db_database      系统下的数据库用户/模式（Oracle schema、MySQL 账号 + 库名）
--   db_table_doc     一张表当前的结构（唯一键：数据库用户 + 表名）
--   db_table_version 每次分析产生的表结构历史快照（只在新建/变更时写入）
--   db_doc           退化为「分析批次」，记录本批新增/变更/未变/疑似下线张数
--
-- 本脚本可重复执行：建表用 IF NOT EXISTS，给老表加列先查 information_schema。
-- ============================================================

USE work_log_system;

-- ---------- 1. 业务系统 ----------
CREATE TABLE IF NOT EXISTS db_system (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  system_name VARCHAR(100) NOT NULL COMMENT '系统名称，如「门诊住院信息系统」',
  system_code VARCHAR(50) COMMENT '系统编码，如 HIS，便于与运维台账对得上（可空，留空由名称首字母兜底）',
  vendor VARCHAR(100) COMMENT '厂商，如 卫宁/东软/自研',
  db_type VARCHAR(30) NOT NULL DEFAULT 'mysql' COMMENT '主流数据库类型：mysql/oracle/postgresql/sqlserver/dm/kingbase',
  version VARCHAR(50) COMMENT '系统版本，如 V5.2',
  owner_name VARCHAR(50) COMMENT '系统负责人',
  remark VARCHAR(500) COMMENT '备注',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-在用，0-停用（停用不删历史表结构）',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序号，越小越靠前',
  created_by BIGINT NOT NULL COMMENT '创建人ID',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE INDEX uk_system_code (system_code),
  INDEX idx_system_status (status, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务系统（数据库表文档的第一级归属）';

-- ---------- 2. 系统下的数据库用户/模式 ----------
CREATE TABLE IF NOT EXISTS db_database (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  system_id BIGINT NOT NULL COMMENT '所属系统（逻辑关联 db_system.id）',
  db_user VARCHAR(100) NOT NULL COMMENT '数据库用户/模式名，如 Oracle 的 HIS_USER、MySQL 的账号名 —— 表结构挂在谁名下',
  db_name VARCHAR(100) COMMENT '库名/SID/服务名，如 work_log_system、orcl',
  db_type VARCHAR(30) NOT NULL DEFAULT 'mysql' COMMENT '数据库类型：mysql/oracle/postgresql/sqlserver/dm/kingbase',
  host VARCHAR(200) COMMENT '数据库地址',
  port INT COMMENT '端口',
  env VARCHAR(20) NOT NULL DEFAULT 'prod' COMMENT '环境：prod-生产，test-测试，uat-预上线，dev-开发',
  jdbc_url VARCHAR(500) COMMENT '连接串（只存串，不存口令）',
  credential_id BIGINT COMMENT '口令来源（逻辑关联 credential.id），本表不落任何明文口令',
  table_count INT NOT NULL DEFAULT 0 COMMENT '该用户下已登记的表数量（每次分析后回填，列表用）',
  remark VARCHAR(500) COMMENT '备注',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-启用，0-停用',
  created_by BIGINT NOT NULL COMMENT '创建人ID',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE INDEX uk_db_user_env (system_id, db_user, env),
  INDEX idx_db_system (system_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务系统下的数据库用户/模式（表结构的第二级归属）';

-- ---------- 3. 表结构当前快照 ----------
CREATE TABLE IF NOT EXISTS db_table_doc (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  system_id BIGINT NOT NULL COMMENT '所属系统（逻辑关联 db_system.id），冗余自 db_database，便于按系统直接查表',
  database_id BIGINT NOT NULL COMMENT '所属数据库用户（逻辑关联 db_database.id）',
  table_name VARCHAR(128) NOT NULL COMMENT '表名（不含模式前缀）',
  table_comment VARCHAR(500) COMMENT '表注释/业务含义',
  db_user VARCHAR(100) COMMENT '数据库用户/模式名（冗余自 db_database，列表直接展示，不用再连表）',
  db_type VARCHAR(30) COMMENT '数据库类型（冗余）',
  column_count INT NOT NULL DEFAULT 0 COMMENT '字段数量',
  index_count INT NOT NULL DEFAULT 0 COMMENT '索引数量',
  pk_columns VARCHAR(500) COMMENT '主键字段，逗号分隔，列表展示用',
  columns_json LONGTEXT COMMENT '字段明细 JSON（ColumnInfo 列表）',
  indexes_json LONGTEXT COMMENT '索引明细 JSON（IndexInfo 列表）',
  source_ddl LONGTEXT COMMENT '该表最近一次的建表语句原文',
  first_batch_id BIGINT COMMENT '首次登记的批次（db_doc.id）',
  last_batch_id BIGINT COMMENT '最近一次改变结构的批次（db_doc.id）',
  parse_count INT NOT NULL DEFAULT 0 COMMENT '该表被分析过的次数',
  remark VARCHAR(500) COMMENT '人工补充说明（业务口径、注意事项）',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-在用，0-疑似下线（REPLACE 式全量比对后未出现的表）',
  created_by BIGINT NOT NULL COMMENT '创建人ID',
  updated_by BIGINT COMMENT '最近更新人ID',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（首次登记时间）',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（最近一次结构变更时间）',
  UNIQUE INDEX uk_db_table (database_id, table_name),
  INDEX idx_table_system (system_id, status),
  INDEX idx_table_name (table_name),
  INDEX idx_table_comment (table_comment(64))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据库表结构目录（一张表一行，重复分析按增量合并）';

-- ---------- 4. 表结构历史版本 ----------
CREATE TABLE IF NOT EXISTS db_table_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  table_doc_id BIGINT NOT NULL COMMENT '对应的表（逻辑关联 db_table_doc.id）',
  batch_id BIGINT COMMENT '由哪次分析产生（逻辑关联 db_doc.id）',
  system_id BIGINT COMMENT '所属系统（冗余）',
  database_id BIGINT COMMENT '所属数据库用户（冗余）',
  table_name VARCHAR(128) NOT NULL COMMENT '表名（冗余，表删除后仍可追溯）',
  change_type VARCHAR(20) NOT NULL COMMENT '本批对该表的处理：NEW-新增，CHANGED-结构变更，UNCHANGED-仅确认存在，MISSING-本次未出现，MANUAL-人工修订',
  table_comment VARCHAR(500) COMMENT '本版本的表注释',
  column_count INT NOT NULL DEFAULT 0 COMMENT '本版本字段数量',
  columns_json LONGTEXT COMMENT '本版本字段明细 JSON',
  indexes_json LONGTEXT COMMENT '本版本索引明细 JSON',
  source_ddl LONGTEXT COMMENT '本版本建表语句原文',
  diff_summary VARCHAR(1000) COMMENT '与上一版的差异摘要，如「新增字段:age; 删除索引:idx_x」',
  created_by BIGINT NOT NULL COMMENT '操作人ID',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '版本生成时间',
  INDEX idx_version_table (table_doc_id, id),
  INDEX idx_version_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据库表结构历史版本（每次新增/变更留一份快照）';

-- ---------- 5. db_doc 退化为一支「分析批次」，补归属与差异统计 ----------
SET @ddl := (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'db_doc' AND COLUMN_NAME = 'system_id'),
    'SELECT ''db_doc 已是 v2 结构，跳过'' AS msg',
    'ALTER TABLE `db_doc`
       ADD COLUMN `system_id` BIGINT NULL COMMENT ''所属系统 db_system.id，NULL 表示 v1 遗留的未归类记录'' AFTER `id`,
       ADD COLUMN `database_id` BIGINT NULL COMMENT ''所属数据库用户 db_database.id'' AFTER `system_id`,
       ADD COLUMN `db_user` VARCHAR(100) NULL COMMENT ''数据库用户/模式名（冗余展示）'' AFTER `database_id`,
       ADD COLUMN `added_count` INT NOT NULL DEFAULT 0 COMMENT ''本批新增表张数'' AFTER `table_count`,
       ADD COLUMN `updated_count` INT NOT NULL DEFAULT 0 COMMENT ''本批结构变更表张数'' AFTER `added_count`,
       ADD COLUMN `unchanged_count` INT NOT NULL DEFAULT 0 COMMENT ''本批未变化表张数'' AFTER `updated_count`,
       ADD COLUMN `missing_count` INT NOT NULL DEFAULT 0 COMMENT ''本批未出现但目录里仍挂着的表张数'' AFTER `unchanged_count`'
  )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 批次按系统/库筛选是主查询路径，索引单独加（ADD COLUMN 那条语句里不便混建索引）
SET @idx := (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'db_doc' AND INDEX_NAME = 'idx_doc_system'),
    'SELECT ''db_doc.idx_doc_system 已存在，跳过'' AS msg',
    'ALTER TABLE `db_doc` ADD INDEX `idx_doc_system` (`system_id`, `database_id`, `create_time`)'
  )
);
PREPARE stmt FROM @idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- Excel 数据字典、直连采集这些来源本来就没有脚本，source_sql 不能再卡 NOT NULL
SET @ddl_srcsql := (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'db_doc' AND COLUMN_NAME = 'source_sql' AND IS_NULLABLE = 'NO'),
    'ALTER TABLE `db_doc` MODIFY COLUMN `source_sql` LONGTEXT NULL COMMENT ''原始 SQL 脚本；来源是 Excel 数据字典等非脚本时为空''',
    'SELECT ''db_doc.source_sql 已允许为空，跳过'' AS msg'
  )
);
PREPARE stmt FROM @ddl_srcsql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
-- v1 遗留的 db_doc 记录没有归属，界面按「未归类」展示，可在 SQL 分析页重新导入归到系统下。

-- ---------- 6. db_doc 记录这批结构的来源（v2.1：Excel 数据字典、直连采集也走同一套合并） ----------
SET @ddl_src := (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'db_doc' AND COLUMN_NAME = 'source_type'),
    'SELECT ''db_doc 已有来源列，跳过'' AS msg',
    'ALTER TABLE `db_doc`
       ADD COLUMN `source_type` VARCHAR(20) NOT NULL DEFAULT ''DDL'' COMMENT ''来源：DDL 建表脚本 / EXCEL 数据字典 / SCAN 直连采集'' AFTER `title`,
       ADD COLUMN `source_ref` VARCHAR(300) NULL COMMENT ''来源说明，如 Excel 文件名与工作表'' AFTER `source_sql`'
  )
);
PREPARE stmt FROM @ddl_src;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 7. v2.2：目录里的表结构允许在页面上人工修订 ----------
-- 新增 change_type = MANUAL（人工修订），这类版本的 batch_id 为空，表示「不是某次分析带进来的，是人在页面上改的」。
-- 不加新列、不改列宽，只把注释口径同步过来，老库重复执行也安全。
SET @ddl_change_type := (
  SELECT IF(
    (SELECT COLUMN_COMMENT FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'db_table_version' AND COLUMN_NAME = 'change_type') LIKE '%MANUAL%',
    'SELECT ''db_table_version.change_type 注释已含 MANUAL，跳过'' AS msg',
    'ALTER TABLE db_table_version MODIFY COLUMN change_type VARCHAR(20) NOT NULL COMMENT ''处理类型：NEW-新增，CHANGED-结构变更，UNCHANGED-仅确认存在，MISSING-本次未出现，MANUAL-人工修订'''
  )
);
PREPARE stmt FROM @ddl_change_type;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;