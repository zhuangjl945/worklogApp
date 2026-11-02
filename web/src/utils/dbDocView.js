/**
 * 数据库文档模块 —— 接口数据归一化。
 *
 * 为什么要这一层：
 * 1. 后端把整份解析结果塞在 parsed_json 这个 LONGTEXT 字段里，接口回来的是字符串而不是数组，
 *    页面直接 v-for 就是一片空白（就是「查看详情没有数据」的根因）；
 * 2. 历史数据经历过字段改名：表注释 comment -> tableComment、索引 unique 布尔 -> type 字符串，
 *    时间字段也从 createdAt 改成过 createTime，老记录里两套写法并存；
 * 3. v2 之后详情接口改成 { batch, changes, archived } 信封，批次里也可能直接带 tables 数组。
 * 三种来源统一在这里摊平成同一套形状，页面只管渲染，不用反复跟后端版本较劲。
 */

/** JSON 安全解析：脏数据只降级成 null，不把整个弹窗打挂 */
function safeParse(value) {
  if (!value) return null
  if (typeof value === 'object') return value
  try {
    return JSON.parse(value)
  } catch (e) {
    return null
  }
}

/** 布尔归一：新数据是 true/false，老数据可能是 YES/NO 或 1/0 */
function toBool(value, fallback) {
  if (value === undefined || value === null || value === '') return fallback
  if (typeof value === 'string') {
    const s = value.toUpperCase()
    if (s === 'YES' || s === 'TRUE' || s === '1') return true
    if (s === 'NO' || s === 'FALSE' || s === '0') return false
    return fallback
  }
  return Boolean(value)
}

/** 空值统一成 null，方便页面用 == null 判「这个版本还没这个字段」 */
function nullish(value) {
  return value === undefined ? null : value
}

/** 索引类型归一：老数据只有 unique 布尔，新数据直接给 PRIMARY/UNIQUE/INDEX */
function normalizeIndexType(raw) {
  const t = String(raw.type || raw.indexType || '').toUpperCase()
  if (t) return t
  if (toBool(raw.unique, false)) return 'UNIQUE'
  return String(raw.name || '').toUpperCase() === 'PRIMARY' ? 'PRIMARY' : 'INDEX'
}

