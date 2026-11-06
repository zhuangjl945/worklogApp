-- 个人常用 SQL。每人只能看到自己创建的记录，接口按 created_by 收口。
CREATE TABLE IF NOT EXISTS sql_snippet (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  title varchar(200) NOT NULL COMMENT '标题',
  category varchar(50) DEFAULT NULL COMMENT '分类，本人自填',
  db_type varchar(20) DEFAULT NULL COMMENT '数据库类型：mysql/oracle/postgresql/sqlserver/dm/kingbase',
  sql_text longtext NOT NULL COMMENT 'SQL 正文',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  created_by bigint NOT NULL COMMENT '创建人 ID',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='常用 SQL';
