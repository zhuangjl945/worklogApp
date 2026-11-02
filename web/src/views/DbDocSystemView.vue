<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Document, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue'
import {
  dbDatabaseCreate,
  dbDatabaseDelete,
  dbDatabaseList,
  dbDatabaseToggle,
  dbDatabaseUpdate,
  dbSystemCreate,
  dbSystemDelete,
  dbSystemList,
  dbSystemUpdate
} from '../api/dbDoc'
import { credentialList } from '../api/credential'

// 数据库类型与环境是有限取值，做成下拉而不是自由输入：
// 唯一键里有 env，随手写「生产1」就会多出一条目录。
const DB_TYPES = [
  { value: 'mysql', label: 'MySQL' },
  { value: 'oracle', label: 'Oracle' },
  { value: 'postgresql', label: 'PostgreSQL' },
  { value: 'sqlserver', label: 'SQL Server' },
  { value: 'dm', label: '达梦 DM' },
  { value: 'kingbase', label: '人大金仓' }
]
const ENVS = [
  { value: 'prod', label: '生产' },
  { value: 'uat', label: '预上线' },
  { value: 'test', label: '测试' },
  { value: 'dev', label: '开发' }
]

const loading = ref(false)
const systems = ref([])
const databases = ref([])
const dbLoading = ref(false)
const currentSystem = ref(null)
const systemKeyword = ref('')
const dbKeyword = ref('')
// 关联凭据的下拉：只需要 id + 名称 + 账号，取一批够用的即可
const credentials = ref([])

const credentialOptions = computed(() =>
  credentials.value.map((item) => ({
    value: item.id,
    label: `${item.name}${item.username ? '（' + item.username + '）' : ''}`
  }))
)

/** 系统列表 */
async function loadSystems(keepSelection = true) {
  loading.value = true
  try {
    const resp = await dbSystemList({ keyword: systemKeyword.value || undefined })
    systems.value = resp.data || []
    if (keepSelection && currentSystem.value) {
      const again = systems.value.find((item) => item.id === currentSystem.value.id)
      selectSystem(again || systems.value[0] || null)
      if (!again) ElMessage.info('原系统已不在列表中，已切到第一个')
    } else {
      selectSystem(systems.value[0] || null)
    }
  } catch (e) {
    ElMessage.error(e?.message || '加载业务系统失败')
  } finally {
    loading.value = false
  }
}

/** 选中系统后加载它名下的数据库用户 */
function selectSystem(row) {
  currentSystem.value = row
  if (!row) {
    databases.value = []
    return
  }
  loadDatabases()
}

async function loadDatabases() {
  if (!currentSystem.value) return
  dbLoading.value = true
  try {
    const resp = await dbDatabaseList({
      systemId: currentSystem.value.id,
      keyword: dbKeyword.value || undefined
    })
    databases.value = resp.data || []
  } catch (e) {
    ElMessage.error(e?.message || '加载数据库用户失败')
  } finally {
    dbLoading.value = false
  }
}

/** 载入可关联的凭据（失败不拦主流程，只是下拉为空） */
async function loadCredentials() {
  try {
    // 只取数据库类凭据，够关联下拉用
    const resp = await credentialList({ category: 'database', page: 1, size: 200 })
    credentials.value = resp.data?.records || []
  } catch (e) {
    credentials.value = []
  }
}

// ============================== 系统表单 ==============================
const systemDialog = reactive({ visible: false, saving: false, editing: null, form: {} })

function openSystemForm(row) {
  systemDialog.editing = row || null
  systemDialog.form = row
    ? { ...row }
    : { systemName: '', systemCode: '', dbType: 'mysql', vendor: '', version: '', ownerName: '', remark: '', status: 1, sortNo: 0 }
  systemDialog.visible = true
}

async function saveSystem() {
  if (!systemDialog.form.systemName || !systemDialog.form.systemName.trim()) {
    ElMessage.warning('请填写系统名称')
    return
  }
  systemDialog.saving = true
  try {
    if (systemDialog.editing) {
      await dbSystemUpdate(systemDialog.editing.id, systemDialog.form)
      ElMessage.success('系统已更新')
    } else {
      await dbSystemCreate(systemDialog.form)
      ElMessage.success('系统已新增')
    }
    systemDialog.visible = false
    await loadSystems(true)
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    systemDialog.saving = false
  }
}

