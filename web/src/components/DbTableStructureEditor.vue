<script setup>
import { computed, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'

/**
 * 表结构人工修订编辑器（改已登记的表 / 手工建档共用一份）。
 *
 * 以前目录里的表结构只有一条修改路径：再导一遍建表脚本或 Excel。
 * 现场常有「厂商只给了两句口头说明」「脚本还没拿到」的情况，所以这里允许直接在页面上改
 * 表名、表注释、字段和索引；保存后与导入走同一套口径 —— 真变了才覆盖当前快照，
 * 并留一条「人工修订」的历史版本（那条版本不挂批次，事后分得清是人改的还是脚本导的）。
 *
 * 组件只负责收集与校验，不打接口：保存、提示、刷新都由父层统一处理，免得两处弹两种文案。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** edit = 修订一张已登记的表；create = 手工建档 */
  mode: { type: String, default: 'edit' },
  /** create 模式下的归属候选（已登记的库用户） */
  databases: { type: Array, default: () => [] },
  /** 打开时带进来的结构：{ databaseId, tableName, tableComment, columns, indexes } */
  initial: { type: Object, default: () => ({}) },
  saving: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'submit'])

const INDEX_TYPES = [
  { value: 'PRIMARY', label: '主键' },
  { value: 'UNIQUE', label: '唯一' },
  { value: 'INDEX', label: '普通' },
  { value: 'FULLTEXT', label: '全文' },
  { value: 'SPATIAL', label: '空间' }
]

const form = reactive({ databaseId: null, tableName: '', tableComment: '', columns: [], indexes: [] })

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

/** 每次打开都重新拷一份，改坏了关掉再开就能重来，不会污染抽屉里已经显示的数据 */
watch(
  () => props.modelValue,
  (opened) => {
    if (!opened) return
    form.databaseId = props.initial.databaseId ?? null
    form.tableName = props.initial.tableName || ''
    form.tableComment = props.initial.tableComment || ''
    form.columns = (props.initial.columns || []).map(cloneColumn)
    form.indexes = (props.initial.indexes || []).map(cloneIndex)
    // 建档时给一行空字段，页面不至于空得让人不知道从哪填
    if (props.mode === 'create' && !form.columns.length) form.columns = [blankColumn()]
  },
  { immediate: true }
)

function blankColumn() {
  return { name: '', type: '', length: '', nullable: true, defaultValue: '', comment: '', primaryKey: false }
}

function blankIndex() {
  return { name: '', type: 'INDEX', columns: [] }
}

function cloneColumn(col) {
  return {
    name: col.name || '',
    type: col.type || '',
    length: col.length == null ? '' : String(col.length),
    nullable: col.nullable !== false,
    defaultValue: col.defaultValue == null ? '' : String(col.defaultValue),
    comment: col.comment || '',
    primaryKey: !!col.primaryKey
  }
}

function cloneIndex(idx) {
  return {
    name: idx.name || '',
    type: String(idx.type || 'INDEX').toUpperCase(),
    columns: Array.isArray(idx.columns) ? [...idx.columns] : splitColumns(idx.columns)
  }
}

