-- 创建数据库（执行前请确认权限）
CREATE DATABASE IF NOT EXISTS work_log_system 
  DEFAULT CHARACTER SET utf8mb4 
  COLLATE utf8mb4_unicode_ci;

USE work_log_system;

-- 用户表 user
CREATE TABLE user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  username VARCHAR(50) NOT NULL UNIQUE COMMENT '登录账号（工号/邮箱）',
  password VARCHAR(100) NOT NULL COMMENT '密码',
  real_name VARCHAR(30) NOT NULL COMMENT '真实姓名',
  dept_id BIGINT COMMENT '所属科室ID（逻辑关联，非外键）',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-启用，0-禁用',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  
  INDEX idx_username (username),
  INDEX idx_dept_id (dept_id)  -- 保留索引，加速按科室查用户
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表（无外键）';

 
-- 科室表 dept（科室管理）

CREATE TABLE dept (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  dept_code VARCHAR(20) UNIQUE NOT NULL COMMENT '科室编码（如：IT_DEPT_001）',
  dept_name VARCHAR(100) NOT NULL COMMENT '科室全称',
  parent_id BIGINT DEFAULT NULL COMMENT '上级科室ID（逻辑树形，非外键）',
  sort_order INT DEFAULT 0 COMMENT '排序序号',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-启用，0-禁用',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  
  INDEX idx_parent_id (parent_id)  -- 支持递归查询优化
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='科室管理表（无外键，支持多级）';



-- 工作分类表 work_category（工作分类）
CREATE TABLE work_category (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  category_code VARCHAR(20) UNIQUE NOT NULL COMMENT '分类编码（如：DEV_TASK, MEETING, REPORT）',
  category_name VARCHAR(50) NOT NULL COMMENT '分类名称（如：开发任务、会议、报告撰写）',
  description TEXT COMMENT '分类说明（可选）',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-启用，0-禁用',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作内容分类表';

-- 工作状态字典表 work_status（工作完成状态）
CREATE TABLE work_status (
  id TINYINT PRIMARY KEY COMMENT '状态码（主键，建议 1~100）',
  status_name VARCHAR(30) NOT NULL COMMENT '状态名称（如：待处理、进行中、已完成、已归档）',
  sort_order TINYINT DEFAULT 0 COMMENT '排序（用于下拉框展示顺序）',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '字典项启用状态：1-启用，0-停用'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作状态字典表';



-- 工作记录主表 work_record（核心业务表）
CREATE TABLE work_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  user_id BIGINT NOT NULL COMMENT '记录人ID（逻辑关联）',
  dept_id BIGINT NOT NULL COMMENT '所属科室ID（逻辑冗余，提升查询效率）',
  category_id BIGINT NOT NULL COMMENT '工作分类ID（逻辑关联）',
  status_id TINYINT NOT NULL DEFAULT 1 COMMENT '当前状态ID（逻辑关联 → work_status.id）',
  title VARCHAR(200) NOT NULL COMMENT '工作标题',
  content TEXT COMMENT '工作详情',
  start_time DATETIME COMMENT '开始时间',
  end_time DATETIME COMMENT '结束时间',
  duration_minutes INT COMMENT '耗时（分钟）',
  is_important TINYINT DEFAULT 0 COMMENT '是否重要（1=是）',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删：1-删除，0-正常',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  
  INDEX idx_user_id (user_id),
  INDEX idx_dept_id (dept_id),
  INDEX idx_category_id (category_id),
  INDEX idx_status_id (status_id),
  INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日常工作记录表（无外键，纯逻辑关联）';
-- 工作内容详情
CREATE TABLE work_record_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  work_record_id BIGINT NOT NULL COMMENT '关联的工作记录ID',
  user_id BIGINT NOT NULL COMMENT '操作人ID',
  log_content TEXT NOT NULL COMMENT '处理详情/日志内容',
  log_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  INDEX idx_work_record_id (work_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作记录处理日志表';
-- 工作费用表
CREATE TABLE work_record_expense (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  work_record_id BIGINT NOT NULL COMMENT '关联的工作记录ID',
  expense_type VARCHAR(20) NOT NULL COMMENT '费用类型: 配件、耗材、服务费、交通费、其他',
  amount DECIMAL(10, 2) NOT NULL COMMENT '费用金额',
  description VARCHAR(255) COMMENT '费用说明',
  expense_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '费用发生时间',
  INDEX idx_work_record_id (work_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作记录费用表';


-- 1. 增加 dept_id 字段
ALTER TABLE work_category ADD COLUMN dept_id BIGINT NOT NULL COMMENT '所属科室ID' AFTER id;



-- 2. 调整唯一约束（由原来的 category_code 唯一，改为 dept_id + category_code 唯一）
-- 如果之前有 category_code 的唯一索引，需要先删除
-- ALTER TABLE work_category DROP INDEX uk_category_code; 
CREATE UNIQUE INDEX uk_dept_category_code ON work_category(dept_id, category_code);

-- 3. 增加普通索引加速查询
CREATE INDEX idx_dept_id ON work_category(dept_id);

-- 任务转移记录表
CREATE TABLE work_record_transfer (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  work_record_id BIGINT NOT NULL COMMENT '关联的工作记录ID',
  from_user_id BIGINT NOT NULL COMMENT '发起转移人ID',
  to_user_id BIGINT NOT NULL COMMENT '目标接收人ID',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0-待处理，1-已接受，2-已拒绝，3-已取消',
  reason VARCHAR(255) COMMENT '转移原因',
  reply VARCHAR(255) COMMENT '反馈/拒绝理由',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发起时间',
  handle_time DATETIME COMMENT '处理时间',
  
  INDEX idx_record_id (work_record_id),
  INDEX idx_to_user_id (to_user_id, status),
  INDEX idx_from_user_id (from_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务转移申请表';

--增加工作记录业务科室
ALTER TABLE work_record
ADD COLUMN biz_dept_id BIGINT NULL COMMENT '业务科室ID（记录选择）';
--图片字段
ALTER TABLE work_record
ADD COLUMN image_urls TEXT NULL COMMENT '图片URL列表(建议存JSON数组字符串)'
AFTER content;


-- 2.2.1 供应商主表 supplier_main
CREATE TABLE `supplier_main` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `supplier_code` VARCHAR(64) NOT NULL COMMENT '供应商编码（唯一）',
  `supplier_name` VARCHAR(200) NOT NULL COMMENT '供应商全称',
  `supplier_short_name` VARCHAR(100) COMMENT '供应商简称',
  `credit_code` VARCHAR(50) COMMENT '统一社会信用代码',
  `legal_representative` VARCHAR(100) COMMENT '法定代表人',
  `contact_address` VARCHAR(500) COMMENT '注册地址',
  `contact_phone` VARCHAR(50) COMMENT '联系电话',
  `bank_name` VARCHAR(200) COMMENT '开户银行',
  `bank_account` VARCHAR(100) COMMENT '银行账号',
  `account_name` VARCHAR(200) COMMENT '账户名称',
  `created_by` BIGINT NOT NULL COMMENT '创建人ID',
  `created_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` BIGINT COMMENT '更新人ID',
  `updated_time` DATETIME ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_supplier_code` (`supplier_code`),
  KEY `idx_name` (`supplier_name`)
) ENGINE=InnoDB COMMENT='供应商主表';
-- 2.2.2 供应商联系人表 supplier_contact
CREATE TABLE `supplier_contact` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `supplier_id` BIGINT NOT NULL COMMENT '供应商ID',
  `contact_name` VARCHAR(100) NOT NULL COMMENT '联系人姓名',
  `contact_position` VARCHAR(100) COMMENT '职位',
  `contact_phone` VARCHAR(50) COMMENT '手机号',
  `contact_email` VARCHAR(100) COMMENT '邮箱',
  `is_primary` TINYINT DEFAULT 0 COMMENT '是否主要联系人：0否 1是',
  `created_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_supplier_id` (`supplier_id`)
) ENGINE=InnoDB COMMENT='供应商联系人表';

-- 2.2.3 合同主表 contract_main
CREATE TABLE `contract_main` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `contract_no` VARCHAR(64) NOT NULL COMMENT '合同编号（唯一，业务号）',
  `contract_name` VARCHAR(200) NOT NULL COMMENT '合同名称',
  `contract_type` TINYINT NOT NULL COMMENT '合同类型：1采购 2销售 3服务 4其他',
  `contract_direction` TINYINT NOT NULL COMMENT '合同方向：1采购（我方付款）2销售（我方收款）',
  -- 参与方信息
  `customer_id` BIGINT COMMENT '客户ID（销售合同时填写）',
  `customer_name` VARCHAR(200) COMMENT '客户名称（冗余）',
  `supplier_id` BIGINT COMMENT '供应商ID（采购合同时填写）',
  `supplier_name` VARCHAR(200) COMMENT '供应商名称（冗余）',
  -- 金额与期限（默认人民币，去掉currency字段）
  `contract_amount` DECIMAL(18,2) NOT NULL COMMENT '合同总金额（人民币）',
  `start_date` DATE NOT NULL COMMENT '合同开始日期',
  `end_date` DATE NOT NULL COMMENT '合同结束日期',
  `sign_date` DATE COMMENT '签订日期',
  -- 状态（简化：去掉审批中状态）
  `status` TINYINT NOT NULL DEFAULT 10 COMMENT '状态：10草稿 30执行中 40已完成 50已终止 60已过期',
  -- 付款条款
  `payment_terms` TINYINT COMMENT '付款条款：1一次性 2分期 3按里程碑 4月结',
  `payment_schedule` JSON COMMENT '付款计划JSON（分期详情）',
  -- 业务归属
  `dept_id` BIGINT COMMENT '负责部门ID',
  `manager_id` BIGINT COMMENT '合同负责人ID',
  -- 系统字段
  `created_by` BIGINT NOT NULL COMMENT '创建人ID',
  `created_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` BIGINT COMMENT '更新人ID',
  `updated_time` DATETIME ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_contract_no` (`contract_no`),
  KEY `idx_status` (`status`),
  KEY `idx_supplier_id` (`supplier_id`),
  KEY `idx_customer_id` (`customer_id`),
  KEY `idx_end_date` (`end_date`)
) ENGINE=InnoDB COMMENT='合同主表';
-- 2.2.4 付款计划与记录表 contract_payment_plan
CREATE TABLE `contract_payment_plan` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `contract_id` BIGINT NOT NULL COMMENT '合同ID',
  `plan_no` VARCHAR(32) COMMENT '计划编号（如：第1期/预付款）',
  `plan_type` TINYINT NOT NULL COMMENT '款项类型：1收款 2付款',
  `plan_amount` DECIMAL(18,2) NOT NULL COMMENT '计划金额',
  `plan_date` DATE COMMENT '计划付款日期',
  `condition_desc` VARCHAR(500) COMMENT '付款条件说明',
  -- 实际付款记录（直接登记，无申请流程）
  `actual_date` DATE COMMENT '实际付款日期',
  `actual_amount` DECIMAL(18,2) COMMENT '实际付款金额',
  `voucher_no` VARCHAR(100) COMMENT '财务凭证号',
  `pay_method` VARCHAR(20) COMMENT '支付方式：银行转账/支票/现金',
  `remark` VARCHAR(500) COMMENT '备注',
  `recorded_by` BIGINT COMMENT '登记人ID',
  `recorded_time` DATETIME COMMENT '登记时间',
  -- 状态（简化）
  `status` TINYINT DEFAULT 10 COMMENT '状态：10待付款 20已付款 30已逾期',
  `created_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_contract_id` (`contract_id`),
  KEY `idx_plan_date` (`plan_date`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB COMMENT='付款计划与记录表';



-- 增加字段保存合同原件
ALTER TABLE contract_main
ADD COLUMN contract_file_url VARCHAR(500) COMMENT '正式合同原件URL（OSS）'
-- 合同续签功能
 ALTER TABLE contract_main 
 ADD COLUMN renew_from_id BIGINT UNSIGNED COMMENT '续签来源合同ID' AFTER manager_id,
 ADD COLUMN renew_count INT DEFAULT 0 COMMENT '续签次数' AFTER renew_from_id;

-- 插入常用初始状态（供参考，可按需增删）
INSERT INTO work_status (id, status_name, sort_order, status) VALUES
(1, '待处理',0,1),
(2, '进行中',0,1),
(3, '已完成',0,1),
(4, '已归档',0,1),
(5, '已取消',0,1);
-- 插入 8 条用户测试数据
INSERT INTO user (
  username, password, real_name, dept_id, status, create_time
) VALUES
-- ✅ 启用用户：IT 科核心成员
('admin', '123', 'admin', 1, 1, '2024-05-28 08:30:00'),
('EMP_002', '123', '李四', 1, 1, '2024-05-28 09:15:00'),
('EMP_003', '123', '王五', 1, 1, '2024-05-29 10:00:00'),