async function removeSystem(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除系统「${row.systemName}」吗？名下有数据库用户或表时删不掉。`,
      '删除确认',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await dbSystemDelete(row.id)
    ElMessage.success('已删除')
    await loadSystems(true)
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

// ============================== 数据库用户表单 ==============================
const dbDialog = reactive({ visible: false, saving: false, editing: null, form: {} })

function openDatabaseForm(row) {
  if (!currentSystem.value) {
    ElMessage.warning('请先选择业务系统')
    return
  }
  dbDialog.editing = row || null
  dbDialog.form = row
    ? { ...row }
    : {
        systemId: currentSystem.value.id,
        dbUser: '',
        dbName: '',
        dbType: currentSystem.value.dbType || 'mysql',
        host: '',
        port: null,
        env: 'prod',
        jdbcUrl: '',
        credentialId: null,
        remark: '',
        status: 1
      }
  dbDialog.visible = true
}

async function saveDatabase() {
  if (!dbDialog.form.dbUser || !dbDialog.form.dbUser.trim()) {
    ElMessage.warning('请填写数据库用户/模式名')
    return
  }
  dbDialog.saving = true
  try {
    if (dbDialog.editing) {
      await dbDatabaseUpdate(dbDialog.editing.id, dbDialog.form)
      ElMessage.success('数据库用户已更新')
    } else {
      await dbDatabaseCreate(dbDialog.form)
      ElMessage.success('数据库用户已新增')
    }
    dbDialog.visible = false
    await loadDatabases()
    await loadSystems(true)
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    dbDialog.saving = false
  }
}

async function toggleDatabase(row) {
  try {
    await dbDatabaseToggle(row.id, row.status === 1 ? 0 : 1)
    ElMessage.success(row.status === 1 ? '已停用' : '已启用')
    await loadDatabases()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

async function removeDatabase(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除数据库用户「${row.dbUser}」吗？已登记 ${row.tableCount || 0} 张表时删不掉。`,
      '删除确认',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await dbDatabaseDelete(row.id)
    ElMessage.success('已删除')
    await loadDatabases()
    await loadSystems(true)
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

function envLabel(env) {
  return ENVS.find((item) => item.value === env)?.label || env || '生产'
}

onMounted(() => {
  loadSystems(false)
  loadCredentials()
})
</script>

<template>
  <div class="db-system">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Document /></el-icon>
        <div>
          <div class="title">系统与数据库用户</div>
          <div class="subtitle">先定业务系统，再定表挂在哪个数据库用户下，表结构才有地方归</div>
        </div>
      </div>
      <div class="actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadSystems(true)">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openSystemForm(null)">新增系统</el-button>
      </div>
    </div>

    <!-- 系统 -->
    <el-card class="card" shadow="never">
      <template #header>
        <div class="cardHeader">
          <span>业务系统（{{ systems.length }}）</span>
          <el-input
            v-model="systemKeyword"
            placeholder="按名称 / 编码 / 厂商搜索"
            clearable
            size="small"
            style="width: 240px"
            @keyup.enter="loadSystems(false)"
            @clear="loadSystems(false)"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>
      </template>
      <el-table
        v-loading="loading"
        :data="systems"
        border
        stripe
        highlight-current-row
        row-key="id"
        @current-change="selectSystem"
      >
        <el-table-column label="系统" min-width="200">
          <template #default="{ row }">
            <div class="mainText">{{ row.systemName }}</div>
            <div v-if="row.systemCode" class="subText">{{ row.systemCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">{{ (row.dbType || 'mysql').toUpperCase() }}</template>
        </el-table-column>
        <el-table-column prop="vendor" label="厂商" min-width="120" show-overflow-tooltip />
        <el-table-column prop="version" label="版本" width="90" />
        <el-table-column prop="ownerName" label="负责人" width="90" />
        <el-table-column label="库用户" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ row.databaseCount || 0 }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="表" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small">{{ row.tableCount || 0 }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '在用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :icon="Edit" @click="openSystemForm(row)">编辑</el-button>
            <el-button link type="danger" size="small" :icon="Delete" @click="removeSystem(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有业务系统，先新增一个，再往里导表结构" :image-size="70" />
        </template>
      </el-table>
    </el-card>

    <!-- 数据库用户 -->
    <el-card class="card" shadow="never">
      <template #header>
        <div class="cardHeader">
          <span>
            数据库用户
            <em v-if="currentSystem">— {{ currentSystem.systemName }}</em>
            <em v-else class="faint">— 先在上面选一个系统</em>
          </span>
          <div>
            <el-input
              v-model="dbKeyword"
              placeholder="按用户名 / 库名 / 地址搜索"
              clearable
              size="small"
              style="width: 220px; margin-right: 8px"
              @keyup.enter="loadDatabases"
              @clear="loadDatabases"
            />
            <el-button type="primary" size="small" :icon="Plus" :disabled="!currentSystem" @click="openDatabaseForm(null)">
              新增数据库用户
            </el-button>
          </div>
        </div>
      </template>
      <el-table v-loading="dbLoading" :data="databases" border stripe row-key="id">
        <el-table-column label="数据库用户 / 模式" min-width="170">
          <template #default="{ row }">
            <div class="mono">{{ row.dbUser }}</div>
            <div v-if="row.dbName" class="subText">{{ row.dbName }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ (row.dbType || 'mysql').toUpperCase() }}</template>
        </el-table-column>
        <el-table-column label="环境" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.env === 'prod' ? 'danger' : 'warning'">{{ envLabel(row.env) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="地址" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.host">{{ row.host }}{{ row.port ? ':' + row.port : '' }}</span>
            <span v-else class="faint">—</span>
          </template>
        </el-table-column>
        <el-table-column label="已登记表" width="100" align="center">
          <template #default="{ row }">
            <router-link
              v-if="row.tableCount"
              class="linkText"
              :to="{ name: 'db-doc-catalog', query: { databaseId: row.id } }"
            >{{ row.tableCount }}</router-link>
            <span v-else class="faint">0</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :icon="Edit" @click="openDatabaseForm(row)">编辑</el-button>
            <el-button link size="small" @click="toggleDatabase(row)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
            <el-button link type="danger" size="small" :icon="Delete" @click="removeDatabase(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="currentSystem ? '该系统还没有数据库用户，新增一个或直接去 SQL 分析页导入' : '先选择业务系统'" :image-size="70" />
        </template>
      </el-table>
    </el-card>

    <!-- 系统表单 -->
    <el-dialog v-model="systemDialog.visible" :title="systemDialog.editing ? '编辑业务系统' : '新增业务系统'" width="620px">
      <el-form :model="systemDialog.form" label-width="96px">
        <el-form-item label="系统名称" required>
          <el-input v-model="systemDialog.form.systemName" placeholder="如 门诊住院信息系统" />
        </el-form-item>
        <el-form-item label="系统编码">
          <el-input v-model="systemDialog.form.systemCode" placeholder="如 HIS，可与运维台账对上" />
        </el-form-item>
        <el-form-item label="数据库类型">
          <el-select v-model="systemDialog.form.dbType" style="width: 100%">
            <el-option v-for="item in DB_TYPES" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="厂商">
          <el-input v-model="systemDialog.form.vendor" placeholder="如 卫宁 / 东软 / 自研" />
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="systemDialog.form.version" placeholder="如 V5.2" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="systemDialog.form.ownerName" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="systemDialog.form.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="systemDialog.form.status" :active-value="1" :inactive-value="0" active-text="在用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="systemDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="systemDialog.saving" @click="saveSystem">保存</el-button>
      </template>
    </el-dialog>

    <!-- 数据库用户表单 -->
    <el-dialog v-model="dbDialog.visible" :title="dbDialog.editing ? '编辑数据库用户' : '新增数据库用户'" width="620px">
      <el-form :model="dbDialog.form" label-width="130px">
        <el-form-item label="所属系统">
          <el-input :value="currentSystem ? currentSystem.systemName : ''" disabled />
        </el-form-item>
        <el-form-item label="数据库用户" required>
          <el-input v-model="dbDialog.form.dbUser" placeholder="Oracle 的模式名 / MySQL 的账号名，如 HIS_USER" />
        </el-form-item>
        <el-form-item label="库名 / SID">
          <el-input v-model="dbDialog.form.dbName" placeholder="如 orcl、work_log_system" />
        </el-form-item>
        <el-form-item label="数据库类型">
          <el-select v-model="dbDialog.form.dbType" style="width: 100%">
            <el-option v-for="item in DB_TYPES" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="环境">
          <el-radio-group v-model="dbDialog.form.env">
            <el-radio-button v-for="item in ENVS" :key="item.value" :value="item.value">{{ item.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="dbDialog.form.host" placeholder="如 10.0.0.12">
            <template #append>
              <el-input v-model.number="dbDialog.form.port" placeholder="端口" style="width: 90px" />
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="关联凭据">
          <el-select v-model="dbDialog.form.credentialId" clearable filterable placeholder="口令存凭据保管库，这里只关联 ID" style="width: 100%">
            <el-option v-for="item in credentialOptions" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dbDialog.form.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="dbDialog.form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dbDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dbDialog.saving" @click="saveDatabase">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 与全站列表页一致：表格铺满内容区，多出来的宽度给「系统 / 地址」这些弹性列 */
.db-system {
  width: 100%;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 12px;
  flex-wrap: wrap;
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
  margin-bottom: 18px;
}

.cardHeader {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  font-weight: 600;
}

.cardHeader em {
  font-style: normal;
  font-weight: 400;
  color: var(--g-text-muted);
  margin-left: 6px;
}

.mainText {
  font-weight: 600;
}

.subText {
  font-size: 12px;
  color: var(--g-text-muted);
}

.mono {
  font-family: 'Consolas', 'Monaco', monospace;
  font-weight: 600;
}

.faint {
  color: var(--g-text-muted);
}

.linkText {
  color: var(--g-primary, #2563eb);
  text-decoration: none;
}
</style>
