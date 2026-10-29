import http from './http'

// 凭据保管库 — 分页查询（支持关键字与分类筛选）
export async function credentialList(params) {
  return await http.get('/credential', { params })
}

// 新增凭据（密码/密钥由后端加密存储）
export async function credentialCreate(payload) {
  return await http.post('/credential', payload)
}

// 编辑凭据
export async function credentialUpdate(id, payload) {
  return await http.put(/credential/ + id, payload)
}

// 查看凭据详情（密文字段脱敏为 null）
export async function credentialDetail(id) {
  return await http.get(/credential/ + id)
}

// 解密并返回凭据明文（需二次确认后调用）
export async function credentialReveal(id) {
  return await http.get(/credential/ + id + '/reveal')
}

// 删除凭据
export async function credentialDelete(id) {
  return await http.delete(/credential/ + id)
}