/** 老数据里索引列有可能是「a, b」这种字符串，先摊成数组再进编辑器 */
function splitColumns(value) {
  return String(value || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

// ============================== 字段行操作 ==============================

function addColumn() {
  form.columns.push(blankColumn())
}

function removeColumn(index) {
  form.columns.splice(index, 1)
}

function moveColumn(index, delta) {
  const target = index + delta
  if (target < 0 || target >= form.columns.length) return
  const [row] = form.columns.splice(index, 1)
  form.columns.splice(target, 0, row)
}

// ============================== 索引行操作 ==============================

function addIndex() {
  if (!form.columns.filter((col) => text(col.name)).length) {
    ElMessage.warning('先把字段填上，索引要选字段')
    return
  }
  form.indexes.push(blankIndex())
}

function removeIndex(index) {
  form.indexes.splice(index, 1)
}

/** 索引候选字段：用字段名的原始写法，保存时后端还会照字段表再回写一次 */
const columnOptions = computed(() =>
  form.columns
    .map((col) => text(col.name))
    .filter((name, position, list) => name && list.indexOf(name) === position)
    .map((name) => ({ value: name, label: name }))
)

/**
 * 主键只认一个口径：有「主键」索引就以它的列为准，字段那列的 PK 只读跟随；
 * 没有主键索引时才让勾选生效（后端会按勾了主键的字段合成一条）。
 * 两处各存一份主键是这套结构最容易出问题的地方，宁可在页面上就把它锁死。
 */
const primaryIndex = computed(() => form.indexes.find((idx) => idx.type === 'PRIMARY'))
const pkFromIndex = computed(() => (primaryIndex.value ? primaryIndex.value.columns.map(lower) : []))
const pkKeySet = computed(() => {
  if (pkFromIndex.value.length) return new Set(pkFromIndex.value)
  return new Set(form.columns.filter((col) => col.primaryKey).map((col) => lower(col.name)))
})

function isPrimary(row) {
  return pkKeySet.value.has(lower(row.name))
}

function text(value) {
  return String(value ?? '').trim()
}

function lower(value) {
  return text(value).toLowerCase()
}

// ============================== 校验与提交 ==============================

/** 与服务层同一条底线的本地校验：能在页面上说清的，就别让人点完保存才知道错 */
function validate() {
  if (props.mode === 'create' && !form.databaseId) return '请选择这张表挂在哪个数据库用户下'
  if (!text(form.tableName)) return '表名不能为空'
  if (!form.columns.length) return '至少要有 1 个字段'

  const seen = new Set()
  for (const col of form.columns) {
    const name = text(col.name)
    if (!name) return '有字段没填字段名'
    if (!text(col.type)) return '字段 ' + name + ' 没填类型'
    const key = lower(name)
    if (seen.has(key)) return '字段 ' + name + ' 重复了'
    seen.add(key)
  }

  if (form.indexes.filter((idx) => idx.type === 'PRIMARY').length > 1) return '只能有一条主键索引，请删掉多余的'
  const indexNames = new Set()
  for (const idx of form.indexes) {
    const cols = (idx.columns || []).map(text).filter(Boolean)
    if (idx.type === 'PRIMARY') {
      if (!cols.length) return '主键索引还没选字段，勾字段的主键或直接删掉这条'
      for (const col of cols) {
        if (!seen.has(col.toLowerCase())) return '主键引用了不存在的字段 ' + col + '，字段改名或删除后要记得同步主键'
      }
      continue
    }
    if (!cols.length) continue
    const name = text(idx.name)
    if (!name) return '索引（字段 ' + cols.join(', ') + '）没填索引名'
    const key = lower(name)
    if (indexNames.has(key)) return '索引名 ' + name + ' 重复了'
    indexNames.add(key)
    for (const col of cols) {
      if (!seen.has(col.toLowerCase())) return '索引 ' + name + ' 引用了不存在的字段 ' + col + '，字段改名或删除后要记得同步索引'
    }
  }
  return ''
}

function submit() {
  const message = validate()
  if (message) {
    ElMessage.warning(message)
    return
  }
  emit('submit', {
    databaseId: form.databaseId,
    tableName: text(form.tableName),
    tableComment: text(form.tableComment) || null,
    columns: form.columns.map((col) => ({
      name: text(col.name),
      type: text(col.type),
      length: text(col.length) || null,
      nullable: col.nullable !== false,
      defaultValue: text(col.defaultValue) || null,
      comment: text(col.comment) || null,
      primaryKey: isPrimary(col)
    })),
    // 没选字段的空索引行直接丢掉，别让它把「索引数」撑虚
    indexes: form.indexes
      .map((idx) => ({
        name: idx.type === 'PRIMARY' ? 'PRIMARY' : text(idx.name),
        type: idx.type,
        columns: (idx.columns || []).map(text).filter(Boolean)
      }))
      .filter((idx) => idx.columns.length)
  })
}

function close() {
  visible.value = false
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="mode === 'create' ? '手工登记一张表的结构' : '人工修订表结构：' + (initial.tableName || '')"
    width="1080px"
    top="5vh"
    :close-on-click-modal="false"
    append-to-body
  >
    <el-alert
      class="tip"
      type="info"
      :closable="false"
      show-icon
      :title="
        mode === 'create'
          ? '手上还没有整份建表脚本，也能先把结构登进目录；以后拿到脚本照常导入，会按差异合并到这一行。'
          : '改完只有结构真的变了才覆盖当前快照，并留一条「人工修订」版本。下次导入这张表的脚本时，比对基准就是这份改过的结构 —— 脚本怎么写的，仍会按差异覆盖回来。'
      "
    />

    <div class="head">
      <label v-if="mode === 'create'" class="headItem">
        <span class="label">归属数据库用户</span>
        <el-select v-model="form.databaseId" filterable placeholder="选系统下的库用户" style="width: 300px">
          <el-option v-for="db in databases" :key="db.id" :label="db.label" :value="db.id" />
        </el-select>
      </label>
      <label class="headItem grow">
        <span class="label">表名</span>
        <el-input v-model="form.tableName" placeholder="如 USER_INFO" class="mono" />
      </label>
      <label class="headItem grow">
        <span class="label">表注释</span>
        <el-input v-model="form.tableComment" placeholder="业务含义，如「住院患者基本信息」" />
      </label>
    </div>

    <div class="blockHead">
      <span class="blockTitle">字段（{{ form.columns.length }}）</span>
      <el-button size="small" :icon="Plus" @click="addColumn">加字段</el-button>
    </div>
    <el-table :data="form.columns" size="small" border max-height="330">
      <el-table-column type="index" label="#" width="42" align="center" />
      <el-table-column label="字段名" min-width="150">
        <template #default="{ row }">
          <el-input v-model="row.name" size="small" placeholder="必填" class="mono" />
        </template>
      </el-table-column>
      <el-table-column label="类型" width="130">
        <template #default="{ row }">
          <el-input v-model="row.type" size="small" placeholder="如 VARCHAR" class="mono" />
        </template>
      </el-table-column>
      <el-table-column label="长度" width="96">
        <template #default="{ row }">
          <el-input v-model="row.length" size="small" placeholder="255 / 10,2" />
        </template>
      </el-table-column>
      <el-table-column label="可空" width="66" align="center">
        <template #default="{ row }">
          <el-switch v-model="row.nullable" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="主键" width="72" align="center">
        <template #default="{ row }">
          <el-tooltip v-if="pkFromIndex.length" content="主键由下面那条「主键」索引决定，要改就改那条索引" placement="top">
            <el-tag v-if="isPrimary(row)" type="warning" size="small">PK</el-tag>
            <span v-else class="faint">—</span>
          </el-tooltip>
          <el-checkbox v-else v-model="row.primaryKey" />
        </template>
      </el-table-column>
      <el-table-column label="默认值" width="120">
        <template #default="{ row }">
          <el-input v-model="row.defaultValue" size="small" placeholder="—" />
        </template>
      </el-table-column>
      <el-table-column label="注释" min-width="180">
        <template #default="{ row }">
          <el-input v-model="row.comment" size="small" placeholder="业务口径" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" align="right" fixed="right">
        <template #default="{ $index }">
          <el-button link size="small" :disabled="$index === 0" @click="moveColumn($index, -1)">上移</el-button>
          <el-button link size="small" :disabled="$index === form.columns.length - 1" @click="moveColumn($index, 1)">下移</el-button>
          <el-button link size="small" type="danger" :icon="Delete" @click="removeColumn($index)" />
        </template>
      </el-table-column>
      <template #empty>
        <span class="faint">还没有字段，点上面「加字段」</span>
      </template>
    </el-table>

    <div class="blockHead">
      <span class="blockTitle">索引（{{ form.indexes.length }}）</span>
      <el-button size="small" :icon="Plus" @click="addIndex">加索引</el-button>
    </div>
    <el-table :data="form.indexes" size="small" border>
      <el-table-column label="索引名" min-width="170">
        <template #default="{ row }">
          <el-input v-if="row.type !== 'PRIMARY'" v-model="row.name" size="small" placeholder="如 IDX_PAT_NAME" class="mono" />
          <span v-else class="mono faint">PRIMARY（主键不用起名字）</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          <el-select v-model="row.type" size="small">
            <el-option v-for="item in INDEX_TYPES" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="包含字段" min-width="320">
        <template #default="{ row }">
          <el-select v-model="row.columns" size="small" multiple filterable placeholder="选字段，可多选（顺序就是索引顺序）" style="width: 100%">
            <el-option v-for="opt in columnOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="70" align="right">
        <template #default="{ $index }">
          <el-button link size="small" type="danger" :icon="Delete" @click="removeIndex($index)" />
        </template>
      </el-table-column>
      <template #empty>
        <span class="faint">这张表没有索引；勾了字段主键的话，保存时会自动成一条「主键」索引</span>
      </template>
    </el-table>

    <template #footer>
      <span class="footerHint">共 {{ form.columns.length }} 个字段 / {{ form.indexes.length }} 个索引</span>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ mode === 'create' ? '登记进目录' : '保存修改' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tip {
  margin-bottom: 12px;
}

.head {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.headItem {
  display: flex;
  align-items: center;
  gap: 8px;
}

.headItem.grow {
  flex: 1;
  min-width: 260px;
}

.label {
  font-size: 13px;
  color: var(--g-text-secondary);
  white-space: nowrap;
}

.blockHead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 14px 0 8px;
}

.blockTitle {
  font-size: 13px;
  font-weight: 600;
  color: var(--g-text);
}

.footerHint {
  float: left;
  font-size: 12px;
  color: var(--g-text-muted);
  line-height: 32px;
}

.mono {
  font-family: var(--g-font-mono, 'Consolas', monospace);
}

.faint {
  color: var(--g-text-faint);
}
</style>