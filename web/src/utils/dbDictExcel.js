/**
 * 数据库表结构 —— Excel 数据字典解析。
 *
 * 为什么要有这条路：医院项目拿到的表结构经常不是 DDL，而是厂商给的《表结构说明.xlsx》。
 * 这里把「一行一个字段」的表格摊平成后端要的 TableSchema，之后走和贴脚本完全相同的
 * 增量合并逻辑（新增/变更/未变/疑似下线、差异摘要、历史版本），一行后端代码都不用改。
 *
 * 对厂商 Excel 的几点宽容处理，都是现场真会遇到的：
 * - 合并单元格只有第一格有值 → 表名为空时自动继承上一行的表；
 * - 列名写法五花八门（表名称 / table_name / 字段 / 列名）→ 按别名匹配；
 * - 表头前面有空行、混着说明行 → 自动找表头行，认不出来的列直接忽略并提示；
 * - 类型里自带长度（VARCHAR2(50)）→ 拆成 type + length，长度列有值时以长度列为准。
 */
import * as XLSX from 'xlsx'

/** 模板里第一个 sheet 的名字，下载模板和解析都以此为准 */
export const DICT_SHEET = '表结构字典'

/** 单次导入上限，与后端 MAX_TABLES_PER_IMPORT 保持一致 */
export const MAX_TABLES = 500

/**
 * 列定义：header 是模板里的标准表头，aliases 用来兼容厂商各种写法。
 * 匹配前会把表头去空格、去括号说明、转小写。
 */
export const DICT_COLUMNS = [
  {
    key: 'dbUser',
    header: '数据库用户',
    aliases: ['数据库用户/模式', '数据库用户', '库用户', '模式', '模式名', 'schema', 'dbname', 'user'],
    hint: '选填。填了就把这张表归到该用户目录下，等同脚本里的 HIS.USER_INFO 前缀；不填则归到页面上选定的库用户'
  },
  { key: 'tableName', header: '表名', required: true, aliases: ['表名', '表名称', '数据表', 'table', 'tablename', 'table_name'] },
  { key: 'tableComment', header: '表注释', aliases: ['表注释', '表说明', '表名称说明', 'table_comment', 'tablecomment'] },
  { key: 'columnName', header: '字段名', required: true, aliases: ['字段名', '字段', '列名', '列', '字段名称', 'column', 'column_name', 'field', 'columnname'] },
  { key: 'type', header: '类型', required: true, aliases: ['类型', '数据类型', '字段类型', 'type', 'data_type', 'datatype'] },
  { key: 'length', header: '长度', aliases: ['长度', '长度/精度', '精度', '宽度', '字节长', 'length', 'precision', 'scale'] },
  { key: 'nullable', header: '可空', aliases: ['可空', '是否可空', '可为空', '空值', 'nullable', 'null'] },
  { key: 'defaultValue', header: '默认值', aliases: ['默认值', '缺省值', '初值', 'default', 'default_value', 'data_default'] },
  { key: 'comment', header: '字段注释', aliases: ['字段注释', '字段说明', '注释', '说明', '含义', '业务含义', '备注', 'comment', 'column_comment'] },
  { key: 'primaryKey', header: '主键', aliases: ['主键', '是否主键', 'pk', 'primary_key', 'primarykey'] },
  { key: 'indexName', header: '索引名', aliases: ['索引名', '索引名称', '索引', 'index', 'index_name', 'indexname'] },
  { key: 'indexType', header: '索引类型', aliases: ['索引类型', 'index_type', 'indextype', '唯一索引'] }
]

const YES_SET = new Set(['y', 'yes', 'true', '1', '是', '√', '✓', 'pk', '主键', '有', '唯一'])
const NO_SET = new Set(['n', 'no', 'false', '0', '否', '×', 'x', '无', 'not', ''])

/** 表头归一：去掉空白与括号说明，转小写，便于别名匹配 */
function normalizeHeader(text) {
  return String(text ?? '')
    .replace(/[\s\u3000]/g, '')
    .replace(/[（(].*?[)）]/g, '')
    .replace(/[*：:]/g, '')
    .toLowerCase()
}

