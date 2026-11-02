-- 表结构三级目录（业务系统 / 数据库用户 / 表 / 历史版本）见同目录 db_doc_module_v2.sql，本文件只建批次表。
-- 数据库文档模块建表脚本
CREATE TABLE IF NOT EXISTS db_doc (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  title varchar(200) NOT NULL COMMENT '文档标题',
  source_sql longtext NOT NULL COMMENT '原始 SQL 文本',
  parsed_json longtext NOT NULL COMMENT '解析后的 JSON 结构',
  table_count int NOT NULL DEFAULT 0 COMMENT '包含表数量',
  created_by bigint NOT NULL COMMENT '创建人 ID',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据库文档记录';
