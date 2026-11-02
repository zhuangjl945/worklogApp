<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Download, Refresh, Search, Warning } from '@element-plus/icons-vue'
import { dbDatabaseCreate, dbDatabaseEnabled, dbDocParse, dbImport, dbPreviewDiff, dbSystemCreate, dbSystemList } from '../api/dbDoc'
import DbDictExcelImport from '../components/DbDictExcelImport.vue'
import DbTableStructure from '../components/DbTableStructure.vue'
import { changeTagType, changeTypeText, normalizeTable } from '../utils/dbDocView'

const DB_TYPES = ['mysql', 'oracle', 'postgresql', 'sqlserver', 'dm', 'kingbase']
const ENVS = [
  { value: 'prod', label: '生产' },
  { value: 'uat', label: '预上线' },
  { value: 'test', label: '测试' },
  { value: 'dev', label: '开发' }
]

// ---------- 导入目标：先说清「哪个系统、哪个数据库用户」 ----------
const systems = ref([])
const databases = ref([])
const target = reactive({
  systemId: null,
  databaseId: null,
  dbType: 'mysql',
  env: 'prod',
  title: '',
  mode: 'MERGE',
  autoCreateDatabase: true
})

async function loadSystems() {
  try {
    const resp = await dbSystemList({ status: 1 })
    systems.value = resp.data || []
    if (!target.systemId && systems.value.length) {
      target.systemId = systems.value[0].id
      await loadDatabases()
    }
  } catch (e) {
    ElMessage.error(e?.message || '加载业务系统失败')
  }
}

async function loadDatabases() {
  if (!target.systemId) {
    databases.value = []
    return
  }
  try {
    const resp = await dbDatabaseEnabled(target.systemId)
    databases.value = resp.data || []
    if (target.databaseId && !databases.value.some((item) => item.id === target.databaseId)) {
      target.databaseId = null
    }
  } catch (e) {
    ElMessage.error(e?.message || '加载数据库用户失败')
  }
}

async function onSystemChange() {
  target.databaseId = null
  await loadDatabases()
}

const currentSystem = computed(() => systems.value.find((item) => item.id === target.systemId) || null)
const currentDatabase = computed(() => databases.value.find((item) => item.id === target.databaseId) || null)

// ---------- 就地新建系统 / 数据库用户：不想为导一份脚本先跳去别的页面 ----------
const systemDialog = reactive({ visible: false, saving: false, form: {} })
const databaseDialog = reactive({ visible: false, saving: false, form: {} })

function openSystemDialog() {
  systemDialog.form = { systemName: '', systemCode: '', dbType: target.dbType || 'mysql', vendor: '', remark: '', status: 1 }
  systemDialog.visible = true
}

async function createSystem() {
  if (!systemDialog.form.systemName.trim()) {
    ElMessage.warning('请填写系统名称')
    return
  }
  systemDialog.saving = true
  try {
    const resp = await dbSystemCreate(systemDialog.form)
    ElMessage.success('系统已创建')
    systemDialog.visible = false
    await loadSystems()
    target.systemId = resp.data?.id || target.systemId
    await onSystemChange()
  } catch (e) {
    ElMessage.error(e?.message || '创建系统失败')
  } finally {
    systemDialog.saving = false
  }
}

function openDatabaseDialog() {
  if (!target.systemId) {
    ElMessage.warning('先选一个业务系统')
    return
  }
  databaseDialog.form = {
    systemId: target.systemId,
    dbUser: '',
    dbName: '',
    dbType: currentSystem.value?.dbType || target.dbType || 'mysql',
    host: '',
    port: null,
    env: target.env || 'prod',
    remark: '',
    status: 1
  }
  databaseDialog.visible = true
}

async function createDatabase() {
  if (!databaseDialog.form.dbUser.trim()) {
    ElMessage.warning('请填写数据库用户/模式名')
    return
  }
  databaseDialog.saving = true
  try {
    const resp = await dbDatabaseCreate(databaseDialog.form)
    ElMessage.success('数据库用户已创建')
    databaseDialog.visible = false
    await loadDatabases()
    target.databaseId = resp.data?.id || target.databaseId
  } catch (e) {
    ElMessage.error(e?.message || '创建数据库用户失败')
  } finally {
    databaseDialog.saving = false
  }
}

// ---------- 表结构来源与预览 ----------
/**
 * sql  = 贴建表脚本，交后端解析；
 * dict = 上传厂商给的表结构说明 Excel，浏览器里就解析完。
 * 两种来源最终都走同一个 /import，合并、差异摘要、版本留痕完全一致。
 */