const ALIAS_INDEX = (() => {
  const map = new Map()
  for (const col of DICT_COLUMNS) {
    for (const alias of [col.header, ...col.aliases]) {
      const key = normalizeHeader(alias)
      if (key && !map.has(key)) map.set(key, col.key)
    }
  }
  return map
})()

/** 单元格取值：数字/日期都转成去尾零的字符串，空值统一成 '' */
function cellText(value) {
  if (value === null || value === undefined) return ''
  if (value instanceof Date) {
    const p = (n) => String(n).padStart(2, '0')
    return value.getFullYear() + '-' + p(value.getMonth() + 1) + '-' + p(value.getDate()) +
      ' ' + p(value.getHours()) + ':' + p(value.getMinutes()) + ':' + p(value.getSeconds())
  }
  if (typeof value === 'number') return String(value)
  return String(value).replace(/[\s\u3000]+$/g, '').replace(/^[\s\u3000]+/g, '')
}

/** 可空判定：现场写法太多，宁可多认几种，认不出来的按「可空」处理 */
function parseNullable(text, primaryKey) {
  const raw = String(text ?? '').trim()
  // 空着按可空；主键行即使没写也给成非空，不然目录里会出现「主键可为空」这种脏数据
  if (!raw) return !primaryKey
  const s = raw.toLowerCase().replace(/\s/g, '')
  if (['非空', '必填', '不可空', '不允许空', '不允许', '禁止空', '不为空'].some((k) => raw.includes(k))) return false
  if (['notnull', 'not_null', 'n', 'no', 'false', '0', '否', '不'].includes(s)) return false
  if (['可空', '允许空', '可为空', '允许为空', '可以为空'].some((k) => raw.includes(k))) return true
  if (['null', 'nullable', 'y', 'yes', 'true', '1', '是', '可'].includes(s)) return true
  // 只剩看不懂的说法了：按可空登记，预览里能看到，别在这里瞎猜成非空
  return true
}

function parseYes(text) {
  const s = String(text ?? '').trim().toLowerCase().replace(/\s/g, '')
  if (!s) return false
  return YES_SET.has(s) || s.includes('是') || s.includes('主键') || s.includes('唯一')
}

/** VARCHAR2(50) / decimal(10,2) 拆成类型与长度；已有长度列时以长度列为准 */
function splitTypeAndLength(rawType, rawLength) {
  const text = String(rawType ?? '').trim()
  const match = text.match(/^([\w\s$]+?)\s*\(([^)]*)\)\s*$/)
  if (!match) return { type: text, length: String(rawLength ?? '').trim() }
  const inlineLength = match[2].trim().replace(/\s/g, '')
  const outer = String(rawLength ?? '').trim()
  return { type: match[1].trim(), length: outer || inlineLength }
}

/** 索引类型归一，和后端 TableSchemaDiffer 的口径对齐 */
function normalizeIndexType(text, isPrimary) {
  const s = String(text ?? '').trim().toLowerCase().replace(/\s/g, '')
  if (isPrimary || s.includes('主键') || s === 'primary' || s === 'pk') return 'PRIMARY'
  if (s.includes('唯一') || s === 'unique' || s === 'u') return 'UNIQUE'
  if (s.includes('全文') || s === 'fulltext') return 'FULLTEXT'
  return 'INDEX'
}

/** 找到 sheet 里的表头行：要求至少认出「表名」和「字段名」两列 */
function locateHeaderRow(rows) {
  const limit = Math.min(rows.length, 30)
  for (let r = 0; r < limit; r++) {
    const mapped = mapHeaderRow(rows[r])
    if (mapped.keys.includes('tableName') && mapped.keys.includes('columnName')) {
      return { rowIndex: r, keys: mapped.keys, unknown: mapped.unknown }
    }
  }
  return null
}

