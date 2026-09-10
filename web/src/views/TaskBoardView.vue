<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { workRecordPage, workStatusEnabled, workCategoryEnabled, workRecordCreate, workRecordUpdateStatus } from '../api/work'
import { can as canFeature, canEditRecord, currentProfile, ensurePermissions, ensureProfile, ROLE } from '../utils/auth'

const router = useRouter()

/* ========== 基础数据 ========== */
const loading = ref(false)
const records = ref([])
const statuses = ref([])
const categories = ref([])
// 登录档案统一取 utils/auth 那份：路由守卫、菜单、按钮收口共用同一个角色值，
// 看板自己再调一次 /auth/me 会出现「这里认为是管理员、那里认为不是」的分裂
const currentUser = computed(() => currentProfile())
// 后端返回的总数：拉取有上限，超出时要如实告诉用户看到的是截断结果
const serverTotal = ref(0)

/* ========== 视图 / 筛选状态 ========== */
// board=看板，list=列表；左侧「视图」区必须真的能切换，之前是写死的
const viewMode = ref('board')
// mine=只看本人，dept=看本人所在科室（后端 scope 参数）
const scope = ref('mine')
const searchKeyword = ref('')
const activeFilter = ref('all') // all | mine | due | overdue
const activeCategory = ref(null)
const statusFilter = ref([])
const importantOnly = ref(false)
const searchInput = ref(null)
const sortKey = ref('createTime')
const sortDir = ref('desc')

/* ========== 新建任务 / 任务详情（他人任务按角色可推进状态） ========== */
const createOpen = ref(false)
const creating = ref(false)
const createForm = ref(blankForm())
const detailOpen = ref(false)
const detailRecord = ref(null)
// 详情弹窗里的状态草稿 + 保存中标记
const detailStatus = ref(null)
const savingStatus = ref(false)

// 「即将到期」= 未来 7 天内到期且还没完成；已过期是另一回事，单独一个筛选项
const DUE_SOON_DAYS = 7
const DAY_MS = 24 * 60 * 60 * 1000
// 单次拉取上限：后端不校验 size，这里自己收口，超出的部分用「仅显示最近 N 条」提示
const FETCH_SIZE = 500
const isMac = /Mac|iPhone|iPad|iPod/.test(
  typeof navigator !== 'undefined' ? navigator.platform || navigator.userAgent || '' : ''
)

onMounted(async () => {
  window.addEventListener('keydown', onGlobalKey)
  await loadAll()
})
onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKey)
})

async function loadAll() {
  loading.value = true
  try {
    // ensureProfile 不并到 Promise.all 里取返回值：档案统一从 utils/auth 读，这里只要保证它已就位
    await Promise.all([ensureProfile(), ensurePermissions()])
    const [statusResp, catResp] = await Promise.all([workStatusEnabled(), workCategoryEnabled()])
    statuses.value = [...(statusResp.data || [])].sort(
      (a, b) => (a.sortOrder || 0) - (b.sortOrder || 0) || a.id - b.id
    )
    categories.value = catResp.data || []
    // 档案没拿到多半是登录态失效（http 层已在跳登录页），此时拉列表只会被 401 打回，不必再请
    if (!currentUser.value) return
    // 范围可能因为角色变化而不再可用（例如管理员退出换成普通员工重登），先自愈再取数
    if (!scopeAllowed(scope.value)) scope.value = 'mine'
    await loadRecords()
  } catch (e) {
    console.error('加载看板数据失败', e)
  } finally {
    loading.value = false
  }
}

async function loadRecords() {
  const resp = await workRecordPage({ page: 1, size: FETCH_SIZE, scope: scope.value })
  records.value = resp.data?.records || []
  serverTotal.value = resp.data?.total || 0
}

// 切范围要重新拉数据，不是纯前端过滤
async function switchScope(next) {
  if (scope.value === next) return
  // 前端拦一道不是不信任后端（后端 scope=all 会直接 40003），而是省掉一次注定失败的请求和一片空白列
  if (!scopeAllowed(next)) {
    ElMessage.warning('当前角色无该数据范围')
    return
  }
  scope.value = next
  if (next === 'mine' && activeFilter.value === 'mine') activeFilter.value = 'all'
  loading.value = true
  try {
    await loadRecords()
  } catch (e) {
    console.error('切换看板范围失败', e)
  } finally {
    loading.value = false
  }
}

async function refresh() {
  loading.value = true
  try {
    await loadRecords()
  } catch (e) {
    console.error('刷新看板数据失败', e)
  } finally {
    loading.value = false
  }
}

/* ========== 字典映射 ==========
   /work/records 返回的是 WorkRecordDTO：只有 statusId / categoryId / userId / isImportant，
   没有 statusName、categoryName、creatorName，名称只能在前端查字典表。 */
const statusMap = computed(() => new Map(statuses.value.map((s) => [s.id, s.statusName])))
const categoryMap = computed(() => new Map(categories.value.map((c) => [c.id, c.categoryName])))

function statusName(id) {
  return statusMap.value.get(id) || ''
}
function categoryName(id) {
  return categoryMap.value.get(id) || ''
}

/* 状态归类：注意判定顺序，「待处理」里也含「处理」，必须先判完成 / 关闭 */
function statusTone(id) {
  const n = (statusName(id) || '').toLowerCase()
  if (!n) return 'todo'
  if (n.includes('完成') || n.includes('已办') || n.includes('解决') || n.includes('done') || n.includes('finish')) return 'done'
  if (n.includes('关闭') || n.includes('归档') || n.includes('取消') || n.includes('作废') || n.includes('不处理') || n.includes('close')) return 'closed'
  if (n.includes('进行') || n.includes('处理中') || n.includes('受理中') || n.includes('跟进') || n.includes('执行') || n.includes('doing')) return 'progress'
  return 'todo'
}

const TONE_DOT = { todo: 'yellow', progress: 'blue', done: 'green', closed: 'gray' }

function dotOf(id) {
  return TONE_DOT[statusTone(id)]
}

/* ========== 记录级判定 ========== */
function isMine(r) {
  return !!currentUser.value && r.userId === currentUser.value.id
}
function isTerminal(r) {
  const tone = statusTone(r.statusId)
  return tone === 'done' || tone === 'closed'
}
function isOverdue(r) {
  if (!r.endTime || isTerminal(r)) return false
  return new Date(r.endTime).getTime() < Date.now()
}
function isDueSoon(r) {
  if (!r.endTime || isTerminal(r)) return false
  const t = new Date(r.endTime).getTime()
  return t >= Date.now() && t <= Date.now() + DUE_SOON_DAYS * DAY_MS
}
function recordUserName(r) {
  // 科室范围下后端会带 recorderName；本人记录在旧数据里可能为空，退回登录态姓名
  if (r.recorderName) return r.recorderName
  if (isMine(r) && currentUser.value) return currentUser.value.realName || currentUser.value.username || ''
  return ''
}

