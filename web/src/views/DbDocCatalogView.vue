<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Files, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { dbCatalog, dbDatabaseList, dbTableCreate, dbTableDelete, dbTableDetail, dbTableList, dbTableManual, dbTableStructureUpdate, dbTableVersionDetail } from '../api/dbDoc'
import DbTableStructureEditor from '../components/DbTableStructureEditor.vue'
import { changeTagType, changeTypeText, formatDocTime, indexTagType, indexTypeText } from '../utils/dbDocView'

const route = useRoute()

const loading = ref(false)
const treeLoading = ref(false)
const treeData = ref([])
const treeRef = ref(null)
const currentNode = ref(null)
const rows = ref([])
const total = ref(0)
const summary = reactive({ systems: 0, databases: 0, tables: 0 })

const filters = reactive({
  systemId: route.query.systemId ? Number(route.query.systemId) : null,
  databaseId: route.query.databaseId ? Number(route.query.databaseId) : null,
  keyword: '',
  columnKeyword: '',
  status: 1,
  page: 1,
  size: 20
})

/** 目录树：系统 → 数据库用户，节点上直接标出表数量 */
async function loadCatalog() {
  treeLoading.value = true
  try {
    const resp = await dbCatalog()
    const data = resp.data || { systems: [], totalDatabases: 0, totalTables: 0 }
    summary.systems = (data.systems || []).length
    summary.databases = data.totalDatabases || 0
    summary.tables = data.totalTables || 0
    treeData.value = (data.systems || []).map((system) => ({
      id: `s-${system.id}`,
      label: system.systemName,
      kind: 'system',
      systemId: system.id,
      tableCount: system.tableCount || 0,
      children: (system.databases || []).map((db) => ({
        id: `d-${db.id}`,
        label: `${db.dbUser}${db.dbName ? ' @ ' + db.dbName : ''}`,
        kind: 'database',
        systemId: db.systemId,
        databaseId: db.id,
        tableCount: db.tableCount || 0,
        env: db.env
      }))
    }))
    // 带着 query 参数进来时（从系统页点表数跳转），把树里对应的节点高亮出来
    await nextTick()
    if (filters.databaseId) {
      const node = findNode(`d-${filters.databaseId}`)
      if (node) {
        currentNode.value = node
        treeRef.value?.setCurrentKey(node.id)
      }
    } else if (filters.systemId) {
      const node = findNode(`s-${filters.systemId}`)
      if (node) {
        currentNode.value = node
        treeRef.value?.setCurrentKey(node.id)
      }
    }
  } catch (e) {
    ElMessage.error(e?.message || '加载目录失败')
  } finally {
    treeLoading.value = false
  }
}

function findNode(key) {
  for (const system of treeData.value) {
    if (system.id === key) return system
    const hit = (system.children || []).find((child) => child.id === key)
    if (hit) return hit
  }
  return null
}

/** 点树节点 = 切过滤条件 */
function handleNodeClick(node) {
  currentNode.value = node
  filters.page = 1
  if (node.kind === 'database') {
    filters.databaseId = node.databaseId
    filters.systemId = null
  } else {
    filters.databaseId = null
    filters.systemId = node.systemId
  }
  loadTables()
}

