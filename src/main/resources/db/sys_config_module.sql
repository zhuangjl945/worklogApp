-- 系统参数配置表
-- 以 key-value 形式存储可调参数，管理员在「参数配置」页面维护，无需重启服务
USE work_log_system;

CREATE TABLE IF NOT EXISTS sys_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  config_group VARCHAR(50) NOT NULL COMMENT '参数分组（如 system / upload / rate_limit / oss）',
  config_key VARCHAR(100) NOT NULL COMMENT '参数键名（组内唯一）',
  config_value TEXT COMMENT '参数值（字符串形式，前端按类型解析）',
  config_label VARCHAR(100) NOT NULL COMMENT '参数中文标签，页面展示用',
  config_desc VARCHAR(255) DEFAULT '' COMMENT '参数说明，鼠标悬浮提示',
  sort_order INT DEFAULT 0 COMMENT '组内排序序号',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

  UNIQUE KEY uk_group_key (config_group, config_key),
  INDEX idx_group (config_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参数配置表';

-- 预置默认参数（与 application.yml 保持一致，首次部署后管理员可自行调整）
INSERT INTO sys_config (config_group, config_key, config_value, config_label, config_desc, sort_order) VALUES
-- 系统基础
('system', 'site_name',            '工作日志管理系统',  '系统名称',       '页面标题与侧边栏显示的系统名称',          1),
('system', 'jwt_expire_seconds',   '28800',           '登录有效期(秒)',  'JWT Token 过期时间，默认 28800 秒（8小时）', 2),

-- 文件上传
('upload', 'max_file_size_mb',     '5',               '单文件上限(MB)', '单个文件最大允许上传体积',                1),
('upload', 'max_request_size_mb',  '30',              '单次请求上限(MB)', '单次请求（含多文件）最大允许体积',         2),

-- 限流防护
('rate_limit', 'ip_count',             '8',            'IP窗口次数',       '同一 IP 在窗口期内允许的提交次数',          1),
('rate_limit', 'ip_window_seconds',    '600',          'IP窗口时长(秒)',   '限流滑动窗口长度',                        2),
('rate_limit', 'channel_qpm',          '60',           '渠道每分钟上限',   '单个登记渠道每分钟允许提交数',              3),
('rate_limit', 'phone_hour',           '5',            '手机号每小时上限', '同一手机号每小时允许提交数',               4),

-- OSS 存储
('oss', 'endpoint',                'oss-cn-hangzhou.aliyuncs.com', 'OSS Endpoint',       '阿里云 OSS 接入点',           1),
('oss', 'bucket',                  'worklog-oss',                   'OSS Bucket',         '存储桶名称',                   2),
('oss', 'dir_prefix',              'work-records/',                 'OSS 目录前缀',       '文件存储根路径前缀',            3),
-- 手机端问题登记表单：哪些字段必填、最少几个字、照片几张（改完即时生效，不用重启）
-- config_value：开关类填 1=必填 / 0=选填；数字类填个数
('ticket_form', 'title_required',        '1', '问题标题必填',   '手机端「问题标题」是否必填；关闭后仍可填，留空时用工单描述兜底', 1),
('ticket_form', 'title_min_len',         '4', '标题最少字数',   '填了标题就必须达到的字数，1~200；标题设为选填时最小按 0 处理',   2),
('ticket_form', 'content_required',      '1', '问题描述必填',   '手机端「问题描述」是否必填；与标题同时关闭时两者至少填一个',     3),
('ticket_form', 'content_min_len',       '5', '描述最少字数',   '填了描述就必须达到的字数，1~5000',                              4),
('ticket_form', 'category_required',     '0', '问题类型必填',   '必须亲手选问题类型（关闭时可选「不指定」，走渠道默认分类）；科室没有任何可选类型时自动不生效', 5),
('ticket_form', 'location_required',     '0', '发生地点必填',   '手机端「发生地点」是否必填',                                    6),
('ticket_form', 'contact_name_required', '0', '姓名工号必填',   '手机端「你的姓名或工号」是否必填',                              7),
('ticket_form', 'contact_phone_required','0', '联系电话必填',   '所有登记入口统一要求填联系电话；渠道自己勾了必填时以更严的一方为准', 8),
('ticket_form', 'min_images',            '0', '照片最少张数',   '至少上传几张现场照片，0 表示不作要求（会按最多张数收敛）',       9),
('ticket_form', 'max_images',            '9', '照片最多张数',   '最多允许几张现场照片，1~9',                                    10),

-- 手机端问题登记的流转规则：维修人员标记完成后，「待报修人确认」这一阶段要不要设时限
-- config_value：开关类填 1=开启 / 0=关闭；数字类填小时数
('ticket_flow', 'auto_confirm_enabled', '1', '自动确认已解决',     '维修人员标记完成后，报修人在时限内未确认则由系统自动确认；关闭则一直等报修人亲手确认', 1),
('ticket_flow', 'auto_confirm_hours',   '8', '自动确认时限(小时)', '从标记完成时开始计时，1~168 小时；改完最迟下一轮扫描（约 5 分钟）生效，不用重启',      2),

-- 紧急程度（响应时限，管理员可自由调整）
-- sort_order = urgency code（工单里的 urgency 字段值），config_value = SLA 响应时限（分钟）
('urgency', 'urgent',    '30',    '紧急',     '最高优先级，要求 30 分钟内响应',       1),
('urgency', 'high',      '120',   '高',       '较高优先级，要求 2 小时内响应',        2),
('urgency', 'normal',    '480',   '普通',     '一般优先级，要求 8 小时内响应',        3),
('urgency', 'low',       '1440',  '低',       '低优先级，要求 24 小时内响应',         4),

-- 日志级别（改完立刻生效，不用重启）。取值：TRACE / DEBUG / INFO / WARN / ERROR / OFF
('logging', 'root_level', 'INFO', '全局日志级别',   '整站默认级别；TRACE/DEBUG 会非常吵，生产建议 INFO 或 WARN', 1),
('logging', 'app_level',  'INFO', '业务日志级别',   '本系统包 com.zjl.worklog：工单、鉴权、OSS 等业务日志',     2),
('logging', 'sql_level',  'WARN', 'SQL 日志级别',   'MyBatis SQL；查慢查询时再临时调到 DEBUG，用完改回 WARN',    3);

-- ============================================================
-- 已建库升级用：补上「登记表单」分组的参数（首次部署可跳过，重复执行安全）
-- 语义与上面预置值一致；已经在参数配置页改过的键不会被覆盖
-- ============================================================
INSERT INTO `sys_config` (config_group, config_key, config_value, config_label, config_desc, sort_order)
SELECT * FROM (
  SELECT 'ticket_form' AS a, 'title_required'         AS b, '1' AS c, '问题标题必填'   AS d, '手机端「问题标题」是否必填；关闭后仍可填，留空时用工单描述兜底' AS e, 1 AS f
  UNION ALL SELECT 'ticket_form','title_min_len','4','标题最少字数','填了标题就必须达到的字数，1~200；标题设为选填时最小按 0 处理',2
  UNION ALL SELECT 'ticket_form','content_required','1','问题描述必填','手机端「问题描述」是否必填；与标题同时关闭时两者至少填一个',3
  UNION ALL SELECT 'ticket_form','content_min_len','5','描述最少字数','填了描述就必须达到的字数，1~5000',4
  UNION ALL SELECT 'ticket_form','category_required','0','问题类型必填','必须亲手选问题类型（关闭时可选「不指定」，走渠道默认分类）；科室没有任何可选类型时自动不生效',5
  UNION ALL SELECT 'ticket_form','location_required','0','发生地点必填','手机端「发生地点」是否必填',6
  UNION ALL SELECT 'ticket_form','contact_name_required','0','姓名工号必填','手机端「你的姓名或工号」是否必填',7
  UNION ALL SELECT 'ticket_form','contact_phone_required','0','联系电话必填','所有登记入口统一要求填联系电话；渠道自己勾了必填时以更严的一方为准',8
  UNION ALL SELECT 'ticket_form','min_images','0','照片最少张数','至少上传几张现场照片，0 表示不作要求（会按最多张数收敛）',9
  UNION ALL SELECT 'ticket_form','max_images','9','照片最多张数','最多允许几张现场照片，1~9',10
) AS seed
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_config` s WHERE s.config_group = 'ticket_form' AND s.config_key = seed.b
);

-- ============================================================
-- 已建库升级用：补上「工单流转」分组的参数（重复执行安全）
-- 只在键不存在时插入，管理员在参数配置页改过的值不会被这条脚本覆盖回去
-- ============================================================
INSERT INTO `sys_config` (config_group, config_key, config_value, config_label, config_desc, sort_order)
SELECT * FROM (
  SELECT 'ticket_flow' AS a, 'auto_confirm_enabled' AS b, '1' AS c, '自动确认已解决' AS d, '维修人员标记完成后，报修人在时限内未确认则由系统自动确认；关闭则一直等报修人亲手确认' AS e, 1 AS f
  UNION ALL SELECT 'ticket_flow','auto_confirm_hours','8','自动确认时限(小时)','从标记完成时开始计时，1~168 小时；改完最迟下一轮扫描（约 5 分钟）生效，不用重启',2
) AS seed
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_config` s WHERE s.config_group = 'ticket_flow' AND s.config_key = seed.b
);

-- ============================================================
-- 已建库升级用：补上「日志级别」分组（重复执行安全）
-- ============================================================
INSERT INTO `sys_config` (config_group, config_key, config_value, config_label, config_desc, sort_order)
SELECT * FROM (
  SELECT 'logging' AS a, 'root_level' AS b, 'INFO' AS c, '全局日志级别' AS d, '整站默认级别；TRACE/DEBUG 会非常吵，生产建议 INFO 或 WARN' AS e, 1 AS f
  UNION ALL SELECT 'logging','app_level','INFO','业务日志级别','本系统包 com.zjl.worklog：工单、鉴权、OSS 等业务日志',2
  UNION ALL SELECT 'logging','sql_level','WARN','SQL 日志级别','MyBatis SQL；查慢查询时再临时调到 DEBUG，用完改回 WARN',3
) AS seed
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_config` s WHERE s.config_group = 'logging' AND s.config_key = seed.b
);
