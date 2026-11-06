-- 常用 SQL 补充分类和数据库类型。可重复执行。
SET @exist := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'sql_snippet'
    AND COLUMN_NAME = 'category'
);
SET @sql := IF(@exist = 0,
  'ALTER TABLE sql_snippet ADD COLUMN category varchar(50) DEFAULT NULL COMMENT ''分类，本人自填'' AFTER title',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'sql_snippet'
    AND COLUMN_NAME = 'db_type'
);
SET @sql := IF(@exist = 0,
  'ALTER TABLE sql_snippet ADD COLUMN db_type varchar(20) DEFAULT NULL COMMENT ''数据库类型：mysql/oracle/postgresql/sqlserver/dm/kingbase'' AFTER category',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