function mapHeaderRow(row) {
  const keys = []
  const unknown = []
  const maxCol = Math.max(row?.length || 0, 1)
  for (let c = 0; c < maxCol; c++) {
    const text = normalizeHeader(cellText(row?.[c]))
    if (!text) {
      keys.push(null)
      continue
    }
    const key = ALIAS_INDEX.get(text)
    keys.push(key || null)
    if (!key) unknown.push(cellText(row[c]))
  }
  return { keys, unknown }
}

function cellAt(row, keys, key) {
  const idx = keys.indexOf(key)
  return idx === -1 ? '' : cellText(row?.[idx])
}

/**
 * 解析整本工作簿。
 * @param {ArrayBuffer|Uint8Array} buffer 上传文件读出来的二进制
 * @returns {{tables: Array, warnings: string[], stats: object}}
 */
export function parseDictWorkbook(buffer) {
  const wb = XLSX.read(buffer, { type: 'array', cellDates: true })
  const warnings = []
  /** Map<归属key, {dbUser, tableName, tableComment, columns: [], indexRows: []}> */
  const tables = new Map()
  /** 上一行落在哪张表：合并单元格时表名和库用户都要跟着它走 */
  let lastEntry = null
  let usedSheets = 0
  let dataRows = 0

  for (const sheetName of wb.SheetNames) {
    const sheet = wb.Sheets[sheetName]
    const rows = XLSX.utils.sheet_to_json(sheet, { header: 1, blankrows: false, defval: '' })
    const header = locateHeaderRow(rows)
    if (!header) {
      // 列数很少的小 sheet 基本是封面或说明页，安静跳过；列数像数据表却没认出来才值得提示
      const looksLikeData = rows.some((row) => (row?.length || 0) >= 4)
      if (looksLikeData) {
        warnings.push(`工作表「${sheetName}」没找到表头（需要「表名」和「字段名」两列），已跳过`)
      }
      continue
    }
    usedSheets++
    if (header.unknown.length) {
      warnings.push(`工作表「${sheetName}」有 ${header.unknown.length} 列没认出来，已忽略：${header.unknown.slice(0, 5).join('、')}`)
    }

    for (let r = header.rowIndex + 1; r < rows.length; r++) {
      const row = rows[r]
      const lineNo = r + 1
      const rawTable = cellAt(row, header.keys, 'tableName')
      const column = cellAt(row, header.keys, 'columnName')
      const type = cellAt(row, header.keys, 'type')
      const tableComment = cellAt(row, header.keys, 'tableComment')
      const dbUser = cellAt(row, header.keys, 'dbUser')

      // 表名为空且整行没什么内容 → 空行，安静跳过
      if (!rawTable && !column && !type && !tableComment && !cellAt(row, header.keys, 'comment')) continue

      // 合并单元格：厂商文档常只在每张表第一行写表名和库用户，后面留空就跟着上一行
      let table = rawTable
      let owner = dbUser
      if (!table) {
        if (!lastEntry) {
          warnings.push(`第 ${lineNo} 行没有表名，且前面也没有出现过表名，已跳过`)
          continue
        }
        table = lastEntry.tableName
        if (!owner) owner = lastEntry.dbUser
      }

      const entry = ensureTable(tables, owner, table)
      if (!entry) {
        warnings.push(`第 ${lineNo} 行表名「${table}」不是合法标识符（只能用字母、数字、下划线，且不以数字开头），已跳过`)
        continue
      }
      lastEntry = entry
      if (tableComment && !entry.tableComment) entry.tableComment = tableComment

      // 只填了表名/表注释、没有字段名 → 这是表级说明行，不当字段处理
      if (!column) {
        if (type) warnings.push(`第 ${lineNo} 行有类型但没有字段名，只登记了表注释，已跳过该字段`)
        continue
      }

      dataRows++
      const primaryKey = parseYes(cellAt(row, header.keys, 'primaryKey'))
      const { type: colType, length } = splitTypeAndLength(type, cellAt(row, header.keys, 'length'))
      const column_ = {
        name: column,
        type: colType,
        length,
        nullable: parseNullable(cellAt(row, header.keys, 'nullable'), primaryKey),
        defaultValue: cellAt(row, header.keys, 'defaultValue'),
        comment: cellAt(row, header.keys, 'comment'),
        primaryKey
      }
      const dup = entry.columns.find((c) => c.name.toLowerCase() === column.toLowerCase())
      if (dup) {
        warnings.push(`第 ${lineNo} 行「${table}.${column}」与前面重复，已用这一行的内容覆盖`)
        Object.assign(dup, column_)
      } else {
        entry.columns.push(column_)
      }

      const indexName = cellAt(row, header.keys, 'indexName')
      if (indexName) {
        entry.indexRows.push({
          name: indexName,
          type: normalizeIndexType(cellAt(row, header.keys, 'indexType'), indexName.toUpperCase() === 'PRIMARY'),
          column: column
        })
      }
    }
  }

  if (!usedSheets) {
    throw new Error('没找到能识别的工作表：表头里至少要同时有「表名」和「字段名」两列')
  }
  if (tables.size > MAX_TABLES) {
    throw new Error(`一共 ${tables.size} 张表，超过单次上限 ${MAX_TABLES} 张，请按库或模块拆分后再导`)
  }

  const result = []
  for (const entry of tables.values()) {
    if (!entry.columns.length) {
      warnings.push(`表 ${entry.tableName} 只有表级信息、一个字段都没有，已跳过`)
      continue
    }
    result.push({
      dbUser: entry.dbUser || null,
      tableName: entry.tableName,
      tableComment: entry.tableComment || '',
      columns: entry.columns,
      indexes: buildIndexes(entry)
    })
  }
  if (!result.length) {
    throw new Error('表头认出来了，但没解析出任何字段：检查一下「字段名」和「类型」两列有没有填')
  }

  const columnTotal = result.reduce((sum, t) => sum + t.columns.length, 0)
  return {
    tables: result,
    warnings,
    stats: {
      sheets: usedSheets,
      tables: result.length,
      columns: columnTotal,
      dataRows,
      dbUsers: [...new Set(result.map((t) => t.dbUser).filter(Boolean))]
    }
  }
}