const sourceKind = ref('sql')
const dict = ref({ tables: [], sourceRef: '', stats: null, warnings: [] })
const sql = ref('')
const parsing = ref(false)
const diffing = ref(false)
const importing = ref(false)
const tables = ref([])
const warnings = ref([])
const scriptDbUsers = ref([])
const diff = ref(null)

const normalizedTables = computed(() => tables.value.map(normalizeTable))

const isDict = computed(() => sourceKind.value === 'dict')
const dictTables = computed(() => (isDict.value ? dict.value.tables || [] : []))

/** 两种来源各自检查自己那份输入准备好了没有 */
function requireInput() {
  if (isDict.value) {
    if (!dictTables.value.length) {
      ElMessage.warning('先选择 Excel 文件，解析出表结构再往下走')
      return false
    }
    return true
  }
  if (!sql.value.trim()) {
    ElMessage.warning('请先粘贴建表语句')
    return false
  }
  return true
}

/** Excel 解析完直接把结果铺到下方「解析出的表结构」，不用再点一次按钮 */
function syncFromDict() {
  tables.value = dict.value.tables || []
  scriptDbUsers.value = dict.value.stats?.dbUsers || []
  warnings.value = []
  diff.value = null
}

// 换来源时把上一轮结果清掉，免得看着 Excel 的条数以为是脚本的比对结果
function onSourceKindChange() {
  diff.value = null
  tables.value = []
  warnings.value = []
  scriptDbUsers.value = []
  if (isDict.value) syncFromDict()
}

watch(
  () => [dict.value.tables, dict.value.stats],
  () => {
    if (isDict.value) syncFromDict()
  }
)

/** 只看解析结果，不比对目录 */
async function handleParse() {
  if (!requireInput()) return
  if (isDict.value) {
    syncFromDict()
    ElMessage.info('Excel 已解析出 ' + tables.value.length + ' 张表，可直接预览差异或导入')
    return
  }
  parsing.value = true
  diff.value = null
  try {
    const resp = await dbDocParse(sql.value)
    tables.value = resp.data?.tables || []
    warnings.value = resp.data?.warnings || []
    scriptDbUsers.value = resp.data?.dbUsers || []
    if (!tables.value.length) {
      ElMessage.info('没有解析出表结构，检查一下脚本里有没有 CREATE TABLE')
    } else {
      const userTip = scriptDbUsers.value.length ? `，涉及 ${scriptDbUsers.value.length} 个库用户` : ''
      ElMessage.success(`解析出 ${tables.value.length} 张表${userTip}`)
    }
  } catch (e) {
    ElMessage.error(e?.message || '解析失败')
  } finally {
    parsing.value = false
  }
}

/** 与目录比对，先看清会新增几张、会改哪几张 */
async function handlePreviewDiff() {
  if (!requireInput()) return
  if (!target.databaseId) {
    ElMessage.warning('先选择数据库用户，才能比对这张表在目录里有没有、结构变了没')
    return
  }
  diffing.value = true
  try {
    const resp = await dbPreviewDiff(
      isDict.value ? '' : sql.value,
      target.databaseId,
      dictTables.value.length ? dictTables.value : undefined
    )
    diff.value = resp.data || null
    tables.value = resp.data?.tables || tables.value
    warnings.value = resp.data?.warnings || warnings.value
    if (!diff.value.changes.length) {
      ElMessage.info('没有可比对的表')
    } else {
      ElMessage.success(`将新增 ${diff.value.addedCount} 张、变更 ${diff.value.changedCount} 张、未变 ${diff.value.unchangedCount} 张`)
    }
  } catch (e) {
    ElMessage.error(e?.message || '差异预览失败')
  } finally {
    diffing.value = false
  }
}