// 三个范围档位读的都是「权限设置」里的门槛（board.scopeDept / board.scopeAll / record.editDeptOthers），
// 中括号里是代码内置下限，只在配置接口没回来时兜底
const canDeptScope = computed(
  () => !!currentUser.value?.deptId && canFeature('board.scopeDept', [ROLE.USER])
)
/** 「全部科室」范围 */
const isSysAdmin = computed(() => canFeature('board.scopeAll', [ROLE.ADMIN]))
/** 本科室范围下能否改别人的任务（与后端 DataScope.canEdit 同一口径） */
const canEditOthers = computed(() => canFeature('record.editDeptOthers', [ROLE.DEPT_ADMIN]))

/** 该范围对当前角色是否开放：普通员工只能看自己和只读本科室 */
function scopeAllowed(key) {
  if (key === 'all') return isSysAdmin.value
  if (key === 'dept') return canDeptScope.value
  return key === 'mine'
}

/** 范围文案集中一处，标题、概览、工具栏都从这里取，免得三个地方各写一遍「本科室」 */
const scopeLabel = computed(() => (scope.value === 'all' ? '全部科室' : scope.value === 'dept' ? '本科室' : '我负责的'))

/* ========== 筛选 ========== */
const filteredRecords = computed(() => {
  let list = records.value
  const kw = searchKeyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter(
      (r) => (r.title || '').toLowerCase().includes(kw) || (r.content || '').toLowerCase().includes(kw)
    )
  }
  if (activeFilter.value === 'mine') list = list.filter(isMine)
  if (activeFilter.value === 'due') list = list.filter(isDueSoon)
  if (activeFilter.value === 'overdue') list = list.filter(isOverdue)
  if (activeCategory.value) list = list.filter((r) => r.categoryId === activeCategory.value)
  if (statusFilter.value.length) list = list.filter((r) => statusFilter.value.includes(r.statusId))
  if (importantOnly.value) list = list.filter((r) => r.isImportant === 1)
  return list
})

const totalCount = computed(() => records.value.length)
const mineCount = computed(() => records.value.filter(isMine).length)
const importantCount = computed(() => records.value.filter((r) => r.isImportant === 1).length)
const dueCount = computed(() => records.value.filter(isDueSoon).length)
const overdueCount = computed(() => records.value.filter(isOverdue).length)

function categoryCount(catId) {
  return records.value.filter((r) => r.categoryId === catId).length
}

const hasFilter = computed(
  () =>
    activeFilter.value !== 'all' ||
    !!activeCategory.value ||
    statusFilter.value.length > 0 ||
    importantOnly.value ||
    !!searchKeyword.value.trim()
)

// 生效中的条件全部摊开成可单独摘掉的标签，否则筛选结果和数字对不上时没人知道为什么
const filterChips = computed(() => {
  const chips = []
  if (activeFilter.value === 'mine') chips.push({ key: 'filter', label: '仅我负责', reset: () => (activeFilter.value = 'all') })
  if (activeFilter.value === 'due') chips.push({ key: 'filter', label: '即将到期', reset: () => (activeFilter.value = 'all') })
  if (activeFilter.value === 'overdue') chips.push({ key: 'filter', label: '已逾期', reset: () => (activeFilter.value = 'all') })
  if (activeCategory.value) {
    chips.push({
      key: 'category',
      label: categoryName(activeCategory.value) || '分类',
      reset: () => (activeCategory.value = null)
    })
  }
  for (const id of statusFilter.value) {
    chips.push({ key: 'status' + id, label: statusName(id) || '状态', reset: () => toggleStatus(id) })
  }
  if (importantOnly.value) chips.push({ key: 'important', label: '重要', reset: () => (importantOnly.value = false) })
  if (searchKeyword.value.trim()) {
    chips.push({ key: 'kw', label: '“' + searchKeyword.value.trim() + '”', reset: () => (searchKeyword.value = '') })
  }
  return chips
})

function clearFilters() {
  activeFilter.value = 'all'
  activeCategory.value = null
  statusFilter.value = []
  importantOnly.value = false
  searchKeyword.value = ''
}

function resetQuickFilter() {
  activeFilter.value = 'all'
  importantOnly.value = false
}

function toggleStatus(id) {
  statusFilter.value = statusFilter.value.includes(id)
    ? statusFilter.value.filter((i) => i !== id)
    : [...statusFilter.value, id]
}

function toggleCategory(catId) {
  activeCategory.value = activeCategory.value === catId ? null : catId
}

/* ========== 新建任务入库 ==========
   分类可以挂模板（templateJson），看板新建必须遵守模板的必填约束，
   否则后端 applyCategoryTemplateRules 会把请求直接打回来。 */
function blankForm() {
  return {
    title: '',
    categoryId: null,
    statusId: null,
    startTime: '',
    endTime: '',
    content: '',
    isImportant: 0
  }
}

const createTemplate = computed(() => {
  const cat = categories.value.find((c) => c.id === createForm.value.categoryId)
  if (!cat || !cat.templateJson) return null
  try {
    return JSON.parse(cat.templateJson) || null
  } catch (e) {
    return null
  }
})

const requireContent = computed(() => createTemplate.value?.requireContent === true)
const requireEndTime = computed(() => createTemplate.value?.requireEndTime === true)
const requireImage = computed(() => createTemplate.value?.requireImage === true)

function openCreate() {
  const f = blankForm()
  f.categoryId = activeCategory.value || (categories.value[0] ? categories.value[0].id : null)
  f.statusId = statuses.value[0] ? statuses.value[0].id : null
  createForm.value = f
  createOpen.value = true
}

async function submitCreate() {
  const f = createForm.value
  if (!f.title.trim()) {
    ElMessage.warning('请填写任务标题')
    return
  }
  if (!f.categoryId) {
    ElMessage.warning('请选择工作分类')
    return
  }
  if (requireContent.value && !f.content.trim()) {
    ElMessage.warning('该分类要求填写工作内容')
    return
  }
  if (requireEndTime.value && !f.endTime) {
    ElMessage.warning('该分类要求填写截止日期')
    return
  }
  if (f.startTime && f.endTime && f.startTime > f.endTime) {
    ElMessage.warning('截止日期不能早于开始时间')
    return
  }
  creating.value = true
  try {
    await workRecordCreate({
      title: f.title.trim(),
      categoryId: f.categoryId,
      statusId: f.statusId,
      content: f.content.trim() || null,
      startTime: f.startTime || null,
      endTime: f.endTime || null,
      isImportant: f.isImportant
    })
    ElMessage.success('任务已创建')
    createOpen.value = false
    await loadRecords()
  } catch (e) {
    ElMessage.error(e?.message || '创建失败')
  } finally {
    creating.value = false
  }
}

