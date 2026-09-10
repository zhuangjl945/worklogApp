<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Avatar, RefreshRight, WarningFilled } from '@element-plus/icons-vue'
import { userRolePage, userRoleSummary, userUpdateRole, userUpdateRolesBatch } from '../api/user'
import { deptTree } from '../api/dept'
import { currentProfile, ROLE } from '../utils/auth'

/* ============================================================
   人员角色设置
   ------------------------------------------------------------
   这一页只管一件事：给具体的人挂上角色。
   「某个功能要什么角色」不在这里改，那是「权限设置」页的事。
   两条保护（不能改自己、必须留一个启用的系统管理员）由后端 UserRoleGuard 判定，
   界面同步禁用控件，避免点了才吃 40003/40004。
   ============================================================ */

/** 与后端 Role 枚举一一对应；cap 写清「这个角色能干什么」，授角色的人不用翻文档 */
const ROLES = [
  { value: ROLE.ADMIN, label: '系统管理员', tag: 'danger', cap: '跨科室查看全部数据，员工、科室、参数配置与权限设置' },
  { value: ROLE.DEPT_ADMIN, label: '科室管理员', tag: 'warning', cap: '编辑本科室同事的记录，维护工作分类与登记渠道' },
  { value: ROLE.USER, label: '普通员工', tag: 'info', cap: '只管自己的记录，本科室看板只读' }
]
const roleMeta = (v) => ROLES.find((r) => r.value === v) || ROLES[2]

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const summary = ref({})
const deptOptions = ref([])
const selection = ref([])
const batchRole = ref(ROLE.USER)
/** 每行进入页面时的角色：改失败或被取消时用它把下拉倒回去 */
const baseline = new Map()

const filters = reactive({
  page: 1,
  size: 20,
  role: null,
  deptId: null,
  keyword: '',
  status: null
})

