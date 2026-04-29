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