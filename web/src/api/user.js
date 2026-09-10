import http from './http'

export async function userPage(params) {
  return await http.get('/users', { params })
}

/** 花名册：只给选人下拉用（id/用户名/姓名/科室），任何登录用户可查；/users 分页是系统管理员专属 */
export async function userRoster(params) {
  return await http.get('/users/roster', { params })
}

/** 编辑员工：realName 必填，可带 deptId / role / status */
export async function userUpdate(id, payload) {
  return await http.put(`/users/${id}`, payload)
}

export async function userCount(params) {
  return await http.get('/users/count', { params })
}

export async function userCreate(payload) {
  return await http.post('/users', payload)
}

export async function userUpdatePassword(id, newPassword) {
  return await http.put(`/users/${id}/password`, { newPassword })
}

/** 人员角色设置页的分页列表：带科室名，可按角色/科室/关键字/状态筛 */
export async function userRolePage(params) {
  return await http.get('/users/role-page', { params })
}

/** 三个角色的启用账号人数：概览卡用 */
export async function userRoleSummary() {
  return await http.get('/users/role-summary')
}

/** 单人授予/收回角色：role = USER / DEPT_ADMIN / ADMIN */
export async function userUpdateRole(id, role) {
  return await http.put(`/users/${id}/role`, { role })
}

/** 批量授予/收回角色：items = [{ id, role }]，返回 { updated, updatedNames, skipped } */
export async function userUpdateRolesBatch(items) {
  return await http.put('/users/roles/batch', { items })
}

export async function userUpdateStatus(id, status) {
  return await http.put(`/users/${id}/status`, { status })
}
