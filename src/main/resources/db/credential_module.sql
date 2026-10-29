-- 凭据保管库建表脚本
CREATE TABLE IF NOT EXISTS credential (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  name varchar(200) NOT NULL COMMENT '凭据名称',
  category varchar(50) NOT NULL DEFAULT 'other' COMMENT '分类：database/server/app/network/other',
  username varchar(200) DEFAULT NULL COMMENT '账号（明文）',
  password_encrypted text DEFAULT NULL COMMENT '密码（AES-256-GCM 密文）',
  secret_key_encrypted text DEFAULT NULL COMMENT '密钥（AES-256-GCM 密文）',
  url varchar(500) DEFAULT NULL COMMENT '访问地址',
  tags varchar(500) DEFAULT NULL COMMENT '标签，逗号分隔',
  remark text DEFAULT NULL COMMENT '备注',
  expire_date date DEFAULT NULL COMMENT '过期日期',
  created_by bigint NOT NULL COMMENT '创建人 ID',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_category (category),
  KEY idx_expire_date (expire_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='凭据保管库';