const flatDeptOptions = computed(() => {
  const out = []
  const walk = (nodes, depth = 0) => {
    for (const n of nodes || []) {
      out.push({ id: n.id, label: `${'—'.repeat(depth)}${depth > 0 ? ' ' : ''}${n.deptName} (${n.deptCode})` })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(deptOptions.value, 0)
  return out
})

const meId = computed(() => currentProfile()?.id ?? null)
const isSelf = (row) => !!meId.value && row.id === meId.value

/** 选中人数（自己那行不可选，所以这里就是「真正会被改的人数」） */
const pickedCount = computed(() => selection.value.length)

/** 科室管理员却没绑科室 = 这个角色对他基本无效，单独提醒，别让人以为授成功了 */
const orphanDeptAdmins = computed(() =>
  rows.value.filter((r) => r.role === ROLE.DEPT_ADMIN && !r.deptId)
)

function fmtTime(v) {
  if (!v) return '-'
  const s = String(v).replace('T', ' ')
  return s.length >= 16 ? s.slice(0, 16) : s
}

async function load() {
  loading.value = true
  try {
    const resp = await userRolePage({ ...filters })
    rows.value = resp.data?.records || []
    total.value = resp.data?.total || 0
    baseline.clear()
    for (const r of rows.value) baseline.set(r.id, r.role)
  } catch (e) {
    ElMessage.error(e?.message || '加载人员列表失败')
  } finally {
    loading.value = false
  }
}

async function loadSummary() {
  try {
    const resp = await userRoleSummary()
    summary.value = resp.data || {}
  } catch {
    // 概览只是辅助信息，读不到不影响改角色，保持空值即可
  }
}

async function loadDepts() {
  try {
    const resp = await deptTree()
    deptOptions.value = resp.data || []
  } catch {
    // ignore：科室树读不到时下拉为空，不影响按角色筛人
  }
}

function reload() {
  load()
  loadSummary()
}

/** 点角色卡即按该角色筛选；再点一次取消筛选 */
function toggleRoleFilter(value) {
  filters.role = filters.role === value ? null : value
  filters.page = 1
  load()
}

function onReset() {
  filters.role = null
  filters.deptId = null
  filters.keyword = ''
  filters.status = null
  filters.page = 1
  load()
}

function onPageChange(p) {
  filters.page = p
  load()
}

function onSizeChange(s) {
  filters.size = s
  filters.page = 1
  load()
}

/**
 * 行内改角色：先确认，再落库，失败或取消都把下拉倒回原值。
 *
 * <p>倒回是必须的：v-model 已经把新值写进 row.role 了，不还原会出现
 * 「界面显示系统管理员、库里还是普通员工」这种最难查的假象。
 */
async function onRoleChange(row) {
  const next = row.role
  const prev = baseline.get(row.id) || ROLE.USER
  if (next === prev) return
  const meta = roleMeta(next)
  try {
    await ElMessageBox.confirm(
      `把「${row.realName || row.username}」的角色改为「${meta.label}」？${meta.cap}。对方需重新登录后才生效。`,
      '确认调整角色',
      { type: 'warning', confirmButtonText: '确认调整', cancelButtonText: '取消' }
    )
  } catch {
    row.role = prev
    return
  }
  try {
    const resp = await userUpdateRole(row.id, next)
    const applied = resp.data?.role || next
    row.role = applied
    row.roleLabel = resp.data?.roleLabel || roleMeta(applied).label
    // 「最近变更」用后端回读的时间，不然刚改完那一行还显示旧时间，看着像没保存
    if (resp.data?.updateTime) row.updateTime = resp.data.updateTime
    baseline.set(row.id, applied)
    ElMessage.success(`「${row.realName || row.username}」已设为${roleMeta(applied).label}`)
    await loadSummary()
  } catch (e) {
    row.role = prev
    if (e?.code !== 403) ElMessage.error(e?.message || '调整失败')
  }
}

/** 批量改角色：后端逐条走同样的两条保护，被跳过的人原样列出来 */
async function onBatchApply() {
  if (!pickedCount.value) return
  const meta = roleMeta(batchRole.value)
  try {
    await ElMessageBox.confirm(
      `把选中的 ${pickedCount.value} 人都设为「${meta.label}」？对方都要重新登录后才生效。`,
      '确认批量调整',
      { type: 'warning', confirmButtonText: '确认调整', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  const items = selection.value.map((r) => ({ id: r.id, role: batchRole.value }))
  try {
    const resp = await userUpdateRolesBatch(items)
    const updated = resp.data?.updated || 0
    const skipped = resp.data?.skipped || []
    if (updated) ElMessage.success(`已调整 ${updated} 人`)
    if (skipped.length) {
      ElMessage.warning(`${skipped.length} 人未调整：${skipped.map((s) => `${s.name}（${s.reason}）`).join('；')}`)
    }
    if (!updated && !skipped.length) ElMessage.info('所选人员的角色已经是该值，无需调整')
    selection.value = []
    await reload()
  } catch (e) {
    if (e?.code !== 403) ElMessage.error(e?.message || '批量调整失败')
  }
}

function onSelectionChange(list) {
  selection.value = list || []
}

onMounted(() => {
  load()
  loadSummary()
  loadDepts()
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Avatar /></el-icon>
        <div>
          <div class="title">人员角色设置</div>
          <div class="sub">给具体的人授予或收回角色；功能门槛本身在「权限设置」里调</div>
        </div>
      </div>
      <div class="actions">
        <el-button :icon="RefreshRight" :disabled="loading" @click="reload">刷新</el-button>
      </div>
    </div>

    <!-- 三张角色卡：既是能力说明，也是「只看这个角色的人」的快捷筛选 -->
    <div class="roleCards">
      <div
        v-for="r in ROLES"
        :key="r.value"
        class="roleCard"
        :class="{ on: filters.role === r.value }"
        @click="toggleRoleFilter(r.value)"
      >
        <div class="rcHead">
          <el-tag :type="r.tag" effect="dark" size="small">{{ r.label }}</el-tag>
          <span class="rcCount">{{ summary[r.value] ?? 0 }}</span>
        </div>
        <div class="rcCap">{{ r.cap }}</div>
        <div class="rcTip">启用账号中的人数 · 点击{{ filters.role === r.value ? '取消筛选' : '只看该角色' }}</div>
      </div>
    </div>

    <el-card class="card" shadow="never">
      <div class="filter-bar">
        <el-form :model="filters" inline class="filter-form">
          <el-form-item label="人员">
            <el-input v-model="filters.keyword" placeholder="用户名或姓名" clearable style="width: 180px" @keyup.enter="onPageChange(1)" />
          </el-form-item>
          <el-form-item label="科室">
            <el-select v-model="filters.deptId" clearable placeholder="全部科室" style="width: 220px">
              <el-option v-for="opt in flatDeptOptions" :key="opt.id" :value="opt.id" :label="opt.label" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="filters.status" clearable placeholder="全部" style="width: 120px">
              <el-option :value="1" label="启用" />
              <el-option :value="0" label="禁用" />
            </el-select>
          </el-form-item>
          <el-form-item class="action-buttons">
            <el-button type="primary" @click="onPageChange(1)">查询</el-button>
            <el-button @click="onReset">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- 批量条：只在勾了人时出现，平时不占地方 -->
      <div v-if="pickedCount" class="batchBar">
        <span class="batchText">已选 {{ pickedCount }} 人，设为</span>
        <el-select v-model="batchRole" style="width: 150px">
          <el-option v-for="r in ROLES" :key="r.value" :value="r.value" :label="r.label" />
        </el-select>
        <el-button type="primary" @click="onBatchApply">批量调整</el-button>
        <span class="batchHint">你自己的那行不能勾选（后端禁止管理员改自己的角色）</span>
      </div>

      <el-alert
        v-if="orphanDeptAdmins.length"
        type="warning"
        :closable="false"
        class="notice"
      >
        <template #title>
          <el-icon class="warnIcon"><WarningFilled /></el-icon>
          本页有 {{ orphanDeptAdmins.length }} 个「科室管理员」没绑科室：{{ orphanDeptAdmins.map((r) => r.realName || r.username).join('、') }}
        </template>
        <div class="noticeBody">科室管理员的权力全部落在本科室内，没绑科室等于只管得到自己的记录。请到「员工管理」里给他补上科室。</div>
      </el-alert>

      <el-table
        v-loading="loading"
        :data="rows"
        border
        stripe
        row-key="id"
        class="roleTable"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="46" :selectable="(row) => !isSelf(row)" />
        <el-table-column label="人员" min-width="170">
          <template #default="{ row }">
            <div class="person">
              <span class="pName">{{ row.realName || '（未填姓名）' }}</span>
              <span class="pUser">{{ row.username }}</span>
              <el-tag v-if="isSelf(row)" size="small" type="info" effect="plain">本人</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="科室" min-width="150">
          <template #default="{ row }">
            <span v-if="row.deptName">{{ row.deptName }}</span>
            <span v-else class="noDept">未绑定</span>
          </template>
        </el-table-column>
        <el-table-column label="当前角色" width="120">
          <template #default="{ row }">
            <el-tag :type="roleMeta(row.role).tag" effect="light">{{ row.roleLabel || roleMeta(row.role).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="调整为" width="170">
          <template #default="{ row }">
            <el-select
              v-model="row.role"
              :disabled="isSelf(row)"
              size="small"
              style="width: 100%"
              @change="onRoleChange(row)"
            >
              <el-option v-for="r in ROLES" :key="r.value" :value="r.value" :label="r.label">
                <span class="opt-line">
                  <span class="opt-label">{{ r.label }}</span>
                  <span class="opt-hint">{{ r.cap }}</span>
                </span>
              </el-option>
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近变更" width="140">
          <template #default="{ row }">{{ fmtTime(row.updateTime) }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pagination"
        background
        layout="prev, pager, next, sizes, total"
        :total="total"
        :current-page="filters.page"
        :page-size="filters.size"
        :page-sizes="[10, 20, 50, 100]"
        @update:current-page="onPageChange"
        @update:page-size="onSizeChange"
      />
    </el-card>

    <el-alert type="info" :closable="false" class="notice">
      <template #title>改角色之前先看这三条</template>
      <ul class="ruleList">
        <li><b>角色随登录令牌签发</b>：改完对方要<b>重新登录</b>才生效，系统会在对方下次进入时提示角色已变更。</li>
        <li><b>不能改自己的角色</b>：否则管理员把自己降权后没人能把他捞回来；需要动自己时请另一位系统管理员操作。</li>
        <li><b>系统必须保留至少一个启用的系统管理员</b>：批量降级最后一名管理员时，那一条会被跳过并说明原因。</li>
        <li>想改的是「某个功能要什么角色」（例如登记渠道维护下放给普通员工），去 <router-link to="/permissions">权限设置</router-link>；账号的增删、改科室、重置密码在 <router-link to="/users">员工管理</router-link>。</li>
      </ul>
    </el-alert>
  </div>
</template>

<style scoped>
.page {
  padding: 18px;
}

.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.titleWrap {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.titleIcon {
  font-size: 22px;
  color: var(--g-text);
  margin-top: 2px;
}

.title {
  font-size: 18px;
  font-weight: 900;
  color: var(--g-text);
}

.sub {
  font-size: 12px;
  color: var(--g-text-muted);
  margin-top: 2px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.roleCards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.roleCard {
  border: 1px solid var(--g-border);
  border-radius: var(--g-radius-md);
  background: #fff;
  padding: 12px 14px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.roleCard:hover {
  border-color: var(--g-border-strong);
}

.roleCard.on {
  border-color: var(--g-accent);
  box-shadow: var(--g-shadow-sm);
}

.rcHead {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.rcCount {
  font-size: 22px;
  font-weight: 900;
  color: var(--g-text);
  line-height: 1;
}

.rcCap {
  font-size: 12.5px;
  color: var(--g-text-secondary);
  margin-top: 8px;
  line-height: 1.6;
}

.rcTip {
  font-size: 11.5px;
  color: var(--g-text-faint);
  margin-top: 6px;
}

.card {
  border-radius: var(--g-radius-md);
}

.filter-bar {
  margin-bottom: 4px;
}

.filter-form :deep(.el-form-item) {
  margin-bottom: 10px;
}

.action-buttons {
  margin-left: 0;
}

.batchBar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 10px 12px;
  margin-bottom: 12px;
  border: 1px dashed var(--g-accent);
  border-radius: var(--g-radius-md);
  background: var(--g-bg-subtle);
}

.batchText {
  font-size: 13px;
  font-weight: 700;
  color: var(--g-text);
}

.batchHint {
  font-size: 12px;
  color: var(--g-text-muted);
}

.person {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.pName {
  font-weight: 700;
  color: var(--g-text);
}

.pUser {
  font-size: 12px;
  color: var(--g-text-muted);
}

.noDept {
  font-size: 12px;
  color: var(--g-warning);
}

.roleTable {
  width: 100%;
}

.opt-line {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
  padding: 4px 0;
}

.opt-label {
  font-size: 13px;
  color: var(--g-text);
}

.opt-hint {
  font-size: 11.5px;
  color: var(--g-text-muted);
}

.notice {
  margin-top: 12px;
}

.warnIcon {
  margin-right: 4px;
  vertical-align: -2px;
}

.noticeBody {
  font-size: 12.5px;
  line-height: 1.7;
  margin-top: 4px;
}

.ruleList {
  margin: 4px 0 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--g-text-secondary);
}

.pagination {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>