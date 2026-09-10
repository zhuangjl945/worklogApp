package com.zjl.worklog.user.dto;

/**
 * 选人下拉用的最小视图：只有 id / 用户名 / 姓名 / 科室。
 *
 * <p>为什么不直接把 /users 开放给所有人：那个分页视图带角色、状态、创建时间，
 * 属于员工管理的数据；而合同经办人、工单指派、任务转移只需要「叫什么、哪个科」。
 * 用独立的最小视图，既不用为三个下拉框放开整个员工管理，也少泄露几个字段。
 */
public record UserRosterView(Long id, String username, String realName, Long deptId) {
}