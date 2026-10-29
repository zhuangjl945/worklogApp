import http from './http'

/** 解析 SQL 返回 ParseResult（不保存） */
export async function dbDocParse(sql) {
  return await http.post('/db-doc/parse', { sql })
}

/** 解析并保存文档记录 */
export async function dbDocSave(sql, title) {
  return await http.post('/db-doc/save', { sql, title })
}

/** 分页查询已保存文档 */
export async function dbDocList(params) {
  return await http.get('/db-doc', { params })
}

/** 单条详情 */
export async function dbDocDetail(id) {
  return await http.get(`/db-doc/${id}`)
}

/** 删除文档 */
export async function dbDocDelete(id) {
  return await http.delete(`/db-doc/${id}`)
}