/** 索引列归一：可能是数组，也可能是逗号分隔的字符串 */
function normalizeIndexColumns(raw) {
  const cols = raw.columns || raw.columnList || ''
  if (Array.isArray(cols)) return cols.filter(Boolean)
  return String(cols)
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

/** 单个字段（列）归一 */
function normalizeColumn(raw) {
  return {
    name: raw.name || '',
    type: raw.type || '',
    length: raw.length == null ? '' : String(raw.length),
    nullable: toBool(raw.nullable, true),
    defaultValue: raw.defaultValue == null ? '' : String(raw.defaultValue),
    comment: raw.comment || '',
    primaryKey: toBool(raw.primaryKey, false)
  }
}

/** 单张表归一：顺手算出唯一 key，同名表挂在不同库用户下时折叠面板不会串；SQL 分析页也用它把解析结果喂给同一个组件 */
export function normalizeTable(raw) {
  const tableName = raw.tableName || '(未命名表)'
  const dbUser = raw.dbUser || ''
  return {
    key: (dbUser ? dbUser + '.' : '') + tableName,
    tableName,
    dbUser,
    tableComment: raw.tableComment || raw.comment || '',
    columns: (raw.columns || []).map(normalizeColumn),
    indexes: (raw.indexes || []).map((idx) => ({
      name: idx.name || '',
      type: normalizeIndexType(idx),
      columns: normalizeIndexColumns(idx)
    }))
  }
}

/** 本批某张表的处理结论归一（v2 的 TableChangeItem；v1 记录没有这一段） */
function normalizeChange(raw) {
  return {
    tableName: raw.tableName || '',
    dbUser: raw.dbUser || '',
    changeType: String(raw.changeType || raw.type || '').toUpperCase(),
    diffSummary: raw.diffSummary || '',
    oldColumnCount: nullish(raw.oldColumnCount),
    newColumnCount: nullish(raw.newColumnCount),
    tableComment: raw.tableComment || '',
    tableDocId: nullish(raw.tableDocId)
  }
}

function pickList(primary, fallback, key) {
  if (Array.isArray(primary)) return primary
  if (Array.isArray(fallback ? fallback[key] : null)) return fallback[key]
  return []
}

/**
 * 把 /api/db-doc/{id} 的响应摊平成详情页/详情弹窗直接可用的对象。
 * 传空返回 null，调用方自己判空。
 */
export function normalizeDoc(raw) {
  if (!raw) return null
  // v2 详情是 { batch, changes, archived } 信封；v1 直接就是批次实体
  const batch = raw.batch || raw
  const changes = Array.isArray(raw.changes) ? raw.changes.map(normalizeChange) : []
  // parsedJson 才是「当时解析出来的结果」；接口哪天直接带 tables 数组也照样兜住
  const parsed = safeParse(batch.parsedJson) || safeParse(batch.parsed) || {}
  const tableList = pickList(batch.tables, parsed, 'tables')
  return {
    id: batch.id,
    title: batch.title || '',
    sourceType: batch.sourceType || SOURCE_FALLBACK,
    sourceRef: batch.sourceRef || '',
    systemId: nullish(batch.systemId),
    systemName: batch.systemName || '',
    databaseId: nullish(batch.databaseId),
    dbUser: batch.dbUser || '',
    tableCount: batch.tableCount == null ? tableList.length : batch.tableCount,
    addedCount: nullish(batch.addedCount),
    updatedCount: nullish(batch.updatedCount),
    unchangedCount: nullish(batch.unchangedCount),
    missingCount: nullish(batch.missingCount),
    createdBy: nullish(batch.createdBy),
    createdByName: batch.createdByName || '',
    createTime: batch.createTime || batch.createdAt || '',
    updateTime: batch.updateTime || batch.updatedAt || '',
    sourceSql: batch.sourceSql || '',
    tables: tableList.map(normalizeTable),
    warnings: pickList(batch.warnings, parsed, 'warnings').filter(Boolean),
    dbUsers: pickList(batch.dbUsers, parsed, 'dbUsers').filter(Boolean),
    changes,
    archived: raw.archived === undefined ? batch.systemId != null : Boolean(raw.archived)
  }
}

/**
 * 列表行归一：只摊平列表要展示的字段。
 * 列表接口不返回大字段，这里绝不去解 JSON，一页十几行没必要各解一次。
 */
export function normalizeRow(raw) {
  return {
    id: raw.id,
    title: raw.title || '',
    sourceType: raw.sourceType || SOURCE_FALLBACK,
    sourceRef: raw.sourceRef || '',
    systemId: nullish(raw.systemId),
    systemName: raw.systemName || '',
    databaseId: nullish(raw.databaseId),
    dbUser: raw.dbUser || '',
    tableCount: raw.tableCount == null ? 0 : raw.tableCount,
    addedCount: nullish(raw.addedCount),
    updatedCount: nullish(raw.updatedCount),
    unchangedCount: nullish(raw.unchangedCount),
    missingCount: nullish(raw.missingCount),
    createdByName: raw.createdByName || '',
    createTime: raw.createTime || raw.createdAt || '',
    updateTime: raw.updateTime || raw.updatedAt || ''
  }
}

/** 这批表结构从哪来：后端 v2.1 才有 source_type，老记录按 DDL 兜住 */
const SOURCE_FALLBACK = 'DDL'

const SOURCE_TYPE_TEXT = {
  DDL: '建表脚本',
  EXCEL: 'Excel 数据字典',
  SCAN: '直连采集'
}

export function sourceTypeText(type) {
  const t = String(type || SOURCE_FALLBACK).toUpperCase()
  return SOURCE_TYPE_TEXT[t] || t
}

/** 时间显示：后端给 ISO-8601（2026-10-29T16:20:31），统一成 2026-10-29 16:20:31 */
export function formatDocTime(value) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 19)
}

/** 索引类型中文名，弹窗和详情页共用一套口径 */
const INDEX_TYPE_TEXT = {
  PRIMARY: '主键',
  UNIQUE: '唯一',
  FULLTEXT: '全文',
  SPATIAL: '空间',
  INDEX: '普通'
}

export function indexTypeText(type) {
  const t = String(type || '').toUpperCase()
  return INDEX_TYPE_TEXT[t] || type || '普通'
}

export function indexTagType(type) {
  const t = String(type || '').toUpperCase()
  if (t === 'PRIMARY') return 'warning'
  if (t === 'UNIQUE') return 'success'
  return 'info'
}

/** 本批处理结论的中文口径，与后端 change_type 注释保持一致 */
const CHANGE_TYPE_TEXT = {
  NEW: '新增',
  CHANGED: '结构变更',
  UNCHANGED: '未变化',
  MISSING: '本次未出现',
  // 人工修订：页面上直接改结构留下的版本，和脚本导入的 CHANGED 分开算
  MANUAL: '人工修订'
}

export function changeTypeText(type) {
  const t = String(type || '').toUpperCase()
  return CHANGE_TYPE_TEXT[t] || type || '—'
}

export function changeTagType(type) {
  const t = String(type || '').toUpperCase()
  if (t === 'NEW') return 'success'
  if (t === 'CHANGED') return 'warning'
  if (t === 'MISSING') return 'danger'
  // 人工修订不是坏事，只是要看得出来：用中性主色，别和「疑似下线」那种告警红混在一起
  if (t === 'MANUAL') return 'primary'
  return 'info'
}