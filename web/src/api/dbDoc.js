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

// ============================================================
// v2：业务系统 / 数据库用户 / 表结构目录
// 上一段 v1 的接口保持不变（分析批次仍走 /db-doc），这里只补三级目录与导入合并。
// ============================================================

/** 业务系统列表，带每个系统的库数、表数 */
export async function dbSystemList(params) {
  return await http.get('/db-doc/systems', { params })
}

/** 新增业务系统 */
export async function dbSystemCreate(payload) {
  return await http.post('/db-doc/systems', payload)
}

/** 修改业务系统 */
export async function dbSystemUpdate(id, payload) {
  return await http.put(`/db-doc/systems/${id}`, payload)
}

/** 删除业务系统（名下有库或表时后端会拒绝） */
export async function dbSystemDelete(id) {
  return await http.delete(`/db-doc/systems/${id}`)
}

/** 数据库用户列表 */
export async function dbDatabaseList(params) {
  return await http.get('/db-doc/databases', { params })
}

/** 某系统下启用的数据库用户，下拉用 */
export async function dbDatabaseEnabled(systemId) {
  return await http.get('/db-doc/databases/enabled', { params: { systemId } })
}

/** 新增数据库用户 */
export async function dbDatabaseCreate(payload) {
  return await http.post('/db-doc/databases', payload)
}

/** 修改数据库用户 */
export async function dbDatabaseUpdate(id, payload) {
  return await http.put(`/db-doc/databases/${id}`, payload)
}

/** 启用/停用数据库用户 */
export async function dbDatabaseToggle(id, status) {
  return await http.post(`/db-doc/databases/${id}/toggle`, null, { params: { status } })
}

/** 删除数据库用户 */
export async function dbDatabaseDelete(id) {
  return await http.delete(`/db-doc/databases/${id}`)
}

/** 三级目录概览：系统 → 数据库用户 */
export async function dbCatalog() {
  return await http.get('/db-doc/catalog')
}

/** 表目录分页 */
export async function dbTableList(params) {
  return await http.get('/db-doc/tables', { params })
}

/** 单张表的详情：结构 + 历史版本 + 还原的建表语句 */
export async function dbTableDetail(id) {
  return await http.get(`/db-doc/tables/${id}`)
}

/** 人工补充说明 / 手工调整在用与疑似下线 */
export async function dbTableManual(id, payload) {
  return await http.put(`/db-doc/tables/${id}/manual`, payload)
}

/**
 * 人工修订表结构：表名/注释/字段/索引整体提交，后端仍按差异判定，
 * 真变了才覆盖当前快照并留一条不挂批次的「人工修订」版本。
 * 返回 { changed, diffSummary, table }，changed=false 表示提交的内容和保存前一模一样。
 */
export async function dbTableStructureUpdate(id, payload) {
  return await http.put(`/db-doc/tables/${id}/structure`, payload)
}

/** 手工建档一张表：现场只有零散几张表、凑不出一份建表脚本时，直接往目录里补 */
export async function dbTableCreate(payload) {
  return await http.post('/db-doc/tables', payload)
}

/** 删除表登记 */
export async function dbTableDelete(id) {
  return await http.delete(`/db-doc/tables/${id}`)
}

/** 某张表的历史版本列表 */
export async function dbTableVersions(id) {
  return await http.get(`/db-doc/tables/${id}/versions`)
}

/** 单个历史版本的完整快照 */
export async function dbTableVersionDetail(versionId) {
  return await http.get(`/db-doc/table-versions/${versionId}`)
}
/** 某个库用户下的全部表结构，ER 图用 */
export async function dbDatabaseTables(id, limit) {
  return await http.get(`/db-doc/databases/${id}/tables`, { params: { limit } })
}

/** 导入前的差异预览（不落库）。tables 非空表示来源是 Excel 数据字典，后端就不再解析脚本 */
export async function dbPreviewDiff(sql, databaseId, tables) {
  return await http.post('/db-doc/preview-diff', { sql, databaseId, tables })
}

/** 解析并增量合并进目录 */
export async function dbImport(payload) {
  return await http.post('/db-doc/import', payload)
}

/** 某批次涉及的表变更清单 */
export async function dbBatchChanges(id) {
  return await http.get(`/db-doc/batches/${id}/changes`)
}

/** 把 v1 遗留的未归类记录重新导入目录 */
export async function dbRearchive(id, payload) {
  return await http.post(`/db-doc/batches/${id}/rearchive`, payload)
}