import http from './http'

/** 权限点清单 + 当前生效门槛（登录即可读，前端菜单收口要用） */
export async function permissionList() {
  return await http.get('/permissions')
}

/** 批量调整门槛：items = [{ key, role }] */
export async function permissionUpdate(items) {
  return await http.put('/permissions', { items })
}

/** 全部恢复内置下限 */
export async function permissionReset() {
  return await http.post('/permissions/reset')
}