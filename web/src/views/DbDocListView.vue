<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Document, Files, FolderOpened, MagicStick, Refresh, Search, View } from '@element-plus/icons-vue'
import { dbDatabaseEnabled, dbDocDetail, dbDocDelete, dbDocList, dbRearchive, dbSystemList } from '../api/dbDoc'
import DbTableStructure from '../components/DbTableStructure.vue'
import { changeTagType, changeTypeText, formatDocTime, normalizeDoc, normalizeRow, sourceTypeText } from '../utils/dbDocView'

const router = useRouter()

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const searchKey = ref('')
const filters = reactive({ page: 1, size: 15 })

/** 归属与批次统计是 v2 才有的字段，v1 老记录整列为空时不占宽度 */
const hasOwnership = computed(() => rows.value.some((r) => r.systemName || r.dbUser))
const hasBatchStats = computed(() =>
  rows.value.some((r) => r.databaseId != null && (r.addedCount != null || r.updatedCount != null))
)

/** 加载列表 */
async function load() {
  loading.value = true
  try {
    const resp = await dbDocList({
      page: filters.page,
      size: filters.size,
      keyword: searchKey.value || undefined
    })
    const data = resp.data || {}
    rows.value = (data.records || []).map(normalizeRow)
    total.value = data.total || 0
    // 删完最后一页要往前退一页，否则停在空白页上像坏了
    if (!rows.value.length && filters.page > 1) {
      filters.page -= 1
      await load()
    }
  } catch (e) {
    ElMessage.error(e?.message || '加载文档列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  filters.page = 1
  load()
}

function handleReset() {
  searchKey.value = ''
  filters.page = 1
  load()
}

function handlePageChange(page) {
  filters.page = page
  load()
}

function handleSizeChange(size) {
  filters.size = size
  filters.page = 1
  load()
}

/** 跳到完整详情页（ER 关系图在那一页） */
function openDetailPage(id) {
  router.push({ name: 'db-doc-detail', params: { id } })
}

function openParsePage() {
  router.push({ name: 'db-doc-parse' })
}

/** 删除 */
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      '确定删除文档「' + (row.title || row.id) + '」吗？该批次的解析快照会一并删除，不可恢复。',
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await dbDocDelete(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

/** 详情弹窗：表结构整份藏在 parsed_json 里，取回来先过一层归一化再渲染 */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailDoc = ref(null)
const detailTab = ref('structure')

async function showDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detailDoc.value = null
  detailTab.value = 'structure'
  try {
    const resp = await dbDocDetail(row.id)
    detailDoc.value = normalizeDoc(resp.data)
  } catch (e) {
    ElMessage.error(e?.message || '加载详情失败')
  } finally {
    detailLoading.value = false
  }
}

const detailColumnTotal = computed(() =>
  (detailDoc.value?.tables || []).reduce((sum, t) => sum + (t.columns?.length || 0), 0)
)

/** 字段数变化：新增只有新值、变更是「旧 -> 新」，未变化不必占位 */
function columnChangeText(row) {
  if (row.newColumnCount == null) return '—'
  if (row.oldColumnCount == null || row.oldColumnCount === 0) return String(row.newColumnCount)
  if (row.oldColumnCount === row.newColumnCount) return row.oldColumnCount + '（不变）'
  return row.oldColumnCount + ' → ' + row.newColumnCount
}

/** 批次统计文案：v1 老记录这些计数都是 null，直接显示成「—」而不是 0，免得被当成真的没变更 */
function batchText(row) {
  // 没归类的 v1 老批次没有合并统计，计数列一律留空而不是显示 0
  if (row.databaseId == null) return ''
  if (row.addedCount == null && row.updatedCount == null && row.unchangedCount == null) return ''
  const parts = []
  if (row.addedCount != null) parts.push('新增 ' + row.addedCount)
  if (row.updatedCount != null) parts.push('变更 ' + row.updatedCount)
  if (row.unchangedCount != null) parts.push('未变 ' + row.unchangedCount)
  if (row.missingCount) parts.push('疑似下线 ' + row.missingCount)
  return parts.join(' · ')
}

// ---------- v2：把遗留的「未归类」批次补进三级目录 ----------
// 接口早已具备（/batches/{id}/rearchive），但页面没有入口，
// 老记录就只能一直挂在「未归类」上，这里补一个就地归位的弹窗。
const rearchiveVisible = ref(false)
const rearchiveSubmitting = ref(false)
const rearchiveRow = ref(null)
const systemOptions = ref([])
const databaseOptions = ref([])
const rearchiveForm = reactive({ systemChoice: null, databaseChoice: null, env: 'prod', mode: 'MERGE' })

const ENVS = [
  { value: 'prod', label: '生产' },
  { value: 'uat', label: '预上线' },
  { value: 'test', label: '测试' },
  { value: 'dev', label: '开发' }
]

async function loadSystems() {
  try {
    const resp = await dbSystemList({ status: 1 })
    systemOptions.value = resp.data || []
  } catch (e) {
    ElMessage.error(e?.message || '加载业务系统失败')
  }
}

async function loadDatabases(systemId) {
  databaseOptions.value = []
  if (typeof systemId !== 'number') return
  try {
    const resp = await dbDatabaseEnabled(systemId)
    databaseOptions.value = resp.data || []
  } catch (e) {
    ElMessage.error(e?.message || '加载数据库用户失败')
  }
}

// el-select 开了 allow-create：选中已有项拿到的是数字 ID，手输的新名称是字符串
function handleSystemChoice(value) {
  rearchiveForm.databaseChoice = null
  loadDatabases(value)
}

async function openRearchive(row) {
  rearchiveRow.value = row
  rearchiveForm.systemChoice = null
  rearchiveForm.databaseChoice = null
  rearchiveForm.env = 'prod'
  rearchiveForm.mode = 'MERGE'
  databaseOptions.value = []
  if (!systemOptions.value.length) await loadSystems()
  rearchiveVisible.value = true
}

function buildRearchivePayload() {
  const { systemChoice, databaseChoice, env, mode } = rearchiveForm
  const payload = { env, mode }
  if (typeof systemChoice === 'number') payload.systemId = systemChoice
  else payload.systemName = String(systemChoice ?? '').trim()
  if (typeof databaseChoice === 'number') payload.databaseId = databaseChoice
  else payload.dbUser = String(databaseChoice ?? '').trim()
  return payload
}

async function submitRearchive() {
  const payload = buildRearchivePayload()
  if (!payload.systemId && !payload.systemName) {
    ElMessage.warning('请选择业务系统，或直接输入一个新系统名称')
    return
  }
  if (!payload.databaseId && !payload.dbUser) {
    ElMessage.warning('请选择数据库用户，或直接输入一个新的模式名')
    return
  }
  rearchiveSubmitting.value = true
  try {
    const resp = await dbRearchive(rearchiveRow.value.id, payload)
    const r = resp.data || {}
    const parts = []
    if (r.addedCount) parts.push('新增 ' + r.addedCount)
    if (r.updatedCount) parts.push('变更 ' + r.updatedCount)
    if (r.unchangedCount) parts.push('未变 ' + r.unchangedCount)
    ElMessage.success(
      '已归入「' + (r.systemName || '') + ' / ' + (r.dbUser || '') + '」' + (parts.length ? '：' + parts.join(' · ') : '')
    )
    if (r.warnings?.length) ElMessage.warning(r.warnings[0])
    rearchiveVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '归入目录失败')
  } finally {
    rearchiveSubmitting.value = false
  }
}

/** 从批次跳到目录页，带着归属条件过去，省得再选一遍 */
function openCatalog(row) {
  router.push({
    name: 'db-doc-catalog',
    query: {
      systemId: row.systemId,
      databaseId: row.databaseId
    }
  })
}

onMounted(load)
</script>
<template>
  <div class="db-doc-list">
    <!-- 页头 -->
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Document /></el-icon>
        <div>
          <div class="title">分析记录</div>
          <div class="subtitle">每次导入都是一份批次记录，按导入时间倒序；表结构本身去「表结构目录」看</div>
        </div>
      </div>
      <div class="actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="MagicStick" @click="openParsePage">新建分析</el-button>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filterBar">
      <el-input
        v-model="searchKey"
        placeholder="搜索文档标题 / 数据库用户"
        clearable
        class="searchInput"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
        <template #append>
          <el-button @click="handleSearch">搜索</el-button>
        </template>
      </el-input>
      <el-button @click="handleReset">重置</el-button>
      <span class="filterHint">共 {{ total }} 份文档</span>
    </div>

    <!-- 列表 -->
    <el-card class="card" shadow="never">
      <el-table v-loading="loading" :data="rows" border stripe row-key="id">
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column label="标题" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="openDetailPage(row.id)">
              {{ row.title || '(未命名)' }}
            </el-link>
            <div class="srcLine" :title="row.sourceRef || sourceTypeText(row.sourceType)">
              {{ sourceTypeText(row.sourceType) }}
              <span v-if="row.sourceRef" class="srcRef">{{ row.sourceRef }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="hasOwnership" label="归属" min-width="170">
          <template #default="{ row }">
            <div v-if="row.systemName" class="ownerMain">{{ row.systemName }}</div>
            <el-button v-else link type="warning" size="small" @click="openRearchive(row)">未归类，点这里归入</el-button>
            <div v-if="row.dbUser" class="ownerSub">{{ row.dbUser }}</div>
          </template>
        </el-table-column>
        <el-table-column label="表数量" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ row.tableCount }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="hasBatchStats" label="本批变更" min-width="190">
          <template #default="{ row }">
            <span v-if="batchText(row)" class="batchText">{{ batchText(row) }}</span>
            <span v-else class="faint">—</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDocTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ formatDocTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="270" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :icon="View" @click="showDetail(row)">查看详情</el-button>
            <el-button v-if="row.databaseId" link type="primary" size="small" :icon="Files" @click="openCatalog(row)">看目录</el-button>
            <el-button v-else link type="warning" size="small" :icon="FolderOpened" @click="openRearchive(row)">归入目录</el-button>
            <el-button link type="danger" size="small" :icon="Delete" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :description="searchKey ? '没有匹配的文档，换个关键字试试' : '还没有分析记录，先去 SQL 分析页导入建表语句'" :image-size="80">
            <el-button type="primary" :icon="MagicStick" @click="openParsePage">去 SQL 分析</el-button>
          </el-empty>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="filters.page"
          v-model:page-size="filters.size"
          :page-sizes="[10, 15, 30, 50]"
          :total="total"
          background
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
    <!-- 详情弹窗 -->
    <el-dialog
      v-model="detailVisible"
      :title="detailDoc ? detailDoc.title || '(未命名)' : '文档详情'"
      width="960px"
      top="6vh"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div v-loading="detailLoading" class="dlgBody">
        <template v-if="detailDoc">
          <el-descriptions :column="3" border size="small" class="dlgDesc">
            <el-descriptions-item label="表数量">{{ detailDoc.tables.length }}</el-descriptions-item>
            <el-descriptions-item label="字段合计">{{ detailColumnTotal }}</el-descriptions-item>
            <el-descriptions-item label="所属系统">{{ detailDoc.systemName || '未归类' }}</el-descriptions-item>
            <el-descriptions-item label="数据库用户">{{ detailDoc.dbUser || '—' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDocTime(detailDoc.createTime) }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ formatDocTime(detailDoc.updateTime) }}</el-descriptions-item>
            <el-descriptions-item v-if="batchText(detailDoc)" label="本批变更" :span="3">
              {{ batchText(detailDoc) }}
            </el-descriptions-item>
          </el-descriptions>

          <!-- 解析告警：被跳过的语句必须让人看见，否则表数对不上只会当成 bug -->
          <el-alert v-if="detailDoc.warnings.length" type="warning" :closable="false" show-icon class="dlgWarn">
            <template #title>解析告警（{{ detailDoc.warnings.length }} 条）</template>
            <ul class="warnList">
              <li v-for="(w, i) in detailDoc.warnings" :key="i">{{ w }}</li>
            </ul>
          </el-alert>

          <el-tabs v-model="detailTab" class="dlgTabs">
            <el-tab-pane label="表结构" name="structure">
              <DbTableStructure :tables="detailDoc.tables" :expand-count="3" />
            </el-tab-pane>
            <!-- v2 才有本批处理结论：一次导入到底新增了几张、改了几张，得能一眼看见 -->
            <el-tab-pane v-if="detailDoc.changes.length" :label="`本批结论（${detailDoc.changes.length}）`" name="changes">
              <el-table :data="detailDoc.changes" size="small" border stripe max-height="420">
                <el-table-column label="表名" min-width="170" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span class="mono">{{ row.tableName }}</span>
                    <span v-if="row.tableComment" class="changeComment">{{ row.tableComment }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="库用户" width="120" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.dbUser || '—' }}</template>
                </el-table-column>
                <el-table-column label="结论" width="110" align="center">
                  <template #default="{ row }">
                    <el-tag :type="changeTagType(row.changeType)" size="small">{{ changeTypeText(row.changeType) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="字段数" width="110" align="center">
                  <template #default="{ row }">{{ columnChangeText(row) }}</template>
                </el-table-column>
                <el-table-column label="差异摘要" min-width="220" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.diffSummary || '—' }}</template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="原始 SQL" name="sql">
              <pre v-if="detailDoc.sourceSql" class="sqlPre">{{ detailDoc.sourceSql }}</pre>
              <el-empty
                v-else
                :description="detailDoc.sourceRef ? `这批结构来自 ${detailDoc.sourceRef}，没有原始 SQL 可看` : `该记录没有保存原始 SQL`"
                :image-size="70"
              />
            </el-tab-pane>
          </el-tabs>
        </template>
        <el-empty v-else-if="!detailLoading" description="详情加载失败，可关闭后重试" :image-size="80" />
      </div>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!detailDoc" @click="openDetailPage(detailDoc.id)">
          查看完整详情（ER 图）
        </el-button>
      </template>
    </el-dialog>

    <!-- 归入表结构目录：拿这条批次存过的原始 SQL 重新走一遍导入合并 -->
    <el-dialog v-model="rearchiveVisible" title="归入表结构目录" width="560px">
      <el-alert
        class="dlgDesc"
        type="info"
        :closable="false"
        show-icon
        title="会用这条记录保存的原始建表语句重新解析一次，按「业务系统 → 数据库用户 → 表」写进目录"
        description="导入后会新增一条已归类的分析记录，原来这条未归类的保留不动，方便对账。"
      />
      <el-form label-width="104px">
        <el-form-item label="业务系统" required>
          <el-select
            v-model="rearchiveForm.systemChoice"
            filterable
            allow-create
            default-first-option
            placeholder="选已有系统，或直接输入新系统名称"
            style="width: 100%"
            @change="handleSystemChoice"
          >
            <el-option v-for="s in systemOptions" :key="s.id" :label="s.systemName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据库用户" required>
          <el-select
            v-model="rearchiveForm.databaseChoice"
            filterable
            allow-create
            default-first-option
            placeholder="选该系统下已有的用户，或直接输入模式名（如 HIS）"
            style="width: 100%"
          >
            <el-option v-for="d in databaseOptions" :key="d.id" :label="d.dbUser + (d.env ? '（' + d.env + '）' : '')" :value="d.id" />
          </el-select>
          <div class="ownerSub">脚本里写成 用户.表名 的表会各自归位，同系统下没有的用户会自动建档。</div>
        </el-form-item>
        <el-form-item label="环境">
          <el-select v-model="rearchiveForm.env" style="width: 160px">
            <el-option v-for="e in ENVS" :key="e.value" :label="e.label" :value="e.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="合并方式">
          <el-radio-group v-model="rearchiveForm.mode">
            <el-radio value="MERGE">增量合并</el-radio>
            <el-radio value="REPLACE">整库对账</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rearchiveVisible = false">取消</el-button>
        <el-button type="primary" :loading="rearchiveSubmitting" @click="submitRearchive">确定归入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 去掉 1280px 上限：批次表格的标题 / 归属 / 本批变更几列要横向空间才拉得开 */
