import http from './http'

/** 查询所有参数（按分组归类） */
export async function sysConfigList() {
  return await http.get('/sys-configs')
}

/** 批量保存参数值 */
export async function sysConfigBatchSave(items) {
  return await http.put('/sys-configs/batch', items)
}

/** 新增参数 */
export async function sysConfigCreate(payload) {
  return await http.post('/sys-configs', payload)
}

/** 删除参数 */
export async function sysConfigDelete(id) {
  return await http.delete(`/sys-configs/${id}`)
}
