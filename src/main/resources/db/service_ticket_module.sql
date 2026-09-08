-- ============================================================
-- 手机端问题登记（服务工单）模块
-- 风格与 work_record_module.sql 保持一致：不使用外键，全部逻辑关联
-- ============================================================

USE work_log_system;

-- 登记渠道：一个二维码 = 一个渠道，绑定一个受理科室
CREATE TABLE ticket_channel (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  channel_code VARCHAR(32) NOT NULL COMMENT '渠道编码，二维码 URL 里携带',
  channel_name VARCHAR(100) NOT NULL COMMENT '渠道名称，如「三楼机房报修」',
  dept_id BIGINT NOT NULL COMMENT '受理科室ID（逻辑关联 dept.id）',
  default_category_id BIGINT COMMENT '默认问题类型（work_category.id）',
  need_phone TINYINT NOT NULL DEFAULT 1 COMMENT '是否强制填写手机号：1-是，0-否',
  daily_limit INT NOT NULL DEFAULT 200 COMMENT '该渠道每日提交上限（防刷兜底）',
  -- 说明：登记链接不依赖打印进二维码的密钥，改为服务端签发短期 form token（见 TicketFormTokenService）\n
  remark VARCHAR(255) COMMENT '备注/张贴位置说明',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-启用，0-停用（停用后旧二维码立即失效，历史工单保留）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE INDEX uk_channel_code (channel_code),
  INDEX idx_dept_status (dept_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问题登记渠道（二维码）表';

-- 问题工单主表
-- 状态机取值（由 Java 枚举 TicketStatus 固定，不做成可编辑字典：
-- 状态与流转规则强绑定，允许用户在界面上增删状态会让状态机失去约束）：
--   0 待受理 / 10 处理中 / 20 待报修人确认 / 30 已完成 / 40 已关闭 / 50 已退回
CREATE TABLE service_ticket (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  ticket_no VARCHAR(24) NOT NULL COMMENT '对外单号，形如 ST20260908000123，报修人凭此查询',
  channel_id BIGINT NOT NULL COMMENT '来源渠道（逻辑关联 ticket_channel.id）',
  dept_id BIGINT NOT NULL COMMENT '受理科室ID（冗余自渠道，查询与权限边界用）',
  category_id BIGINT COMMENT '问题类型（work_category.id，必须属于受理科室）',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '当前状态，见枚举说明',
  urgency TINYINT NOT NULL DEFAULT 2 COMMENT '紧急度：1-紧急(4h) 2-普通(24h) 3-一般(72h)',
  title VARCHAR(200) NOT NULL COMMENT '问题标题',
  content TEXT NOT NULL COMMENT '问题详细描述',
  location VARCHAR(200) COMMENT '发生地点/房间/设备位置',
  contact_name VARCHAR(50) COMMENT '报修人姓名/工号',
  contact_phone VARCHAR(20) COMMENT '报修人手机，仅用于联系与限流',
  image_urls TEXT COMMENT '图片 objectKey 的 JSON 数组字符串（不存公网直链）',
  access_token_hash CHAR(64) COMMENT '报修人查询令牌的 SHA-256；明文只在提交成功响应里出现一次',
  assignee_id BIGINT COMMENT '受理人 user.id，为空表示尚未受理',
  assignee_name VARCHAR(50) COMMENT '受理人姓名（冗余，列表展示避免联表）',
  work_record_id BIGINT COMMENT '受理时生成的工作记录ID（双向定位）',
  submit_ip VARCHAR(64) COMMENT '提交来源IP（限流与追溯）',
  submit_ua VARCHAR(255) COMMENT '提交 User-Agent 摘要',
  due_time DATETIME COMMENT 'SLA 期望完成时间，提交时按紧急度推算',
  accept_time DATETIME COMMENT '受理时间',
  done_time DATETIME COMMENT '处理完成时间',
  close_time DATETIME COMMENT '归档关闭时间',
  rating TINYINT COMMENT '报修人满意度 1~5',
  rating_comment VARCHAR(255) COMMENT '评价内容',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删：1-已删除，0-正常',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  UNIQUE INDEX uk_ticket_no (ticket_no),
  INDEX idx_dept_status_time (dept_id, status, create_time),
  INDEX idx_assignee_status (assignee_id, status),
  INDEX idx_channel_time (channel_id, create_time),
  INDEX idx_work_record (work_record_id),
  INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手机端登记的问题工单表';

-- 工单流转日志（同时承担「对报修人可见的回复」与「内部备注」两种角色）
CREATE TABLE service_ticket_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  ticket_id BIGINT NOT NULL COMMENT '关联工单（逻辑关联 service_ticket.id）',
  operator_type TINYINT NOT NULL COMMENT '操作人类型：0-报修人 1-员工 2-系统',
  operator_id BIGINT COMMENT '员工 user.id（operator_type=1 时有值）',
  operator_name VARCHAR(50) COMMENT '操作人展示名（冗余；报修人为匿名）',
  action VARCHAR(30) NOT NULL COMMENT '动作：SUBMIT/ACCEPT/ASSIGN/REPLY/CONFIRM/REOPEN/DONE/REJECT/CLOSE/RATE',
  remark TEXT COMMENT '内容正文/回复内容/退回理由',
  image_urls TEXT COMMENT '本次附带的图片 objectKey JSON 数组',
  visible_to_reporter TINYINT NOT NULL DEFAULT 1 COMMENT '是否对报修人可见：1-可见，0-仅内部（内部备注）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  INDEX idx_ticket_time (ticket_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问题工单流转日志表';

-- 打通既有工作记录：标记这条工作是从手机端工单转来的
-- 注意 source_type 用 IFNULL 兜底，保证既有「手工登记」路径不需要改一行代码
ALTER TABLE work_record
  ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
    COMMENT '来源：MANUAL-手工登记，TICKET-手机端问题工单' AFTER user_id,
  ADD COLUMN source_id BIGINT NULL
    COMMENT '来源主键（source_type=TICKET 时为 service_ticket.id）' AFTER source_type,
  ADD INDEX idx_source (source_type, source_id);

-- 已建库升级用：为默认渠道留一条示例（按需修改 dept_id 后再执行）
-- INSERT INTO ticket_channel (channel_code, channel_name, dept_id, need_phone, remark)
-- VALUES ('DEMO001', '信息科报修（示例，请删除）', 1, 1, '试点张贴于三楼机房门口');
