-- ============================================================
-- 权限设置：把「已存在的收口点」做成可配置（只可收紧，不可放宽）
--
-- 存储直接复用 sys_config，不新建表：
--   config_group = 'permission'，config_key = 权限点，config_value = 要求的最低角色
-- 权限点的定义、内置下限（floor）和中文名全部在代码里：
--   src/main/java/com/zjl/worklog/security/Permission.java
--
-- 三条必须知道的规则：
--   1) 配置值低于内置下限时，服务端一律按下限执行（Permission.clamp），
--      所以「把参数配置开放给普通员工」这种操作在数据库里改了也不会生效。
--   2) 本文件不是必须执行的：没配过的权限点自动按内置下限跑（PermissionService.reload）。
--      这里给出全量种子，只为生产变更可审阅、可对账。
--   3) permission.manage（权限设置入口本身）固定 ADMIN，不参与配置，防止把自己锁死。
--
-- 改完的生效范围：服务端立即生效（内存缓存在保存时刷新）；
-- 已打开的页面要刷新一次才会更新菜单与按钮。
-- ============================================================

INSERT INTO `sys_config` (config_group, config_key, config_value, config_label, config_desc, sort_order)
SELECT * FROM (
  SELECT 'permission' AS a, 'user.manage'            AS b, 'ADMIN'      AS c, '员工与角色管理'        AS d, '新增员工、改姓名科室、授予或收回角色、启用禁用账号' AS e, 0 AS f
  UNION ALL SELECT 'permission','dept.manage',        'ADMIN',     '科室管理',        '新建/修改/停用/删除科室与科室层级', 1
  UNION ALL SELECT 'permission','sysConfig.manage',   'ADMIN',     '参数配置',        '查看并修改系统参数（上传限制、限流、紧急程度等）', 2
  UNION ALL SELECT 'permission','permission.manage',  'ADMIN',     '权限设置',        '调整其他权限点的角色门槛（固定，不可下调）', 3
  UNION ALL SELECT 'permission','workCategory.manage','DEPT_ADMIN','工作分类维护',    '新建/修改/停用工作分类及其填写模板', 4
  UNION ALL SELECT 'permission','ticketChannel.manage','DEPT_ADMIN','登记渠道维护',   '新建/修改手机端问题登记渠道与二维码', 5
  UNION ALL SELECT 'permission','board.scopeDept',    'USER',      '看板/本科室范围', '能看到本科室同事的任务（只读，除非另有编辑权限）', 6
  UNION ALL SELECT 'permission','board.scopeAll',     'ADMIN',     '看板/全部科室范围','能跨科室查看全部任务', 7
  UNION ALL SELECT 'permission','report.deptCross',   'ADMIN',     '报表/跨科室筛选', '报表里能自由选择科室', 8
  UNION ALL SELECT 'permission','record.editDeptOthers','DEPT_ADMIN','编辑本科室他人记录','在看板上推进本科室同事的任务状态（删除仍只允许本人）', 9
) AS seed
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_config` s WHERE s.config_group = 'permission' AND s.config_key = seed.b
);

-- 恢复默认（等价于界面上的「恢复默认」按钮）：
-- DELETE FROM `sys_config` WHERE config_group = 'permission';