/** 正式导入：解析 + 增量合并进目录 */
async function handleImport() {
  if (!requireInput()) return
  if (!target.systemId) {
    ElMessage.warning('请选择业务系统')
    return
  }
  if (!target.databaseId) {
    ElMessage.warning('请选择数据库用户（表要挂在谁名下）')
    return
  }
  importing.value = true
  try {
    const resp = await dbImport({
      systemId: target.systemId,
      databaseId: target.databaseId,
      title: target.title || undefined,
      sql: isDict.value ? undefined : sql.value,
      // Excel 来源：整份表清单直接交给后端，不再解析脚本；来源和文件名一并留痕
      tables: dictTables.value.length ? dictTables.value : undefined,
      sourceType: isDict.value ? 'EXCEL' : 'DDL',
      sourceRef: isDict.value ? dict.value.sourceRef : undefined,
      mode: target.mode,
      autoCreateDatabase: target.autoCreateDatabase
    })
    const result = resp.data || {}
    ElMessage.success(
      `导入完成：新增 ${result.addedCount || 0} 张，变更 ${result.updatedCount || 0} 张，未变 ${result.unchangedCount || 0} 张` +
        (result.missingCount ? `，本次脚本未出现 ${result.missingCount} 张` : '')
    )
    // 导入结果本身就带每张表的结论，顺手展示出来，省得再点一次
    diff.value = {
      changes: result.changes || [],
      addedCount: result.addedCount || 0,
      changedCount: result.updatedCount || 0,
      unchangedCount: result.unchangedCount || 0,
      missingCount: result.missingCount || 0,
      againstDbUser: result.dbUser
    }
    warnings.value = result.warnings || warnings.value
    await loadDatabases()
  } catch (e) {
    ElMessage.error(e?.message || '导入失败')
  } finally {
    importing.value = false
  }
}

function handleClear() {
  sql.value = ''
  target.title = ''
  dict.value = { tables: [], sourceRef: '', stats: null, warnings: [] }
  tables.value = []
  warnings.value = []
  scriptDbUsers.value = []
  diff.value = null
}

onMounted(loadSystems)
</script>