.db-doc-list {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}

.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.titleWrap {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.titleIcon {
  font-size: 22px;
  color: var(--g-text);
  margin-top: 2px;
}

.title {
  font-size: 18px;
  font-weight: 600;
  color: var(--g-text);
}

.subtitle {
  font-size: 12px;
  color: var(--g-text-muted);
  margin-top: 2px;
}

.actions {
  display: flex;
  gap: 8px;
}

.filterBar {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 搜索框随屏幅微涨，但封顶在可读宽度，不让输入框横贯整屏 */
.searchInput {
  flex: 0 1 auto;
  width: 100%;
  max-width: 420px;
}

.filterHint {
  font-size: 12px;
  color: var(--g-text-muted);
  margin-left: 4px;
}

.card {
  border-radius: 8px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.ownerMain {
  color: var(--g-text);
}

.srcLine {
  font-size: 12px;
  color: var(--g-text-muted);
  margin-top: 2px;
}

.srcRef {
  margin-left: 6px;
  color: var(--g-text-faint);
}

.ownerSub {
  font-size: 12px;
  color: var(--g-text-muted);
  font-family: var(--g-font-mono, 'Consolas', monospace);
}

.batchText {
  font-size: 12px;
  color: var(--g-text-secondary);
}

.mono {
  font-family: var(--g-font-mono, 'Consolas', monospace);
}

.changeComment {
  margin-left: 8px;
  font-size: 12px;
  color: var(--g-text-muted);
}

.faint {
  color: var(--g-text-faint);
}

.dlgBody {
  min-height: 220px;
}

.dlgDesc {
  margin-bottom: 12px;
}

.dlgWarn {
  margin-bottom: 12px;
}

.warnList {
  margin: 4px 0 0;
  padding-left: 18px;
  line-height: 1.7;
}

.dlgTabs :deep(.el-tabs__content) {
  max-height: 52vh;
  overflow-y: auto;
}

.sqlPre {
  margin: 0;
  padding: 12px 14px;
  background: var(--g-bg-muted);
  border: 1px solid var(--g-border);
  border-radius: 6px;
  font-family: var(--g-font-mono, 'Consolas', monospace);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 46vh;
  overflow: auto;
}
</style>