/** 同一张表可能因为「数据库用户」列写法不一而分裂，这里用 用户+表名 做 key */
/**
 * 同一张表可能分散在多行，用「用户+表名」大写归一做 key；表名非法返回 null 让调用方跳过。
 * 库用户列空着但这张表前面已经出现过（且只出现过一次）时认成同一张表：
 * 否则会被拆成「HIS.PATIENT_INFO」和「PATIENT_INFO」两条，后端还要报表名重复。
 */
function ensureTable(map, dbUser, tableName) {
  const user = String(dbUser ?? '').trim()
  const name = String(tableName ?? '').trim()
  if (!/^[A-Za-z_][A-Za-z0-9_$#]*$/.test(name)) {
    return null
  }
  const upper = name.toUpperCase()
  const key = (user ? user.toUpperCase() + '.' : '') + upper
  if (!user && !map.has(key)) {
    const sameName = [...map.values()].filter((item) => item.tableName.toUpperCase() === upper)
    if (sameName.length === 1) return sameName[0]
  }
  if (!map.has(key)) {
    map.set(key, {
      key,
      dbUser: user,
      tableName: name,
      tableComment: '',
      columns: [],
      indexRows: []
    })
  }
  return map.get(key)
}

/** 主键合成 PRIMARY 索引 + 索引列按出现顺序拼联合索引 */
function buildIndexes(entry) {
  const indexes = []
  const byName = new Map()
  for (const row of entry.indexRows) {
    const name = row.name
    if (!byName.has(name)) byName.set(name, { name, type: row.type, columns: [] })
    const idx = byName.get(name)
    if (row.type === 'PRIMARY') idx.type = 'PRIMARY'
    if (row.type === 'UNIQUE' && idx.type !== 'PRIMARY') idx.type = 'UNIQUE'
    if (!idx.columns.includes(row.column)) idx.columns.push(row.column)
  }
  const pkColumns = entry.columns.filter((c) => c.primaryKey).map((c) => c.name)
  if (pkColumns.length) {
    const exist = [...byName.values()].find((i) => i.type === 'PRIMARY')
    if (exist) {
      exist.columns = pkColumns
    } else {
      byName.set('PRIMARY', { name: 'PRIMARY', type: 'PRIMARY', columns: pkColumns })
    }
  }
  for (const idx of byName.values()) {
    if (idx.columns.length) indexes.push(idx)
  }
  return indexes.sort((a, b) => (a.type === 'PRIMARY' ? -1 : b.type === 'PRIMARY' ? 1 : a.name.localeCompare(b.name)))
}

/** 表头行 + 三行示例，照着填就能被认出来 */
export function buildTemplateWorkbook() {
  const header = DICT_COLUMNS.map((c) => c.header)
  const example = [
    ['HIS', 'PATIENT_INFO', '患者基本信息', 'PATIENT_ID', 'VARCHAR2', '50', '否', '', '患者唯一标识', '是', 'PRIMARY', '主键'],
    ['HIS', 'PATIENT_INFO', '患者基本信息', 'NAME', 'VARCHAR2', '50', '否', '', '患者姓名', '', 'IDX_NAME', '普通'],
    ['HIS', 'PATIENT_INFO', '患者基本信息', 'AGE', 'NUMBER', '3', '是', '0', '年龄', '', 'IDX_NAME', '普通'],
    ['HIS', 'PATIENT_INFO', '患者基本信息', 'CREATE_TIME', 'DATE', '', '是', 'SYSDATE', '建档时间', '', '', ''],
    ['LIS', 'SPECIMENT_NO', '标本号表', 'ID', 'BIGINT', '20', '否', '', '自增主键', '是', '', '']
  ]
  const dictSheet = XLSX.utils.aoa_to_sheet([header, ...example])
  dictSheet['!cols'] = header.map((h) => ({ wch: Math.max(12, h.length * 2 + 4) }))
  const guide = [
    ['数据库表结构字典 —— 填写说明'],
    [''],
    ['一行写一个字段；同一张表的多行，表名、库用户、表注释可以只在第一行写（合并单元格也行）。'],
    [''],
    ['列名', '是否必填', '说明'],
    ...DICT_COLUMNS.map((c) => [c.header, c.required ? '必填' : '选填', c.hint || (c.key === 'length' ? '如 50 或 10,2；类型里已写 (50) 时可以不填' : '')]),
    [''],
    ['可空列认这些写法：是/否、Y/N、NULL/NOT NULL、可空/非空、必填。空着按可空处理，主键行按非空处理。'],
    ['主键列填 是/√/1/PK 都会认；多个字段都是主键时，会自动合成一个联合主键索引。'],
    ['索引名列：同一个索引的多行填同一个名字，按行的先后顺序当作索引列顺序；索引类型可填 主键/唯一/普通。'],
    ['数据库用户列：填了就直接归到该用户目录下（和脚本里写 HIS.USER_INFO 一个效果），目录里没有会在同一系统下自动建档。'],
    [''],
    ['一次最多 ' + MAX_TABLES + ' 张表，超了请按库或模块拆分。'],
    ['导入前页面会先做差异预览：哪些是新表、哪些结构变了、变了哪一列，看清了再落库。']
  ]
  const guideSheet = XLSX.utils.aoa_to_sheet(guide)
  guideSheet['!cols'] = [{ wch: 16 }, { wch: 10 }, { wch: 90 }]
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, dictSheet, DICT_SHEET)
  XLSX.utils.book_append_sheet(wb, guideSheet, '填写说明')
  return wb
}

export function downloadDictTemplate(fileName = '表结构字典导入模板.xlsx') {
  XLSX.writeFile(buildTemplateWorkbook(), fileName)
}