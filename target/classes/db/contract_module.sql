
-- 供应商主表 supplier_main
CREATE TABLE supplier_main (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  supplier_code VARCHAR(64) NOT NULL COMMENT '供应商编码（唯一）',
  supplier_name VARCHAR(200) NOT NULL COMMENT '供应商全称',
  supplier_short_name VARCHAR(100) COMMENT '供应商简称',
  credit_code VARCHAR(50) COMMENT '统一社会信用代码',
  legal_representative VARCHAR(100) COMMENT '法定代表人',
  contact_address VARCHAR(500) COMMENT '注册地址',
  contact_phone VARCHAR(50) COMMENT '联系电话',
  bank_name VARCHAR(200) COMMENT '开户银行',
  bank_account VARCHAR(100) COMMENT '银行账号',
  account_name VARCHAR(200) COMMENT '账户名称',
  created_by BIGINT NOT NULL COMMENT '创建人ID',
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_by BIGINT COMMENT '更新人ID',
  updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_code (supplier_code),
  KEY idx_name (supplier_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商主表';

-- 供应商联系人表 supplier_contact
CREATE TABLE supplier_contact (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  supplier_id BIGINT NOT NULL COMMENT '供应商ID',
  contact_name VARCHAR(100) NOT NULL COMMENT '联系人姓名',
  contact_position VARCHAR(100) COMMENT '职位',
  contact_phone VARCHAR(50) COMMENT '手机号',
  contact_email VARCHAR(100) COMMENT '邮箱',
  is_primary TINYINT DEFAULT 0 COMMENT '是否主要联系人：0否 1是',
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_supplier_id (supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商联系人表';

-- 合同主表 contract_main
CREATE TABLE contract_main (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  contract_no VARCHAR(64) NOT NULL COMMENT '合同编号（唯一，业务号）',
  contract_name VARCHAR(200) NOT NULL COMMENT '合同名称',
  contract_type TINYINT NOT NULL COMMENT '合同类型：1采购 2销售 3服务 4其他',
  contract_direction TINYINT NOT NULL COMMENT '合同方向：1采购（我方付款）2销售（我方收款）',

  customer_id BIGINT COMMENT '客户ID（销售合同时填写）',
  customer_name VARCHAR(200) COMMENT '客户名称（冗余）',
  supplier_id BIGINT COMMENT '供应商ID（采购合同时填写）',
  supplier_name VARCHAR(200) COMMENT '供应商名称（冗余）',

  contract_amount DECIMAL(18,2) NOT NULL COMMENT '合同总金额（人民币）',
  start_date DATE NOT NULL COMMENT '合同开始日期',
  end_date DATE NOT NULL COMMENT '合同结束日期',
  sign_date DATE COMMENT '签订日期',

  status TINYINT NOT NULL DEFAULT 10 COMMENT '状态：10草稿 30执行中 40已完成 50已终止 60已过期',

  payment_terms TINYINT COMMENT '付款条款：1一次性 2分期 3按里程碑 4月结',
  payment_schedule JSON COMMENT '付款计划JSON（分期详情）',

  contract_file_url VARCHAR(500) COMMENT '正式合同原件URL（OSS）',

  dept_id BIGINT COMMENT '负责部门ID',
  manager_id BIGINT COMMENT '合同负责人ID',

  created_by BIGINT NOT NULL COMMENT '创建人ID',
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_by BIGINT COMMENT '更新人ID',
  updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_contract_no (contract_no),
  KEY idx_status (status),
  KEY idx_supplier_id (supplier_id),
  KEY idx_customer_id (customer_id),
  KEY idx_end_date (end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合同主表';

-- 付款计划与记录表 contract_payment_plan
CREATE TABLE contract_payment_plan (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  contract_id BIGINT NOT NULL COMMENT '合同ID',
  plan_no VARCHAR(32) COMMENT '计划编号（如：第1期/预付款）',
  plan_type TINYINT NOT NULL COMMENT '款项类型：1收款 2付款',
  plan_amount DECIMAL(18,2) NOT NULL COMMENT '计划金额',
  plan_date DATE COMMENT '计划付款日期',
  condition_desc VARCHAR(500) COMMENT '付款条件说明',

  actual_date DATE COMMENT '实际付款日期',
  actual_amount DECIMAL(18,2) COMMENT '实际付款金额',
  voucher_no VARCHAR(100) COMMENT '财务凭证号',
  pay_method VARCHAR(20) COMMENT '支付方式：银行转账/支票/现金',
  remark VARCHAR(500) COMMENT '备注',
  recorded_by BIGINT COMMENT '登记人ID',
  recorded_time DATETIME COMMENT '登记时间',

  status TINYINT DEFAULT 10 COMMENT '状态：10待付款 20已付款 30已逾期',
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_contract_id (contract_id),
  KEY idx_plan_date (plan_date),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='付款计划与记录表';
