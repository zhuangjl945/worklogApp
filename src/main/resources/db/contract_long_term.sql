-- 合同有效期支持「长期」：已有库升级。可重复执行。
SET @exist := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'contract_main'
    AND COLUMN_NAME = 'is_long_term'
);
SET @sql := IF(@exist = 0,
  'ALTER TABLE contract_main ADD COLUMN is_long_term TINYINT NOT NULL DEFAULT 0 COMMENT ''是否长期有效：1是 0否'' AFTER end_date',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE contract_main
  MODIFY COLUMN end_date DATE NULL COMMENT '合同结束日期；长期合同时为空';