/** 表清单 */
async function loadTables() {
  loading.value = true
  try {
    const resp = await dbTableList({
      systemId: filters.systemId || undefined,
      databaseId: filters.databaseId || undefined,
      keyword: filters.keyword || undefined,
      columnKeyword: filters.columnKeyword || undefined,
      status: filters.status === '' ? undefined : filters.status,
      page: filters.page,
      size: filters.size
    })
    rows.value = resp.data?.records || []
    total.value = resp.data?.total || 0
  } catch (e) {
    ElMessage.error(e?.message || '加载表清单失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  filters.page = 1
  loadTables()
}

function resetFilters() {
  filters.keyword = ''
  filters.columnKeyword = ''
  filters.status = 1
  filters.databaseId = null
  filters.systemId = null
  currentNode.value = null
  filters.page = 1
  loadTables()
}

// ============================== 详情抽屉 ==============================
const drawer = reactive({ visible: false, loading: false, data: null, versionLoading: false })

async function openDetail(row) {
  drawer.visible = true
  drawer.loading = true
  drawer.data = null
  try {
    const resp = await dbTableDetail(row.id)
    drawer.data = resp.data || null
  } catch (e) {
    ElMessage.error(e?.message || '加载表详情失败')
  } finally {
    drawer.loading = false
  }
}

/** 历史版本：点开看那一版的结构，才能回答「这列是什么时候加的」 */
const versionDialog = reactive({ visible: false, loading: false, data: null, columns: [], indexes: [] })

/** 把历史版本里的 JSON 文本摊成表格数据：模板里拿不到 JSON，只能在脚本解 */
function parseJsonArray(text) {
  if (!text) return []
  try {
    const value = JSON.parse(text)
    return Array.isArray(value) ? value : []
  } catch (e) {
    return []
  }
}

async function openVersion(version) {
  versionDialog.visible = true
  versionDialog.loading = true
  versionDialog.data = null
  versionDialog.columns = []
  versionDialog.indexes = []
  try {
    const resp = await dbTableVersionDetail(version.id)
    versionDialog.data = resp.data || null
    versionDialog.columns = parseJsonArray(resp.data?.columnsJson)
    versionDialog.indexes = parseJsonArray(resp.data?.indexesJson)
  } catch (e) {
    ElMessage.error(e?.message || '加载历史版本失败')
  } finally {
    versionDialog.loading = false
  }
}

/** 复制还原出来的建表语句，运维提变更单时常用 */
async function copyDdl() {
  const text = drawer.data?.renderedDdl
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('建表语句已复制')
  } catch (e) {
    ElMessage.warning('浏览器不允许直接复制，请在下方文本框里手动选中')
  }
}

// 人工备注编辑
const manual = reactive({ remark: '', status: 1, saving: false })

function syncManual() {
  manual.remark = drawer.data?.table?.remark || ''
  manual.status = drawer.data?.table?.status ?? 1
}

async function saveManual() {
  if (!drawer.data?.table) return
  manual.saving = true
  try {
    await dbTableManual(drawer.data.table.id, { remark: manual.remark, status: manual.status })
    ElMessage.success('已保存')
    await refreshDrawerTable(drawer.data.table.id)
    loadTables()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    manual.saving = false
  }
}

// ============================== 人工修订表结构 ==============================

/**
 * 目录里的结构以前只有一条修改路径：再导一遍脚本或 Excel。这里补上手工路径 ——
 * 表名、表注释、字段、索引都能在页面上改，保存仍走后端那套差异比对，
 * 只有真变了才覆盖当前快照，并留一条「人工修订」版本（不挂批次，事后分得清是人改的还是脚本导的）。
 * 「编辑结构」和「手工建表」共用同一个弹窗，靠 mode 区分。
 */
const editor = reactive({ visible: false, saving: false, mode: 'edit', initial: {}, databases: [] })

function openStructureEditor() {
  const table = drawer.data?.table
  if (!table) return
  editor.mode = 'edit'
  editor.initial = {
    databaseId: table.databaseId,
    tableName: table.tableName,
    tableComment: table.tableComment || '',
    columns: drawer.data.columns || [],
    indexes: drawer.data.indexes || []
  }
  editor.visible = true
}

/** 手工建档：先取一次启用的库用户当归属候选，默认落在当前筛选的那个库上 */
async function openCreateEditor() {
  try {
    const resp = await dbDatabaseList({ status: 1 })
    editor.databases = (resp.data || []).map((db) => ({
      id: db.id,
      label: (db.systemName || '未归类') + ' · ' + db.dbUser + (db.dbName ? ' @ ' + db.dbName : '') + (db.env && db.env !== 'prod' ? '（' + db.env + '）' : '')
    }))
    // 一个库用户都还没有时，手工建档没有落点，先把人指回上一级目录，别开一个填不了的弹窗
    if (!editor.databases.length) {
      ElMessage.warning('还没有数据库用户，先到「系统与数据库用户」建一个再手工建档')
      return
    }
  } catch (e) {
    ElMessage.error(e?.message || '加载数据库用户失败')
    return
  }
  editor.mode = 'create'
  editor.initial = { databaseId: filters.databaseId || null, tableName: '', tableComment: '', columns: [], indexes: [] }
  editor.visible = true
}

async function submitStructure(payload) {
  editor.saving = true
  try {
    if (editor.mode === 'create') {
      const resp = await dbTableCreate(payload)
      editor.visible = false
      ElMessage.success('已把 ' + (resp.data?.tableName || '这张表') + ' 登进目录')
      await Promise.all([loadCatalog(), loadTables()])
      if (resp.data?.id) openDetail({ id: resp.data.id })
      return
    }
    const tableId = drawer.data?.table?.id
    const resp = await dbTableStructureUpdate(tableId, payload)
    const result = resp.data
    ElMessage.success(result?.changed ? '已保存，并在变更历史里留了一条「人工修订」' : '内容和保存前一模一样，没有产生新版本')
    editor.visible = false
    await refreshDrawerTable(tableId)
    loadTables()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    editor.saving = false
  }
}

/** 重新拉一次详情：字段、索引、还原出来的建表语句、变更历史全部跟着刷新 */
async function refreshDrawerTable(id) {
  const resp = await dbTableDetail(id)
  drawer.data = resp.data || null
  syncManual()
}

async function removeTable(row) {
  try {
    await ElMessageBox.confirm(
      `确定把表「${row.tableName}」从目录里删除吗？历史版本会一起删掉，原始脚本仍保留在分析记录里。`,
      '删除确认',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await dbTableDelete(row.id)
    ElMessage.success('已删除')
    loadTables()
    loadCatalog()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

/** 顶部「范围」文案：直接用树节点的标签，避免再反查一遍树 */
const scopeLabel = computed(() => {
  if (!currentNode.value) return '全部'
  if (currentNode.value.kind === 'database') {
    const parent = treeData.value.find((item) => (item.children || []).some((child) => child.id === currentNode.value.id))
    return `${parent ? parent.label + ' · ' : ''}${currentNode.value.label}`
  }
  return currentNode.value.label
})

onMounted(async () => {
  await loadCatalog()
  await loadTables()
})
</script>

<template>
  <div class="db-catalog">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Files /></el-icon>
        <div>
          <div class="title">表结构目录</div>
          <div class="subtitle">
            {{ summary.systems }} 个系统 · {{ summary.databases }} 个数据库用户 · {{ summary.tables }} 张表；
            重复分析只补新增和变更，看得到每张表当前是什么结构
          </div>
        </div>
      </div>
      <div class="actions">
        <el-button :icon="Refresh" :loading="loading" @click="() => { loadCatalog(); loadTables() }">刷新</el-button>
        <el-button :icon="Plus" @click="openCreateEditor">手工建表</el-button>
        <router-link :to="{ name: 'db-doc-parse' }" class="linkBtn">
          <el-button type="primary" :icon="Search">去 SQL 分析导入</el-button>
        </router-link>
      </div>
    </div>

    <div class="layout">
      <!-- 左：系统 → 数据库用户 -->
      <el-card class="treeCard" shadow="never" v-loading="treeLoading">
        <template #header>
          <div class="cardHeader">
            <span>归属目录</span>
            <el-button link size="small" @click="resetFilters">看全部</el-button>
          </div>
        </template>
        <el-tree
          v-if="treeData.length"
          ref="treeRef"
          :data="treeData"
          node-key="id"
          highlight-current
          :expand-on-click-node="false"
          default-expand-all
          @node-click="handleNodeClick"
        >
          <template #default="{ data }">
            <span class="nodeRow">
              <span class="nodeLabel">
                <el-tag v-if="data.kind === 'database' && data.env && data.env !== 'prod'" size="small" type="warning">{{ data.env }}</el-tag>
                <span :class="data.kind === 'system' ? 'nodeSystem' : 'nodeDb'">{{ data.label }}</span>
              </span>
              <span class="nodeCount">{{ data.tableCount }}</span>
            </span>
          </template>
        </el-tree>
        <el-empty v-else description="还没有目录，先在「系统与数据库用户」里建系统，再去 SQL 分析导入" :image-size="60" />
      </el-card>

      <!-- 右：表清单 -->
      <div class="mainCol">
        <el-card class="card" shadow="never">
          <div class="filterBar">
            <el-input v-model="filters.keyword" placeholder="表名 / 表注释 / 库用户" clearable style="width: 230px" @keyup.enter="handleSearch" @clear="handleSearch" />
            <el-input v-model="filters.columnKeyword" placeholder="按字段名搜（如 patient_id）" clearable style="width: 220px" @keyup.enter="handleSearch" @clear="handleSearch" />
            <el-select v-model="filters.status" style="width: 130px" @change="handleSearch">
              <el-option :value="1" label="在用" />
              <el-option :value="0" label="疑似下线" />
              <el-option value="" label="全部状态" />
            </el-select>
            <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
            <el-button @click="resetFilters">重置</el-button>
            <span class="hint">范围：{{ scopeLabel }} · 共 {{ total }} 张</span>
          </div>

          <el-table v-loading="loading" :data="rows" border stripe row-key="id">
            <el-table-column label="表名" min-width="190">
              <template #default="{ row }">
                <el-link type="primary" :underline="false" class="mono" @click="openDetail(row)">{{ row.tableName }}</el-link>
              </template>
            </el-table-column>
            <el-table-column prop="tableComment" label="表注释" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">{{ row.tableComment || '—' }}</template>
            </el-table-column>
            <el-table-column label="归属" min-width="150">
              <template #default="{ row }">
                <div class="mainText">{{ row.systemName || '—' }}</div>
                <div class="subText mono">{{ row.dbUser || '—' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="字段" width="72" align="center">
              <template #default="{ row }">{{ row.columnCount }}</template>
            </el-table-column>
            <el-table-column label="索引" width="72" align="center">
              <template #default="{ row }">{{ row.indexCount }}</template>
            </el-table-column>
            <el-table-column prop="pkColumns" label="主键" min-width="120" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono">{{ row.pkColumns || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
                  {{ row.status === 1 ? '在用' : '疑似下线' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="结构最近变更" width="150">
              <template #default="{ row }">{{ formatDocTime(row.updateTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="130" align="right" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
                <el-button link type="danger" size="small" :icon="Delete" @click="removeTable(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="这个范围里还没有表结构，去 SQL 分析页导入建表语句" :image-size="70" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="filters.page"
              v-model:page-size="filters.size"
              :page-sizes="[20, 50, 100]"
              :total="total"
              background
              layout="total, sizes, prev, pager, next, jumper"
              @size-change="loadTables"
              @current-change="loadTables"
            />
          </div>
        </el-card>
      </div>
    </div>

    <!-- 表详情抽屉 -->
    <el-drawer v-model="drawer.visible" size="70%" :title="drawer.data ? drawer.data.table.tableName : '表详情'" @opened="syncManual">
      <div v-loading="drawer.loading" class="drawerBody">
        <template v-if="drawer.data">
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="所属系统">{{ drawer.data.table.systemName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="数据库用户">
              <span class="mono">{{ drawer.data.table.dbUser || '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="类型">{{ (drawer.data.table.dbType || '—').toUpperCase() }}</el-descriptions-item>
            <el-descriptions-item label="表注释">{{ drawer.data.table.tableComment || '—' }}</el-descriptions-item>
            <el-descriptions-item label="字段 / 索引">{{ drawer.data.table.columnCount }} / {{ drawer.data.table.indexCount }}</el-descriptions-item>
            <el-descriptions-item label="被分析次数">{{ drawer.data.table.parseCount }}</el-descriptions-item>
            <el-descriptions-item label="首次登记">{{ formatDocTime(drawer.data.table.createTime) }}</el-descriptions-item>
            <el-descriptions-item label="最近变更">{{ formatDocTime(drawer.data.table.updateTime) }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="drawer.data.table.status === 1 ? 'success' : 'danger'" size="small">
                {{ drawer.data.table.status === 1 ? '在用' : '疑似下线' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="blockTitle">
            字段（{{ drawer.data.columns.length }}）
            <el-button link type="primary" size="small" :icon="Edit" @click="openStructureEditor">编辑结构</el-button>
          </h4>
          <el-table :data="drawer.data.columns" size="small" border stripe>
            <el-table-column prop="name" label="字段名" min-width="140">
              <template #default="{ row }"><span class="mono">{{ row.name }}</span></template>
            </el-table-column>
            <el-table-column prop="type" label="类型" width="110" />
            <el-table-column label="长度" width="80">
              <template #default="{ row }">{{ row.length || '—' }}</template>
            </el-table-column>
            <el-table-column label="可空" width="70" align="center">
              <template #default="{ row }">
                <el-tag :type="row.nullable ? 'info' : 'danger'" size="small">{{ row.nullable ? '是' : '否' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="主键" width="70" align="center">
              <template #default="{ row }"><el-tag v-if="row.primaryKey" type="warning" size="small">PK</el-tag></template>
            </el-table-column>
            <el-table-column label="默认值" width="110">
              <template #default="{ row }">{{ row.defaultValue || '—' }}</template>
            </el-table-column>
            <el-table-column label="注释" min-width="160">
              <template #default="{ row }">{{ row.comment || '—' }}</template>
            </el-table-column>
          </el-table>

          <h4 class="blockTitle">索引（{{ drawer.data.indexes.length }}）</h4>
          <el-table :data="drawer.data.indexes" size="small" border stripe>
            <el-table-column label="索引名" min-width="160">
              <template #default="{ row }"><span class="mono">{{ row.name || '(无名)' }}</span></template>
            </el-table-column>
            <el-table-column label="类型" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="indexTagType(row.type)" size="small">{{ indexTypeText(row.type) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="包含字段" min-width="220">
              <template #default="{ row }">
                <span class="mono">{{ Array.isArray(row.columns) ? row.columns.join(', ') : row.columns }}</span>
              </template>
            </el-table-column>
          </el-table>

          <h4 class="blockTitle">
            建表语句
            <el-button link type="primary" size="small" @click="copyDdl">复制</el-button>
          </h4>
          <el-input :model-value="drawer.data.renderedDdl" type="textarea" :rows="10" class="ddlText" readonly />

          <h4 class="blockTitle">结构变更历史（{{ (drawer.data.versions || []).length }}）</h4>
          <el-table :data="drawer.data.versions" size="small" border stripe>
            <el-table-column label="处理" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="changeTagType(row.changeType)" size="small">{{ changeTypeText(row.changeType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="diffSummary" label="差异" min-width="240" show-overflow-tooltip />
            <el-table-column label="字段数" width="80" align="center">
              <template #default="{ row }">{{ row.columnCount }}</template>
            </el-table-column>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ formatDocTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="" width="80" align="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openVersion(row)">看该版</el-button>
              </template>
            </el-table-column>
          </el-table>

          <h4 class="blockTitle">人工补充</h4>
          <el-input v-model="manual.remark" type="textarea" :rows="2" placeholder="业务口径、坑、变更注意事项，只有自己系统里能看到的那部分" />
          <div class="manualRow">
            <el-radio-group v-model="manual.status">
              <el-radio-button :value="1">在用</el-radio-button>
              <el-radio-button :value="0">疑似下线</el-radio-button>
            </el-radio-group>
            <el-button type="primary" size="small" :loading="manual.saving" @click="saveManual">保存补充</el-button>
          </div>
        </template>
      </div>
    </el-drawer>

    <!-- 人工修订结构 / 手工建档：提交后仍由后端判差异、留版本 -->
    <DbTableStructureEditor
      v-model="editor.visible"
      :mode="editor.mode"
      :initial="editor.initial"
      :databases="editor.databases"
      :saving="editor.saving"
      @submit="submitStructure"
    />

    <!-- 历史版本快照 -->
    <el-dialog v-model="versionDialog.visible" title="该版本的结构快照" width="900px" top="6vh">
      <div v-loading="versionDialog.loading">
        <template v-if="versionDialog.data">
          <el-alert
            type="info"
            :closable="false"
            show-icon
            :title="`${changeTypeText(versionDialog.data.changeType)} · ${versionDialog.data.diffSummary || ''}`"
            class="verAlert"
          />
          <el-table :data="versionDialog.columns" size="small" border stripe>
            <el-table-column prop="name" label="字段名" min-width="140" />
            <el-table-column prop="type" label="类型" width="110" />
            <el-table-column prop="length" label="长度" width="80" />
            <el-table-column label="可空" width="70" align="center">
              <template #default="{ row }">{{ row.nullable ? '是' : '否' }}</template>
            </el-table-column>
            <el-table-column prop="defaultValue" label="默认值" width="110" />
            <el-table-column prop="comment" label="注释" min-width="150" />
          </el-table>

          <h4 class="blockTitle">该版索引（{{ versionDialog.indexes.length }}）</h4>
          <el-table :data="versionDialog.indexes" size="small" border stripe>
            <el-table-column prop="name" label="索引名" min-width="160" />
            <el-table-column label="类型" width="100" align="center">
              <template #default="{ row }">{{ indexTypeText(row.type) }}</template>
            </el-table-column>
            <el-table-column label="包含字段" min-width="200">
              <template #default="{ row }">{{ Array.isArray(row.columns) ? row.columns.join(', ') : row.columns }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 与 SQL 分析、系统与库用户同宽：切菜单时版式不再跳一截 */
.db-catalog {
  width: 100%;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.titleWrap {
  display: flex;
  align-items: center;
  gap: 10px;
}

.titleIcon {
  font-size: 22px;
  color: var(--g-primary, #2563eb);
}

.title {
  font-size: 19px;
  font-weight: 700;
  color: var(--g-text);
}

.subtitle {
  font-size: 12px;
  color: var(--g-text-muted);
}

.layout {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.treeCard {
  width: 270px;
  flex: 0 0 270px;
  max-height: 74vh;
  overflow: auto;
}

.mainCol {
  flex: 1;
  min-width: 0;
}

.card {
  margin-bottom: 16px;
}

.cardHeader {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.filterBar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.hint {
  font-size: 12px;
  color: var(--g-text-muted);
  margin-left: auto;
}

.nodeRow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
}

.nodeLabel {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
}

.nodeSystem {
  font-weight: 600;
}

.nodeDb {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
}

.nodeCount {
  font-size: 12px;
  color: var(--g-text-muted);
}

.mono {
  font-family: 'Consolas', 'Monaco', monospace;
}

.mainText {
  font-weight: 600;
}

.subText {
  font-size: 12px;
  color: var(--g-text-muted);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.drawerBody {
  padding: 0 4px 20px;
}

.blockTitle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  margin: 18px 0 8px;
  color: var(--g-text-secondary);
}

.ddlText :deep(textarea) {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
}

.manualRow {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
}

.verAlert {
  margin-bottom: 12px;
}

.linkBtn {
  text-decoration: none;
}

@media (max-width: 1100px) {
  .layout {
    flex-direction: column;
  }

  .treeCard {
    width: 100%;
    flex: none;
  }
}
</style>