/* ========== 看板列：直接由状态字典生成，不再写死四列 ========== */
const columns = computed(() => {
  const buckets = new Map(statuses.value.map((s) => [s.id, []]))
  const orphan = []
  for (const r of filteredRecords.value) {
    if (buckets.has(r.statusId)) buckets.get(r.statusId).push(r)
    else orphan.push(r)
  }
  const cols = statuses.value.map((s) => ({
    key: 's' + s.id,
    statusId: s.id,
    name: s.statusName,
    dotClass: dotOf(s.id),
    narrow: statusTone(s.id) === 'closed',
    items: buckets.get(s.id)
  }))
  // 状态被停用但历史记录还挂着它，给一列兜底，别让任务凭空消失
  if (orphan.length) {
    cols.push({ key: 'orphan', statusId: null, name: '未匹配状态', dotClass: 'gray', narrow: true, items: orphan })
  }
  return cols
})

const progressStats = computed(() => {
  const total = filteredRecords.value.length
  const stat = { todo: 0, progress: 0, done: 0, closed: 0 }
  for (const r of filteredRecords.value) stat[statusTone(r.statusId)] += 1
  const pct = (n) => (total ? Math.round((n / total) * 100) : 0)
  return {
    rows: [
      { key: 'todo', label: '待办', cls: 'amber', value: pct(stat.todo) },
      { key: 'progress', label: '进行', cls: 'blue', value: pct(stat.progress) },
      { key: 'done', label: '完成', cls: 'green', value: pct(stat.done) },
      { key: 'closed', label: '关闭', cls: 'gray', value: pct(stat.closed) }
    ]
  }
})

/* ========== 列表视图 ========== */
function sortValue(r) {
  switch (sortKey.value) {
    case 'title':
      return (r.title || '').toLowerCase()
    case 'status':
      return statusName(r.statusId)
    case 'category':
      return categoryName(r.categoryId)
    case 'endTime':
      return r.endTime ? new Date(r.endTime).getTime() : 0
    default:
      return r.createTime ? new Date(r.createTime).getTime() : 0
  }
}

const listRows = computed(() => {
  const dir = sortDir.value === 'asc' ? 1 : -1
  return [...filteredRecords.value].sort((a, b) => {
    const va = sortValue(a)
    const vb = sortValue(b)
    if (va === vb) return b.id - a.id
    return va > vb ? dir : -dir
  })
})

function toggleSort(key) {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = key
    sortDir.value = key === 'title' || key === 'category' || key === 'status' ? 'asc' : 'desc'
  }
}

/* ========== 展示辅助 ========== */
function priorityClass(record) {
  return record.isImportant === 1 ? 'high' : 'med'
}

function formatDate(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  return d.getMonth() + 1 + '月' + d.getDate() + '日'
}

function formatDateTime(dateStr) {
  if (!dateStr) return '—'
  const d = new Date(dateStr)
  const pad = (n) => String(n).padStart(2, '0')
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
}

function avatarText(name) {
  if (!name) return '?'
  return /[\u4e00-\u9fa5]/.test(name) ? name.charAt(0) : name.substring(0, 2).toUpperCase()
}

function avatarColor(name) {
  if (!name) return 'c5'
  const colors = ['c1', 'c2', 'c3', 'c4', 'c5']
  let hash = 0
  for (let i = 0; i < name.length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash)
  return colors[Math.abs(hash) % colors.length]
}

function tagColor(catId) {
  const palette = ['#DC2626', '#059669', '#2563EB', '#8B5CF6', '#D97706', '#EC4899']
  return palette[(catId || 0) % palette.length]
}

function tagCssClass(name) {
  if (!name) return ''
  const n = name.toLowerCase()
  if (n.includes('bug') || n.includes('问题') || n.includes('故障') || n.includes('维修')) return 'danger'
  if (n.includes('功能') || n.includes('feature') || n.includes('设计')) return 'info'
  return ''
}

function openDetail(record) {
  detailRecord.value = record
  detailStatus.value = record.statusId ?? null
  detailOpen.value = true
}

function openRecord(record) {
  // 本人的任务去工作记录页（那里能改全部字段）；别人的任务留在看板详情，
  // 直接跳过去会查无此项——工作记录列表按本人收口，跨本人的编辑入口只能放在这儿
  if (isMine(record)) {
    router.push({ path: '/work-records', query: { focusId: String(record.id) } })
    return
  }
  openDetail(record)
}

/* ========== 他人任务：按角色推进状态 ==========
   科室管理员/系统管理员在看板上就能把同事的任务往前推一格，
   不必为了改一个状态去找记录人本人。判定口径与后端 DataScope.canEdit 一致。 */
const detailEditable = computed(() => !!detailRecord.value && canEditRecord(detailRecord.value))

async function saveDetailStatus() {
  const r = detailRecord.value
  if (!r || detailStatus.value == null || detailStatus.value === r.statusId) {
    detailOpen.value = false
    return
  }
  savingStatus.value = true
  try {
    await workRecordUpdateStatus(r.id, detailStatus.value)
    // 就地更新那一条，看板列会自动重排，省掉整表重拉
    const target = records.value.find((x) => x.id === r.id)
    if (target) target.statusId = detailStatus.value
    detailRecord.value = { ...r, statusId: detailStatus.value }
    ElMessage.success('状态已更新')
    detailOpen.value = false
  } catch (e) {
    // 权限不足由 http 层统一提示，这里兜住其它异常，别让弹窗卡在保存中转圈
    if (e?.code !== 403 && e?.code !== 40003) ElMessage.error(e?.message || '状态更新失败')
  } finally {
    savingStatus.value = false
  }
}