<template>
  <div class="db-doc-parse">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Search /></el-icon>
        <div>
          <div class="title">SQL 分析导入</div>
          <div class="subtitle">先定系统与数据库用户，再把建表脚本或 Excel 数据字典增量并进目录：已有的表只改结构，不会重复堆快照</div>
        </div>
      </div>
      <div class="actions">
        <router-link :to="{ name: 'db-doc-catalog' }" class="linkBtn">
          <el-button :icon="Refresh">表目录</el-button>
        </router-link>
      </div>
    </div>

    <!-- 导入目标 -->
    <el-card class="card" shadow="never">
      <template #header><span class="cardTitle">导到哪里</span></template>
      <el-form :inline="true" label-width="86px" class="targetForm">
        <el-form-item label="业务系统">
          <el-select v-model="target.systemId" filterable placeholder="选择系统" @change="onSystemChange">
            <el-option v-for="item in systems" :key="item.id" :value="item.id" :label="item.systemName" />
          </el-select>
          <el-button link type="primary" class="createLink" @click="openSystemDialog">新建</el-button>
        </el-form-item>
        <el-form-item label="数据库用户">
          <el-select v-model="target.databaseId" filterable placeholder="选择库用户" :disabled="!target.systemId">
            <el-option
              v-for="item in databases"
              :key="item.id"
              :value="item.id"
              :label="item.dbUser + (item.dbName ? ' @ ' + item.dbName : '') + '（' + item.env + '）'"
            />
          </el-select>
          <el-button link type="primary" class="createLink" @click="openDatabaseDialog">新建</el-button>
        </el-form-item>
        <el-form-item label="环境">
          <el-select v-model="target.env">
            <el-option v-for="item in ENVS" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="合并方式">
          <el-radio-group v-model="target.mode">
            <el-radio-button value="MERGE">增量合并</el-radio-button>
            <el-radio-button value="REPLACE">整库对账</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="批次标题">
          <el-input v-model="target.title" placeholder="留空自动生成：系统·用户·日期" clearable />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon class="modeHint">
        <template #title>
          <span v-if="target.mode === 'MERGE'">增量合并：只新增和更新本次来源里出现的表，目录里其它表原样保留（日常补表用这个）。</span>
          <span v-else>整库对账：本次来源里没有、但目录里挂在这个用户下的表会被标成「疑似下线」，不会物理删除，确认后可再手动清。</span>
        </template>
      </el-alert>
      <el-checkbox v-model="target.autoCreateDatabase" class="autoChk">
        脚本里出现别的库用户前缀（如 LIS.SAMPLE）时，自动在同一系统下为它建档
      </el-checkbox>
    </el-card>

    <!-- 表结构来源：贴建表脚本，或上传厂商给的 Excel 数据字典 -->
    <el-card class="card" shadow="never">
      <template #header>
        <div class="cardHeader">
          <span class="cardTitle">{{ sourceKind === 'dict' ? 'Excel 数据字典' : '建表语句' }}</span>
          <el-radio-group v-model="sourceKind" size="small" @change="onSourceKindChange">
            <el-radio-button value="sql">贴建表脚本</el-radio-button>
            <el-radio-button value="dict">Excel 数据字典</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <el-input
        v-if="sourceKind === 'sql'"
        v-model="sql"
        type="textarea"
        :rows="12"
        placeholder="支持 MySQL / Oracle / PostgreSQL / SQL Server / 达梦的建表脚本，多条语句以分号分隔；带「用户.表名」限定名的会按用户分别归位"
        class="sqlTextarea"
      />
      <DbDictExcelImport v-else v-model="dict" />

      <div class="btnRow">
        <el-button v-if="sourceKind === 'sql'" :loading="parsing" :icon="Search" @click="handleParse">只解析</el-button>
        <el-button v-else :icon="Search" @click="handleParse">看解析结果</el-button>
        <el-button :loading="diffing" :icon="Warning" :disabled="!target.databaseId" @click="handlePreviewDiff">预览差异</el-button>
        <el-button type="primary" :loading="importing" :icon="Download" @click="handleImport">导入目录</el-button>
        <el-button link @click="handleClear">清空</el-button>
        <span v-if="currentDatabase" class="targetTip">
          当前目标：{{ currentSystem?.systemName }} · <span class="mono">{{ currentDatabase.dbUser }}</span>
        </span>
      </div>
    </el-card>

    <!-- 解析告警 -->
    <el-alert v-if="warnings.length" type="warning" :closable="false" show-icon class="card">
      <template #title>{{ isDict ? `Excel 解析有 ${warnings.length} 条提示，导入前请核对` : `共跳过 ${warnings.length} 条语句，其余已正常解析` }}</template>
      <ul class="warnList">
        <li v-for="(item, index) in warnings" :key="index">{{ item }}</li>
      </ul>
    </el-alert>

    <!-- 差异预览 -->
    <el-card v-if="diff && diff.changes && diff.changes.length" class="card" shadow="never">
      <template #header>
        <div class="cardHeader">
          <span class="cardTitle">与目录的比对结果</span>
          <div class="stats">
            <el-tag type="success" size="small">新增 {{ diff.addedCount }}</el-tag>
            <el-tag type="warning" size="small">变更 {{ diff.changedCount }}</el-tag>
            <el-tag type="info" size="small">未变 {{ diff.unchangedCount }}</el-tag>
            <el-tag v-if="diff.missingCount" type="danger" size="small">本次未出现 {{ diff.missingCount }}</el-tag>
          </div>
        </div>
      </template>
      <el-table :data="diff.changes" size="small" border stripe max-height="420">
        <el-table-column label="处理" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="changeTagType(row.changeType)" size="small">{{ changeTypeText(row.changeType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="表名" min-width="170">
          <template #default="{ row }">
            <span class="mono">{{ row.tableName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="库用户" width="130">
          <template #default="{ row }"><span class="mono">{{ row.dbUser || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="字段数" width="110" align="center">
          <template #default="{ row }">{{ row.oldColumnCount || 0 }} → {{ row.newColumnCount || 0 }}</template>
        </el-table-column>
        <el-table-column prop="diffSummary" label="差异" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row.diffSummary || '—' }}</template>
        </el-table-column>
        <el-table-column prop="tableComment" label="表注释" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.tableComment || '—' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 脚本涉及的库用户 -->
    <el-card v-if="scriptDbUsers.length" class="card" shadow="never">
      <template #header><span class="cardTitle">{{ isDict ? 'Excel 里出现的库用户' : '脚本里出现的库用户' }}</span></template>
      <div class="userTags">
        <el-tag v-for="user in scriptDbUsers" :key="user" class="mono" type="info">{{ user }}</el-tag>
      </div>
      <div class="faint small">导入时这些前缀会各自归位；目录里没有的会按上面的开关自动建档。</div>
    </el-card>

    <!-- 解析出的结构 -->
    <el-card v-if="normalizedTables.length" class="card" shadow="never">
      <template #header>
        <div class="cardHeader">
          <span class="cardTitle">解析出的表结构（{{ normalizedTables.length }} 张）</span>
          <el-tag v-if="diff" type="success" size="small"><el-icon><Check /></el-icon> 已比对</el-tag>
        </div>
      </template>
      <DbTableStructure :tables="normalizedTables" :expand-count="2" />
    </el-card>

    <!-- 新建系统 -->
    <el-dialog v-model="systemDialog.visible" title="新建业务系统" width="520px">
      <el-form :model="systemDialog.form" label-width="96px">
        <el-form-item label="系统名称" required>
          <el-input v-model="systemDialog.form.systemName" placeholder="如 检验系统 LIS" />
        </el-form-item>
        <el-form-item label="系统编码">
          <el-input v-model="systemDialog.form.systemCode" placeholder="如 LIS" />
        </el-form-item>
        <el-form-item label="数据库类型">
          <el-select v-model="systemDialog.form.dbType" style="width: 100%">
            <el-option v-for="item in DB_TYPES" :key="item" :value="item" :label="item.toUpperCase()" />
          </el-select>
        </el-form-item>
        <el-form-item label="厂商">
          <el-input v-model="systemDialog.form.vendor" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="systemDialog.form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="systemDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="systemDialog.saving" @click="createSystem">创建</el-button>
      </template>
    </el-dialog>

    <!-- 新建数据库用户 -->
    <el-dialog v-model="databaseDialog.visible" title="新建数据库用户" width="560px">
      <el-form :model="databaseDialog.form" label-width="110px">
        <el-form-item label="数据库用户" required>
          <el-input v-model="databaseDialog.form.dbUser" placeholder="模式名 / 账号名，如 HIS_USER" />
        </el-form-item>
        <el-form-item label="库名 / SID">
          <el-input v-model="databaseDialog.form.dbName" />
        </el-form-item>
        <el-form-item label="数据库类型">
          <el-select v-model="databaseDialog.form.dbType" style="width: 100%">
            <el-option v-for="item in DB_TYPES" :key="item" :value="item" :label="item.toUpperCase()" />
          </el-select>
        </el-form-item>
        <el-form-item label="环境">
          <el-radio-group v-model="databaseDialog.form.env">
            <el-radio-button v-for="item in ENVS" :key="item.value" :value="item.value">{{ item.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="databaseDialog.form.host" placeholder="如 10.0.0.12">
            <template #append>
              <el-input v-model.number="databaseDialog.form.port" placeholder="端口" style="width: 90px" />
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="databaseDialog.form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="databaseDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="databaseDialog.saving" @click="createDatabase">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 页面宽度跟随内容区，不再锁 1200px：宽屏下 SQL 编辑区与比对表才用得起来 */
.db-doc-parse {
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

.card {
  margin-bottom: 16px;
}

.cardTitle {
  font-weight: 600;
}

.cardHeader {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.stats {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

/* 导入目标：5 列等宽栅格。列宽一致、控件各自撑满所在列，标签与控件的左右缘才真正对得齐 */
.targetForm {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 0 24px;
}

/* 栅格下不需要行内表单的右间距，列间距交给 gap；min-width:0 让列可被压缩 */
.targetForm :deep(.el-form-item) {
  margin-right: 0;
  min-width: 0;
}

/* 关键：内容区禁止换行。原先 select 以 100% 宽参与 flex，加上「新建」就溢出折行，把按钮甩到第二行 */
.targetForm :deep(.el-form-item__content) {
  flex-wrap: nowrap;
  gap: 4px;
}

/* flex-basis 取 0 而非 auto：先给「新建」留位，剩余宽度才归下拉和输入框 */
.targetForm :deep(.el-select),
.targetForm :deep(.el-input) {
  flex: 1 1 0;
  min-width: 0;
}

.createLink {
  flex: none;
  padding: 0 2px;
  white-space: nowrap;
}

/* 视口收窄时逐级降列数，控件始终撑满所在列，不留半空列 */
@media (max-width: 1500px) {
  .targetForm {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 1080px) {
  .targetForm {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 720px) {
  .targetForm {
    grid-template-columns: minmax(0, 1fr);
  }
}

.modeHint {
  margin-top: 4px;
}

.autoChk {
  margin-top: 10px;
}

.sqlTextarea :deep(textarea) {
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
}

.btnRow {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
}

.targetTip {
  margin-left: auto;
  font-size: 12px;
  color: var(--g-text-muted);
}

.warnList {
  margin: 6px 0 0;
  padding-left: 20px;
  line-height: 1.8;
}

.userTags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.mono {
  font-family: 'Consolas', 'Monaco', monospace;
}

.faint {
  color: var(--g-text-muted);
}

.small {
  font-size: 12px;
  margin-top: 8px;
}

.linkBtn {
  text-decoration: none;
}
</style>