/* ========== 快捷键：搜索框里的 ⌘K / Ctrl K 提示是真的能用 ========== */
function onGlobalKey(e) {
  if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
    e.preventDefault()
    searchInput.value?.focus()
    searchInput.value?.select()
  } else if (e.key === 'Escape' && searchKeyword.value) {
    searchKeyword.value = ''
  }
}
</script>
<template>
  <div class="board-page" v-loading="loading">
    <!-- 左侧面板 -->
    <aside class="side-panel">
      <div class="sp-brand">
        <h2><span class="logo-dot"></span>任务看板</h2>
        <p>{{ scope === "mine" ? totalCount + " 个任务" : scopeLabel + " " + totalCount + " 个任务" }}</p>
        <p class="sp-trunc" v-if="serverTotal > records.length">仅载入最近 {{ records.length }} 条（共 {{ serverTotal }} 条）</p>
      </div>

      <div class="sp-section">
        <div class="sp-label">视图</div>
        <div class="sp-item" :class="{ on: viewMode === 'board' }" @click="viewMode = 'board'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>
          <span>看板视图</span>
        </div>
        <div class="sp-item" :class="{ on: viewMode === 'list' }" @click="viewMode = 'list'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><circle cx="4" cy="6" r="1" fill="currentColor"/><circle cx="4" cy="12" r="1" fill="currentColor"/><circle cx="4" cy="18" r="1" fill="currentColor"/></svg>
          <span>列表视图</span>
        </div>
      </div>
      <!-- 范围按角色给：本科室人人可看（后端只放开同科室），全部科室只有系统管理员 -->
      <div class="sp-section" v-if="canDeptScope || isSysAdmin">
        <div class="sp-label">范围</div>
        <div class="sp-item" :class="{ on: scope === 'mine' }" @click="switchScope('mine')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
          <span>我负责的</span>
        </div>
        <div class="sp-item" v-if="canDeptScope" :class="{ on: scope === 'dept' }" @click="switchScope('dept')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 21h18"/><path d="M5 21V7l7-4 7 4v14"/><path d="M9 21v-5h6v5"/></svg>
          <span>本科室</span>
          <span class="sp-note" v-if="!canEditOthers">只读</span>
        </div>
        <div class="sp-item" v-if="isSysAdmin" :class="{ on: scope === 'all' }" @click="switchScope('all')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>
          <span>全部科室</span>
        </div>
      </div>

      <div class="sp-section">
        <div class="sp-label">筛选</div>
        <div class="sp-item" :class="{ on: activeFilter === 'all' && !importantOnly }" @click="resetQuickFilter">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>
          <span>全部任务</span>
          <span class="sp-num">{{ totalCount }}</span>
        </div>
        <div class="sp-item" :class="{ on: importantOnly }" @click="importantOnly = !importantOnly">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2 15.09 8.26 22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z"/></svg>
          <span>重要任务</span>
          <span class="sp-num">{{ importantCount }}</span>
        </div>
        <!-- 范围是「我负责的」时这项恒真，只有科室范围下才有筛选意义 -->
        <div class="sp-item" v-if="scope !== 'mine'" :class="{ on: activeFilter === 'mine' }" @click="activeFilter = activeFilter === 'mine' ? 'all' : 'mine'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
          <span>我负责的</span>
          <span class="sp-num">{{ mineCount }}</span>
        </div>
        <div class="sp-item" :class="{ on: activeFilter === 'due' }" @click="activeFilter = activeFilter === 'due' ? 'all' : 'due'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
          <span>即将到期</span>
          <span class="sp-num">{{ dueCount }}</span>
        </div>
        <div class="sp-item" :class="{ on: activeFilter === 'overdue' }" @click="activeFilter = activeFilter === 'overdue' ? 'all' : 'overdue'">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
          <span>已逾期</span>
          <span class="sp-num">{{ overdueCount }}</span>
        </div>
      </div>

      <div class="sp-section" v-if="categories.length">
        <div class="sp-label">分类</div>
        <div class="sp-tag" v-for="cat in categories" :key="cat.id"
             @click="toggleCategory(cat.id)"
             :class="{ active: activeCategory === cat.id }">
          <div class="dot" :style="{ background: tagColor(cat.id) }"></div>
          <label>{{ cat.categoryName }}</label>
          <small>{{ categoryCount(cat.id) }}</small>
        </div>
      </div>

      <div class="sp-overview">
        <h4>项目进度</h4>
        <div class="ov-row" v-for="row in progressStats.rows" :key="row.key">
          <span class="ov-label">{{ row.label }}</span>
          <div class="ov-bar"><div class="ov-fill" :class="row.cls" :style="{ width: row.value + '%' }"></div></div>
          <span class="ov-pct">{{ row.value }}%</span>
        </div>
      </div>

      <div class="sp-user" v-if="currentUser">
        <div class="av">{{ avatarText(currentUser.realName || currentUser.username) }}</div>
        <div class="info">
          <div class="name">{{ currentUser.realName || currentUser.username }}</div>
          <div class="role">{{ scope === "mine" ? "我的任务 " + mineCount + " 个" : scopeLabel + " " + totalCount + " 个任务" }}</div>
        </div>
      </div>
    </aside>

    <!-- 主内容区 -->
    <main class="main-area">
      <div class="toolbar">
        <span class="tb-title">{{ viewMode === 'board' ? '看板视图' : '列表视图' }}</span>
        <span class="tb-count">{{ filteredRecords.length }} 个任务</span>
        <!-- 只读范围要标明：不然普通员工会以为是页面坏了 -->
        <span class="tb-scope" v-if="scope !== 'mine'">{{ scopeLabel }}{{ canEditOthers ? '' : ' · 只读' }}</span>
        <span class="tb-chip" v-for="chip in filterChips" :key="chip.key" @click.stop="chip.reset()">
          {{ chip.label }}<span class="x">×</span>
        </span>
        <div class="tb-spacer"></div>
        <button class="tb-btn tb-primary" type="button" @click="openCreate">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          新建任务
        </button>
        <div class="tb-search">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          <input ref="searchInput" v-model="searchKeyword" placeholder="搜索任务..." />
          <kbd>{{ isMac ? '⌘K' : 'Ctrl K' }}</kbd>
        </div>
        <el-popover placement="bottom-end" trigger="click" :width="232" popper-class="board-filter-popper">
          <template #reference>
            <button class="tb-btn" :class="{ active: hasFilter }" type="button">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/></svg>
              筛选
            </button>
          </template>
          <div class="fp-label">状态</div>
          <div class="fp-item" v-for="s in statuses" :key="s.id" :class="{ on: statusFilter.includes(s.id) }" @click="toggleStatus(s.id)">
            <span class="fp-dot" :class="dotOf(s.id)"></span>
            <span class="fp-name">{{ s.statusName }}</span>
            <span class="fp-check" v-if="statusFilter.includes(s.id)">✓</span>
          </div>
          <div class="fp-label mt">其他</div>
          <div class="fp-item" :class="{ on: importantOnly }" @click="importantOnly = !importantOnly">
            <span class="fp-dot high"></span>
            <span class="fp-name">仅看重要任务</span>
            <span class="fp-check" v-if="importantOnly">✓</span>
          </div>
          <div class="fp-foot">
            <button type="button" class="fp-clear" :disabled="!hasFilter" @click="clearFilters">清除全部筛选</button>
          </div>
        </el-popover>
        <button class="tb-btn" type="button" @click="refresh" title="重新拉取任务">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
          刷新
        </button>
      </div>

      <!-- 看板视图 -->
      <div class="board" v-if="viewMode === 'board'">
        <!-- 状态字典没加载成功时给个提示，否则整页会是一片空白 -->
        <div class="board-hint" v-if="!columns.length">
          <p>{{ loading ? '正在加载…' : '没有可用的工作状态，请先在「工作记录」里维护状态字典' }}</p>
        </div>
        <div v-for="col in columns" :key="col.key" class="col" :class="{ narrow: col.narrow }">
          <div class="col-head">
            <div class="col-dot" :class="col.dotClass"></div>
            <div class="col-name">{{ col.name }}</div>
            <div class="col-badge">{{ col.items.length }}</div>
          </div>

          <div class="col-body" v-if="col.items.length">
            <div v-for="r in col.items" :key="r.id"
                 class="card"
                 :class="{ 'is-done': statusTone(col.statusId) === 'done' }"
                 @click="openRecord(r)">
              <div class="stripe" :class="priorityClass(r)"></div>
              <div class="card-row1">
                <div class="card-title">{{ r.title || '未命名任务' }}</div>
              </div>
              <div class="card-foot">
                <div class="card-tags">
                  <span class="tag" v-if="categoryName(r.categoryId)" :class="tagCssClass(categoryName(r.categoryId))">{{ categoryName(r.categoryId) }}</span>
                  <span class="tag danger" v-if="r.isImportant === 1">重要</span>
                </div>
                <div class="card-meta">
                  <span class="card-date" :class="{ late: isOverdue(r) }" v-if="r.endTime" :title="isOverdue(r) ? '已逾期' : '截止时间'">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                    {{ formatDate(r.endTime) }}
                  </span>
                  <span class="card-who" v-if="scope !== 'mine' && recordUserName(r)">{{ recordUserName(r) }}</span>
                  <div class="card-av" :class="avatarColor(recordUserName(r))" :title="recordUserName(r)" v-if="recordUserName(r)">
                    {{ avatarText(recordUserName(r)) }}
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="col-empty" v-else>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
            <p>暂无「{{ col.name }}」任务</p>
          </div>
        </div>
      </div>

      <!-- 列表视图 -->
      <div class="list-wrap" v-else>
        <table class="list-table">
          <thead>
            <tr>
              <th class="sortable" :class="{ sorted: sortKey === 'title' }" @click="toggleSort('title')">
                任务标题<span class="arw" v-if="sortKey === 'title'">{{ sortDir === 'asc' ? '▲' : '▼' }}</span>
              </th>
              <th class="sortable w120" :class="{ sorted: sortKey === 'status' }" @click="toggleSort('status')">
                状态<span class="arw" v-if="sortKey === 'status'">{{ sortDir === 'asc' ? '▲' : '▼' }}</span>
              </th>
              <th class="sortable w140" :class="{ sorted: sortKey === 'category' }" @click="toggleSort('category')">
                分类<span class="arw" v-if="sortKey === 'category'">{{ sortDir === 'asc' ? '▲' : '▼' }}</span>
              </th>
              <th class="w110">记录人</th>
              <th class="w90">重要</th>
              <th class="sortable w150" :class="{ sorted: sortKey === 'endTime' }" @click="toggleSort('endTime')">
                截止<span class="arw" v-if="sortKey === 'endTime'">{{ sortDir === 'asc' ? '▲' : '▼' }}</span>
              </th>
              <th class="sortable w170" :class="{ sorted: sortKey === 'createTime' }" @click="toggleSort('createTime')">
                创建时间<span class="arw" v-if="sortKey === 'createTime'">{{ sortDir === 'asc' ? '▲' : '▼' }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in listRows" :key="r.id" @click="openRecord(r)">
              <td class="cell-title">
                <span class="stripe-inline" :class="priorityClass(r)"></span>
                <span class="tt">{{ r.title || '未命名任务' }}</span>
              </td>
              <td>
                <span class="st-pill" :class="dotOf(r.statusId)">{{ statusName(r.statusId) || '未知状态' }}</span>
              </td>
              <td class="cell-sub">{{ categoryName(r.categoryId) || '—' }}</td>
              <td class="cell-who">
                <span class="who-pill" :class="{ self: isMine(r) }">{{ recordUserName(r) || '—' }}</span>
              </td>
              <td>
                <span class="tag danger" v-if="r.isImportant === 1">重要</span>
                <span class="cell-sub" v-else>—</span>
              </td>
              <td :class="{ late: isOverdue(r), soon: isDueSoon(r) }">{{ r.endTime ? formatDateTime(r.endTime) : '—' }}</td>
              <td class="cell-sub">{{ formatDateTime(r.createTime) }}</td>
            </tr>
            <tr v-if="!listRows.length">
              <td colspan="7">
                <div class="list-empty">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
                  <p>{{ hasFilter ? '当前筛选条件下没有任务' : '还没有任务' }}</p>
                  <button type="button" class="fp-clear" v-if="hasFilter" @click="clearFilters">清除筛选</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>

    <!-- 新建任务：直接入库，不等跳去工作记录页 -->
    <el-dialog v-model="createOpen" title="新建任务" width="560px" :close-on-click-modal="false" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="任务标题" required>
          <el-input v-model="createForm.title" maxlength="200" show-word-limit placeholder="一句话说清要做什么" />
          <div class="fd-hint" v-if="createTemplate && createTemplate.titlePattern">该分类要求标题格式：{{ createTemplate.titlePattern }}</div>
        </el-form-item>
        <div class="fd-grid">
          <el-form-item label="工作分类" required>
            <el-select v-model="createForm.categoryId" placeholder="请选择分类" style="width: 100%">
              <el-option v-for="c in categories" :key="c.id" :value="c.id" :label="c.categoryName" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="createForm.statusId" placeholder="默认第一个状态" style="width: 100%">
              <el-option v-for="s in statuses" :key="s.id" :value="s.id" :label="s.statusName" />
            </el-select>
          </el-form-item>
        </div>
        <div class="fd-grid">
          <el-form-item label="开始时间">
            <el-date-picker v-model="createForm.startTime" type="datetime" placeholder="可选" value-format="YYYY-MM-DD HH:mm:ss" format="YYYY-MM-DD HH:mm" style="width: 100%" />
          </el-form-item>
          <el-form-item :label="requireEndTime ? '截止日期（该分类必填）' : '截止日期'">
            <el-date-picker v-model="createForm.endTime" type="datetime" placeholder="用于到期与逾期提醒" value-format="YYYY-MM-DD HH:mm:ss" format="YYYY-MM-DD HH:mm" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item :label="requireContent ? '工作内容（该分类必填）' : '工作内容'">
          <el-input type="textarea" :rows="3" v-model="createForm.content" :placeholder="createTemplate && createTemplate.contentTemplate ? createTemplate.contentTemplate : '可选'" />
        </el-form-item>
        <el-form-item label="标记为重要">
          <el-switch v-model="createForm.isImportant" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <div class="fd-warn" v-if="requireImage">该分类要求上传至少一张图片，看板新建不支持附件，请前往「工作记录」页新建。</div>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="requireImage" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 别人的任务：工作记录页列表按本人收口，跳过去查无此项，所以详情留在看板里给 -->
    <el-dialog v-model="detailOpen" title="任务详情" width="520px" append-to-body>
      <div class="dd-body" v-if="detailRecord">
        <h3>{{ detailRecord.title || '未命名任务' }}</h3>
        <div class="dd-row"><span>记录人</span><b>{{ recordUserName(detailRecord) || '—' }}</b></div>
        <div class="dd-row">
          <span>状态</span>
          <!-- 科室管理员 / 系统管理员可直接推进他人任务状态，普通员工只看 -->
          <el-select
            v-if="detailEditable"
            v-model="detailStatus"
            size="small"
            class="dd-status"
            placeholder="选择状态"
          >
            <el-option v-for="s in statuses" :key="s.id" :value="s.id" :label="s.statusName" />
          </el-select>
          <b v-else class="st-pill" :class="dotOf(detailRecord.statusId)">{{ statusName(detailRecord.statusId) || '未知状态' }}</b>
        </div>
        <div class="dd-row"><span>分类</span><b>{{ categoryName(detailRecord.categoryId) || '—' }}</b></div>
        <div class="dd-row"><span>开始时间</span><b>{{ formatDateTime(detailRecord.startTime) }}</b></div>
        <div class="dd-row"><span>截止时间</span><b :class="{ late: isOverdue(detailRecord) }">{{ formatDateTime(detailRecord.endTime) }}</b></div>
        <div class="dd-row"><span>重要</span><b>{{ detailRecord.isImportant === 1 ? '是' : '否' }}</b></div>
        <div class="dd-content" v-if="detailRecord.content">{{ detailRecord.content }}</div>
        <div class="dd-tip" v-if="detailEditable">你可以推进该任务的状态；要改标题、内容、附件等完整字段，请由记录人本人在「工作记录」页操作。</div>
        <div class="dd-tip" v-else>如需编辑或补充处理详情，请由记录人本人在「工作记录」页操作。</div>
      </div>
      <template #footer>
        <el-button @click="detailOpen = false">关闭</el-button>
        <el-button
          v-if="detailEditable"
          type="primary"
          :loading="savingStatus"
          :disabled="detailStatus === detailRecord.statusId"
          @click="saveDetailStatus"
        >
          保存状态
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
/* 看板页面布局 */
.board-page {
  display: flex;
  height: 100%;
  background: #F7F5F0;
  overflow: hidden;
  border: 1px solid #E5E7EB;
  border-radius: 10px;
}

/* ===== 左侧面板 ===== */
.side-panel {
  width: 256px;
  background: #fff;
  border-right: 1px solid #E5E7EB;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  overflow-y: auto;
}
.sp-brand {
  padding: 20px 20px 16px;
  border-bottom: 1px solid #F3F4F6;
}
.sp-brand h2 {
  font-size: 18px;
  font-weight: 800;
  color: #1A3C34;
  letter-spacing: -0.5px;
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}
.sp-brand .logo-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: #2B5F54; display: inline-block;
}
.sp-brand p {
  font-size: 12px; color: #9CA3AF; margin: 3px 0 0;
}
.sp-trunc { color: #B45309 !important; }
.sp-section { padding: 14px 12px 0; }
.sp-section + .sp-section { padding-top: 6px; }
.sp-label {
  font-size: 10px; font-weight: 700; text-transform: uppercase;
  letter-spacing: 1px; color: #9CA3AF; padding: 0 10px; margin-bottom: 6px;
}
.sp-item {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 10px; border-radius: 6px; cursor: pointer;
  transition: all 0.12s; margin-bottom: 1px;
}
.sp-item:hover { background: rgba(43,95,84,0.06); }
.sp-item.on { background: #2B5F54; color: #fff; }
.sp-item svg { width: 16px; height: 16px; opacity: 0.55; flex-shrink: 0; }
.sp-item.on svg { opacity: 1; }
.sp-item span { font-size: 13px; font-weight: 500; color: #374151; flex: 1; }
.sp-item.on span { color: #fff; }
.sp-num {
  font-size: 11px !important; font-weight: 600; color: #9CA3AF !important;
  background: #F3F4F6; padding: 1px 7px; border-radius: 8px; flex: none !important;
}
.sp-item.on .sp-num {
  background: rgba(255,255,255,0.15); color: rgba(255,255,255,0.85) !important;
}
/* 范围项上的小标记：写「只读」，提示这一档只能看不能改 */
.sp-note {
  font-size: 10px !important; font-weight: 700; letter-spacing: .02em;
  color: #B45309 !important; background: #FEF3C7; padding: 1px 5px;
  border-radius: 6px; flex: none !important; margin-left: auto;
}
.sp-item.on .sp-note { background: rgba(255,255,255,0.18); color: #fff !important; }
.sp-tag {
  display: flex; align-items: center; gap: 10px;
  padding: 7px 10px; border-radius: 6px; cursor: pointer;
  transition: all 0.12s; margin-bottom: 1px;
}
.sp-tag:hover { background: rgba(43,95,84,0.06); }
.sp-tag.active { background: rgba(43,95,84,0.1); }
.sp-tag .dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
.sp-tag label { font-size: 13px; color: #374151; flex: 1; cursor: pointer; }
.sp-tag small { font-size: 11px; color: #9CA3AF; }

/* 进度概览 */
.sp-overview {
  margin: 16px 12px; padding: 14px;
  background: #F7F5F0; border-radius: 10px; border: 1px solid #F3F4F6;
}
.sp-overview h4 { font-size: 11px; font-weight: 600; color: #6B7280; margin: 0 0 10px; }
.ov-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.ov-row:last-child { margin-bottom: 0; }
.ov-label { font-size: 11px; color: #6B7280; width: 36px; }
.ov-bar { flex: 1; height: 5px; background: #E5E7EB; border-radius: 3px; overflow: hidden; }
.ov-fill { height: 100%; border-radius: 3px; transition: width 0.4s; }
.ov-fill.green { background: linear-gradient(90deg,#10B981,#34D399); }
.ov-fill.blue { background: linear-gradient(90deg,#3B82F6,#60A5FA); }
.ov-fill.amber { background: linear-gradient(90deg,#F59E0B,#FBBF24); }
.ov-fill.gray { background: linear-gradient(90deg,#9CA3AF,#D1D5DB); }
.ov-pct { font-size: 11px; font-weight: 600; color: #6B7280; width: 32px; text-align: right; }

/* 用户区 */
.sp-user {
  margin-top: auto; padding: 14px 16px;
  border-top: 1px solid #E5E7EB;
  display: flex; align-items: center; gap: 10px;
}
.sp-user .av {
  width: 34px; height: 34px; border-radius: 50%;
  background: linear-gradient(135deg,#2B5F54,#3A7D6F);
  color: #fff; display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 700; flex-shrink: 0;
}
.sp-user .name { font-size: 13px; font-weight: 600; color: #111827; }
.sp-user .role { font-size: 11px; color: #9CA3AF; }

/* ===== 主内容区 ===== */
.main-area { flex: 1; display: flex; flex-direction: column; min-width: 0; }

/* 工具栏 */
.toolbar {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 24px; background: #fff;
  border-bottom: 1px solid #E5E7EB; flex-shrink: 0;
  flex-wrap: wrap;
}
.tb-title { font-size: 15px; font-weight: 700; color: #111827; }
.tb-count { font-size: 12px; font-weight: 500; color: #9CA3AF; margin-left: 2px; }
.tb-scope {
  font-size: 11px; font-weight: 700; color: #1A3C34;
  background: #E7F0ED; padding: 2px 8px; border-radius: 20px;
}
.tb-primary { background: #2B5F54; border-color: #2B5F54; color: #fff; }
.tb-primary:hover { background: #234E45; border-color: #234E45; color: #fff; }
.tb-chip {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 11px; font-weight: 600; color: #2B5F54;
  background: rgba(43,95,84,0.08); border: 1px solid rgba(43,95,84,0.18);
  padding: 2px 6px 2px 8px; border-radius: 20px; cursor: pointer;
}
.tb-chip .x { font-size: 13px; line-height: 1; opacity: 0.6; }
.tb-chip:hover .x { opacity: 1; }
.tb-spacer { flex: 1; }
.tb-search {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 12px; border: 1px solid #E5E7EB;
  border-radius: 6px; background: #F7F5F0; width: 200px;
  transition: all 0.15s;
}
.tb-search:focus-within { border-color: #2B5F54; box-shadow: 0 0 0 3px rgba(43,95,84,0.08); }
.tb-search svg { width: 14px; height: 14px; color: #9CA3AF; flex-shrink: 0; }
.tb-search input {
  flex: 1; min-width: 0; border: none; outline: none; background: transparent;
  font-size: 13px; color: #111827; font-family: inherit;
}
.tb-search input::placeholder { color: #9CA3AF; }
.tb-search kbd {
  font-family: inherit; font-size: 10px; font-weight: 600; color: #9CA3AF;
  background: #fff; border: 1px solid #E5E7EB; padding: 1px 5px; border-radius: 3px;
  white-space: nowrap;
}
.tb-btn {
  display: flex; align-items: center; gap: 5px;
  padding: 7px 14px; border-radius: 6px;
  font-size: 13px; font-weight: 500;
  border: 1px solid #E5E7EB; background: #fff; color: #374151;
  cursor: pointer; transition: all 0.12s;
}
.tb-btn:hover { border-color: #2B5F54; color: #2B5F54; }
.tb-btn.active { background: rgba(43,95,84,0.06); border-color: #2B5F54; color: #2B5F54; }
.tb-btn svg { width: 14px; height: 14px; }

/* 筛选弹层内容 */
.fp-label {
  font-size: 10px; font-weight: 700; letter-spacing: 1px;
  text-transform: uppercase; color: #9CA3AF; margin-bottom: 6px;
}
.fp-label.mt { margin-top: 14px; }
.fp-item {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 8px; border-radius: 6px; cursor: pointer; font-size: 13px; color: #374151;
}
.fp-item:hover { background: #F7F5F0; }
.fp-item.on { background: rgba(43,95,84,0.08); color: #2B5F54; font-weight: 600; }
.fp-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; background: #9CA3AF; }
.fp-dot.yellow { background: #F59E0B; }
.fp-dot.blue { background: #3B82F6; }
.fp-dot.green { background: #10B981; }
.fp-dot.gray { background: #9CA3AF; }
.fp-dot.high { background: #EF4444; }
.fp-name { flex: 1; }
.fp-check { font-size: 12px; color: #2B5F54; font-weight: 700; }
.fp-foot { margin-top: 12px; padding-top: 10px; border-top: 1px solid #F3F4F6; }
.fp-clear {
  width: 100%; padding: 6px 0; border-radius: 6px;
  border: 1px solid #E5E7EB; background: #fff; color: #6B7280;
  font-size: 12px; cursor: pointer; transition: all 0.12s;
}
.fp-clear:hover:not(:disabled) { border-color: #2B5F54; color: #2B5F54; }
.fp-clear:disabled { opacity: 0.5; cursor: not-allowed; }

/* ===== 看板 ===== */
.board {
  flex: 1; display: flex; gap: 14px;
  padding: 18px 20px; overflow-x: auto; overflow-y: hidden;
}
.col { display: flex; flex-direction: column; flex: 1; min-width: 240px; }
.col.narrow { flex: 0.4; min-width: 180px; max-width: 220px; }
.col-head {
  display: flex; align-items: center; gap: 8px;
  padding: 0 4px 12px; flex-shrink: 0;
}
.col-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.col-dot.yellow { background: #F59E0B; }
.col-dot.blue { background: #3B82F6; }
.col-dot.green { background: #10B981; }
.col-dot.gray { background: #9CA3AF; }
.col-name {
  font-size: 12px; font-weight: 700; color: #374151;
  text-transform: uppercase; letter-spacing: 0.6px;
}
.col-badge {
  font-size: 10px; font-weight: 700; color: #9CA3AF;
  background: #F3F4F6; padding: 2px 7px; border-radius: 8px; margin-left: 2px;
}
.col-body { flex: 1; overflow-y: auto; padding-right: 2px; }
.col-body::-webkit-scrollbar { width: 3px; }
.col-body::-webkit-scrollbar-thumb { background: #D1D5DB; border-radius: 2px; }
.col-empty {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 10px; border: 1px dashed #E5E7EB; border-radius: 10px; color: #C4C8CE;
}
.col-empty svg { width: 26px; height: 26px; }
.col-empty p { font-size: 12px; margin: 0; }

.board-hint {
  flex: 1; display: flex; align-items: center; justify-content: center; color: #9CA3AF; font-size: 13px;
}
.board-hint p { margin: 0; }

/* ===== 卡片 ===== */
.card {
  background: #fff; border: 1px solid #E5E7EB;
  border-radius: 10px; padding: 14px 16px; margin-bottom: 8px;
  cursor: pointer; transition: all 0.18s; position: relative;
}
.card:hover {
  border-color: #2B5F54;
  box-shadow: 0 4px 12px rgba(0,0,0,0.06);
  transform: translateY(-2px);
}
.card.is-done .card-title { color: #6B7280; }
.stripe {
  position: absolute; left: 0; top: 12px; bottom: 12px;
  width: 3px; border-radius: 0 2px 2px 0;
}
.stripe.high { background: #EF4444; }
.stripe.med { background: #F59E0B; }
.stripe.low { background: #9CA3AF; }
.card-row1 {
  display: flex; align-items: flex-start; gap: 8px; margin-bottom: 10px;
}
.card-title {
  font-size: 13.5px; font-weight: 600; color: #111827;
  line-height: 1.5; flex: 1;
}
.card-foot {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
}
.card-tags { display: flex; gap: 4px; flex-wrap: wrap; }
.tag {
  padding: 2px 8px; border-radius: 5px;
  font-size: 10px; font-weight: 600;
  background: #ECFDF5; color: #059669;
}
.tag.danger { background: #FEF2F2; color: #DC2626; }
.tag.info { background: #EFF6FF; color: #2563EB; }
.card-meta { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.card-who { font-size: 11px; color: #6B7280; max-width: 64px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.card-date {
  font-size: 11px; color: #9CA3AF;
  display: flex; align-items: center; gap: 3px;
}
.card-date svg { width: 12px; height: 12px; }
.card-date.late { color: #EF4444; font-weight: 600; }
.card-av {
  width: 22px; height: 22px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 10px; font-weight: 700; color: #fff;
}
.card-av.c1 { background: #F59E0B; }
.card-av.c2 { background: #10B981; }
.card-av.c3 { background: #3B82F6; }
.card-av.c4 { background: #8B5CF6; }
.card-av.c5 { background: #6B7280; }

/* ===== 列表视图 ===== */
.list-wrap { flex: 1; overflow: auto; padding: 16px 20px; }
.list-table {
  width: 100%; border-collapse: separate; border-spacing: 0;
  background: #fff; border: 1px solid #E5E7EB; border-radius: 10px; overflow: hidden;
}
.list-table thead th {
  position: sticky; top: 0; z-index: 1;
  background: #FAFAF7; border-bottom: 1px solid #E5E7EB;
  font-size: 11px; font-weight: 700; letter-spacing: 0.4px; color: #6B7280;
  text-align: left; padding: 10px 14px; white-space: nowrap;
}
.list-table th.sortable { cursor: pointer; user-select: none; }
.list-table th.sortable:hover { color: #2B5F54; }
.list-table th.sorted { color: #2B5F54; }
.list-table th .arw { font-size: 9px; margin-left: 3px; }
.list-table tbody td {
  border-bottom: 1px solid #F3F4F6; padding: 11px 14px;
  font-size: 13px; color: #374151; vertical-align: middle;
}
.list-table tbody tr { cursor: pointer; transition: background 0.12s; }
.list-table tbody tr:hover td { background: rgba(43,95,84,0.04); }
.list-table tbody tr:last-child td { border-bottom: none; }
.w90 { width: 90px; }
.w120 { width: 120px; }
.w140 { width: 140px; }
.w150 { width: 150px; }
.w170 { width: 170px; }
.cell-title { min-width: 220px; }
.cell-title .tt { font-weight: 600; color: #111827; }
.cell-sub { color: #6B7280; font-size: 12.5px; }
.w110 { width: 110px; }
.cell-who { white-space: nowrap; }
.who-pill {
  display: inline-block; padding: 1px 8px; border-radius: 20px;
  font-size: 11px; color: #6B7280; background: #F3F4F6;
  max-width: 96px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.who-pill.self { color: #2B5F54; background: rgba(43,95,84,0.1); font-weight: 600; }
.stripe-inline {
  display: inline-block; width: 3px; height: 12px; border-radius: 2px;
  margin-right: 8px; vertical-align: -1px; background: #F59E0B;
}
.stripe-inline.high { background: #EF4444; }
.stripe-inline.med { background: #F59E0B; }
.st-pill {
  display: inline-block; padding: 2px 9px; border-radius: 20px;
  font-size: 11px; font-weight: 600;
}
.st-pill.yellow { background: #FFFBEB; color: #B45309; }
.st-pill.blue { background: #EFF6FF; color: #1D4ED8; }
.st-pill.green { background: #ECFDF5; color: #047857; }
.st-pill.gray { background: #F3F4F6; color: #6B7280; }
.late { color: #DC2626 !important; font-weight: 600; }
.soon { color: #B45309; }
.list-empty {
  padding: 48px 0; display: flex; flex-direction: column; align-items: center;
  gap: 10px; color: #C4C8CE;
}
.list-empty svg { width: 28px; height: 28px; }
.list-empty p { margin: 0; font-size: 13px; }
.list-empty .fp-clear { width: auto; padding: 5px 14px; }

/* ===== 新建任务 / 详情弹窗 ===== */
.fd-grid { display: flex; gap: 12px; }
.fd-grid > * { flex: 1; min-width: 0; }
.fd-hint { font-size: 11px; color: #B45309; margin-top: 4px; line-height: 1.5; }
.fd-warn {
  font-size: 12px; color: #B45309; background: #FFFBEB;
  border: 1px solid #FDE68A; border-radius: 6px; padding: 8px 10px; line-height: 1.6;
}
.dd-body h3 { font-size: 15px; margin: 0 0 12px; color: #111827; line-height: 1.5; }
.dd-row { display: flex; align-items: center; gap: 10px; padding: 5px 0; border-bottom: 1px dashed #F3F4F6; }
.dd-row > span { width: 72px; font-size: 12px; color: #9CA3AF; flex-shrink: 0; }
.dd-row > b { font-size: 13px; font-weight: 600; color: #374151; }
/* 详情弹窗里可直接改状态，宽度收一下别把行撑破 */
.dd-status { width: 150px; }
.dd-row > .dd-status { margin-left: 0; }
.dd-content {
  margin-top: 12px; padding: 10px 12px; background: #F7F5F0; border-radius: 8px;
  font-size: 13px; color: #374151; line-height: 1.7; white-space: pre-wrap; word-break: break-word;
}
.dd-tip { margin-top: 12px; font-size: 11.5px; color: #9CA3AF; }

/* 窄屏：左侧面板收起为一行，保证看板列可用宽度 */
@media (max-width: 1100px) {
  .side-panel { width: 200px; }
  .tb-search { width: 150px; }
}
</style>