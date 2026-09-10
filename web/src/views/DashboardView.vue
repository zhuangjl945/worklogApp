<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { workRecordStatsForDashboard, workRecordStatsByExpenseType, workRecordPage } from '../api/work'
import { contractExpiring, paymentPlanExpiring } from '../api/contract'
import { me } from '../api/auth'

const props = defineProps({
  user: { type: Object, default: null },
  loading: { type: Boolean, default: true },
  errorMsg: { type: String, default: '' }
})

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']
// 石墨色调色板：从深到浅
const BAR_COLORS = ['#171717', '#404040', '#737373', '#A3A3A3', '#D4D4D4']

const displayName = computed(() => props.user?.realName || props.user?.username || '用户')
const router = useRouter()

const now = new Date()
const todayLabel = `${now.getFullYear()}年${now.getMonth() + 1}月${now.getDate()}日 · 周${WEEKDAYS[now.getDay()]}`

const tasksLoading = ref(false)
const tasksError = ref('')
const dueWork = ref([])
const doingTasks = ref([])

const expiringContracts = ref([])
const expiringPayments = ref([])
const remindersLoading = ref(false)
const remindersError = ref('')
const myDeptId = ref(null)

const categoryRows = ref([])
const expenseRows = ref([])
const categoryChartLoading = ref(false)
const categoryChartError = ref('')
const expenseChartLoading = ref(false)
const expenseChartError = ref('')
const currentYear = new Date().getFullYear()

function isWorkOverdue(t) {
  if (!t?.endTime) return false
  const d = parseDate(t.endTime)
  if (!d) return false
  return d.getTime() < startOfDay(new Date()).getTime()
}

function isWorkDueToday(t) {
  if (!t?.endTime) return false
  const d = parseDate(t.endTime)
  if (!d) return false
  return d.getTime() === startOfDay(new Date()).getTime()
}

function pad(n) { return String(n).padStart(2, '0') }

function formatDateTime(d) {
  const x = d instanceof Date ? d : new Date(d)
  return `${x.getFullYear()}-${pad(x.getMonth() + 1)}-${pad(x.getDate())} ${pad(x.getHours())}:${pad(x.getMinutes())}:${pad(x.getSeconds())}`
}

function dueBefore30Days() {
  const x = new Date()
  x.setDate(x.getDate() + 30)
  x.setHours(23, 59, 59, 0)
  return formatDateTime(x)
}

function doingDeadlineLabel(t) {
  if (!t?.endTime) return t.content || '未定期'
  const rel = relativeDay(t.endTime)
  const date = formatShortDate(t.endTime)
  return rel ? `${date} · ${rel}` : date
}

const overdueCount = computed(() => dueWork.value.filter(t => isWorkOverdue(t)).length)

const hasDueSoon = computed(
  () => expiringContracts.value.length > 0 || expiringPayments.value.length > 0 || dueWork.value.some(t => isWorkOverdue(t) || isWorkDueToday(t))
)

const ledgerLoading = computed(() => remindersLoading.value || tasksLoading.value)
const ledgerError = computed(() => remindersError.value || tasksError.value)

function startOfDay(d) {
  const x = new Date(d)
  x.setHours(0, 0, 0, 0)
  return x
}

function parseDate(value) {
  if (!value) return null
  const s = String(value).slice(0, 10)
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s)
  if (!m) return null
  return startOfDay(new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])))
}

function formatMoney(n) {
  const v = Number(n)
  if (!Number.isFinite(v)) return '0'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })
}

function relativeDay(dateStr) {
  const d = parseDate(dateStr)
  if (!d) return ''
  const today = startOfDay(new Date())
  const diff = Math.round((d.getTime() - today.getTime()) / 86400000)
  if (diff < 0) return `已过 ${Math.abs(diff)} 天`
  if (diff === 0) return '今天'
  if (diff === 1) return '明天'
  return `${diff} 天后`
}

function formatShortDate(dateStr) {
  const d = parseDate(dateStr)
  if (!d) return '待处理'
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${m}-${day}`
}

function goWorkRecords(t) {
  router.push({ path: '/work-records', query: { focusId: String(t?.id ?? '') } })
}

function goWriteWork() {
  router.push({ path: '/work-records' })
}

const ledgerItems = computed(() => {
  const items = []
  for (const c of expiringContracts.value) {
    items.push({
      key: `c-${c.id}`, kind: 'contract', kindLabel: '合同',
      title: c.contractName || '未命名合同', meta: c.contractNo || '',
      date: c.endDate, urgent: true,
      go: () => router.push(`/contract/detail/${c.id}`)
    })
  }
  for (const p of expiringPayments.value) {
    items.push({
      key: `p-${p.id}`, kind: 'payment', kindLabel: '付款',
      title: `${p.planNo || '付款计划'} · ¥${formatMoney(p.planAmount)}`,
      meta: p.conditionDesc || (p.contractId ? `合同 #${p.contractId}` : ''),
      date: p.planDate, urgent: true,
      go: () => router.push(`/contract/detail/${p.contractId}`)
    })
  }
  for (const t of dueWork.value) {
    const overdue = isWorkOverdue(t)
    items.push({
      key: `w-${t.id}`, kind: 'work', kindLabel: overdue ? '逾期' : '工作',
      title: t.title || '未命名任务', meta: t.content || '',
      date: t.endTime || null, urgent: overdue || isWorkDueToday(t),
      go: () => goWorkRecords(t)
    })
  }
  items.sort((a, b) => {
    const da = parseDate(a.date)
    const db = parseDate(b.date)
    if (!da && !db) return 0
    if (!da) return -1
    if (!db) return 1
    return da.getTime() - db.getTime()
  })
  return items
})

const ledgerCountLabel = computed(() => {
  const n = ledgerItems.value.length
  const parts = []
  if (expiringContracts.value.length) parts.push(`合同 ${expiringContracts.value.length}`)
  if (expiringPayments.value.length) parts.push(`付款 ${expiringPayments.value.length}`)
  const overdueN = dueWork.value.filter(t => isWorkOverdue(t)).length
  if (overdueN) parts.push(`逾期 ${overdueN}`)
  if (dueWork.value.length) parts.push(`工作 ${dueWork.value.length}`)
  return n ? parts.join(' · ') : '暂无待办'
})

function barWidth(value, rows) {
  const max = Math.max(...rows.map(r => Number(r.value) || 0), 1)
  return `${Math.max(6, (Number(value) / max) * 100)}%`
}

async function loadTasks() {
  tasksLoading.value = true
  tasksError.value = ''
  try {
    const [dueResp, doingResp] = await Promise.all([
      workRecordPage({ page: 1, size: 50, statusIds: '1,2', dueBefore: dueBefore30Days() }),
      workRecordPage({ page: 1, size: 10, statusIds: '2' })
    ])
    dueWork.value = dueResp.data?.records || []
    const doing = doingResp.data?.records || []
    doing.sort((a, b) => {
      const da = parseDate(a.endTime)
      const db = parseDate(b.endTime)
      if (!da && !db) return 0
      if (!da) return 1
      if (!db) return -1
      return da.getTime() - db.getTime()
    })
    doingTasks.value = doing
  } catch (e) {
    tasksError.value = e?.message || '待办加载失败'
  } finally {
    tasksLoading.value = false
  }
}

async function loadReminders() {
  remindersLoading.value = true
  remindersError.value = ''
  try {
    const deptId = myDeptId.value ?? props.user?.deptId ?? undefined
    const [cResp, pResp] = await Promise.all([
      contractExpiring({ days: 30, status: 30, deptId }),
      paymentPlanExpiring({ days: 30, status: 10, deptId })
    ])
    expiringContracts.value = cResp.data || []
    expiringPayments.value = pResp.data || []
  } catch (e) {
    remindersError.value = e?.message || '到期提醒加载失败'
  } finally {
    remindersLoading.value = false
  }
}

function yearRange() {
  return { start: `${currentYear}-01-01 00:00:00`, end: `${currentYear}-12-31 23:59:59` }
}

async function loadCategoryChart() {
  categoryChartLoading.value = true
  categoryChartError.value = ''
  try {
    const range = yearRange()
    const resp = await workRecordStatsForDashboard({ startTimeFrom: range.start, startTimeTo: range.end })
    categoryRows.value = (resp.data || []).map(x => ({ name: x.categoryName || '未分类', value: x.totalCount || 0 }))
  } catch (e) {
    categoryChartError.value = e?.message || '工作量统计加载失败'
  } finally {
    categoryChartLoading.value = false
  }
}

async function loadExpenseChart() {
  expenseChartLoading.value = true
  expenseChartError.value = ''
  try {
    const range = yearRange()
    const resp = await workRecordStatsByExpenseType({ expenseTimeFrom: range.start, expenseTimeTo: range.end })
    expenseRows.value = (resp.data || []).map(x => ({ name: x.expenseType || '未分类', value: Number(x.totalAmount) || 0 }))
  } catch (e) {
    expenseChartError.value = e?.message || '费用统计加载失败'
  } finally {
    expenseChartLoading.value = false
  }
}

onMounted(async () => {
  try {
    const resp = await me()
    myDeptId.value = resp?.data?.deptId ?? null
  } catch {
    myDeptId.value = null
  }
  await Promise.all([loadTasks(), loadReminders(), loadCategoryChart(), loadExpenseChart()])
})
</script>

<template>
  <div class="page">
    <!-- 顶部欢迎栏 -->
    <header class="hero">
      <div class="heroLeft">
        <h1 class="heroTitle">{{ displayName }}，你好</h1>
        <p class="heroDate">{{ todayLabel }}</p>
      </div>
      <button class="writeBtn" type="button" @click="goWriteWork">+ 记一条工作</button>
    </header>

    <!-- 统计卡片 -->
    <div class="statRow">
      <div class="statCard">
        <div class="statNum">{{ ledgerItems.length }}</div>
        <div class="statLabel">30天内待办</div>
        <div class="statSub" v-if="hasDueSoon">
          <span class="statDot urgent"></span> 需要关注
        </div>
        <div class="statSub" v-else>一切正常</div>
      </div>
      <div class="statCard">
        <div class="statNum">{{ overdueCount }}</div>
        <div class="statLabel">已逾期</div>
        <div class="statSub" :class="{ 'danger-text': overdueCount > 0 }">
          {{ overdueCount > 0 ? '需立即处理' : '无逾期' }}
        </div>
      </div>
      <div class="statCard">
        <div class="statNum">{{ doingTasks.length }}</div>
        <div class="statLabel">进行中</div>
        <div class="statSub">当前任务队列</div>
      </div>
      <div class="statCard">
        <div class="statNum">{{ expiringContracts.length }}</div>
        <div class="statLabel">即将到期合同</div>
        <div class="statSub">30天内</div>
      </div>
    </div>

    <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

    <!-- 主内容网格 -->
    <div class="grid">
      <!-- 台账：待办提醒 -->
      <section class="card ledgerCard" v-loading="ledgerLoading">
        <div class="cardHead">
          <div>
            <div class="cardKicker">未来 30 天 · 含逾期</div>
            <h2 class="cardTitle">到期台账</h2>
          </div>
          <span class="cardMeta">{{ ledgerCountLabel }}</span>
        </div>

        <p v-if="ledgerError" class="error">{{ ledgerError }}</p>

        <div v-else-if="ledgerItems.length === 0" class="empty">
          <div class="emptyTitle">暂无待办事项</div>
          <div class="emptyHint">30 天内没有到期合同、付款或已填截止日期的工作。</div>
          <button class="writeBtn ghost" type="button" @click="goWriteWork">记一条工作</button>
        </div>

        <ol v-else class="ledgerList">
          <li
            v-for="item in ledgerItems" :key="item.key"
            class="ledgerRow" :class="{ urgent: item.urgent }"
            @click="item.go" @keyup.enter="item.go" tabindex="0" role="button"
          >
            <div class="ledgerDate">
              <span class="d">{{ formatShortDate(item.date) }}</span>
              <span class="rel">{{ item.date ? relativeDay(item.date) : '未定期' }}</span>
            </div>
            <div class="ledgerBody">
              <div class="ledgerLine1">
                <span class="kind" :class="{ 'kind-urgent': item.urgent }">{{ item.kindLabel }}</span>
                <span class="ledgerTitle">{{ item.title }}</span>
              </div>
              <div v-if="item.meta" class="ledgerMeta">{{ item.meta }}</div>
            </div>
          </li>
        </ol>
      </section>

      <!-- 进行中队列 -->
      <section class="card doingCard">
        <div class="cardHead">
          <div>
            <div class="cardKicker">进行中</div>
            <h2 class="cardTitle">任务队列</h2>
          </div>
          <span class="cardMeta">{{ doingTasks.length }} 条</span>
        </div>
        <div v-if="doingTasks.length === 0" class="empty compact">
          <div class="emptyTitle">没有进行中的工作</div>
          <div class="emptyHint">把待办的状态改为"进行中"即可出现在这里。</div>
        </div>
        <ul v-else class="doingList">
          <li
            v-for="t in doingTasks" :key="t.id"
            class="doingRow" :class="{ overdue: isWorkOverdue(t) }"
            tabindex="0" role="button"
            @click="goWorkRecords(t)" @keyup.enter="goWorkRecords(t)"
          >
            <div class="doingTitle">{{ t.title }}</div>
            <div class="doingMeta">{{ doingDeadlineLabel(t) }}</div>
          </li>
        </ul>
      </section>

      <!-- 分类工作量 -->
      <section class="card chartCard">
        <div class="cardHead">
          <div>
            <div class="cardKicker">{{ currentYear }} 年</div>
            <h2 class="cardTitle">分类工作量</h2>
          </div>
        </div>
        <p v-if="categoryChartError" class="error">{{ categoryChartError }}</p>
        <div v-else v-loading="categoryChartLoading">
          <div v-if="!categoryRows.length" class="empty compact">
            <div class="emptyHint">今年有工作记录后会自动统计。</div>
          </div>
          <ul v-else class="bars">
            <li v-for="(row, i) in categoryRows" :key="row.name" class="barRow">
              <div class="barLabel">{{ row.name }}</div>
              <div class="barTrack">
                <div class="barFill" :style="{ width: barWidth(row.value, categoryRows), background: BAR_COLORS[i % BAR_COLORS.length] }" />
              </div>
              <div class="barValue">{{ row.value }}</div>
            </li>
          </ul>
        </div>

        <div class="chartDivider" />

        <div class="cardHead sub">
          <div>
            <div class="cardKicker">{{ currentYear }} 年</div>
            <h2 class="cardTitle">费用类型</h2>
          </div>
        </div>
        <p v-if="expenseChartError" class="error">{{ expenseChartError }}</p>
        <div v-else v-loading="expenseChartLoading">
          <div v-if="!expenseRows.length" class="empty compact">
            <div class="emptyHint">在工作记录里登记费用后会出现在这里。</div>
          </div>
          <ul v-else class="bars">
            <li v-for="(row, i) in expenseRows" :key="row.name" class="barRow">
              <div class="barLabel">{{ row.name }}</div>
              <div class="barTrack">
                <div class="barFill" :style="{ width: barWidth(row.value, expenseRows), background: BAR_COLORS[i % BAR_COLORS.length] }" />
              </div>
              <div class="barValue money">¥{{ formatMoney(row.value) }}</div>
            </li>
          </ul>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.page {
  padding: 0;
  max-width: 100%;
}

/* 顶部欢迎 */
.hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 20px;
  gap: 16px;
}

.heroTitle {
  font-size: 22px;
  font-weight: 800;
  color: var(--g-text);
  letter-spacing: -0.02em;
  margin: 0;
}

.heroDate {
  font-size: 13px;
  color: var(--g-text-muted);
  margin: 4px 0 0;
}

.writeBtn {
  background: var(--g-text);
  color: #fff;
  border: 0;
  padding: 8px 18px;
  font-size: 13px;
  font-weight: 600;
  border-radius: var(--g-radius);
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s;
}

.writeBtn:hover {
  background: var(--g-accent-hover);
}

.writeBtn.ghost {
  background: transparent;
  color: var(--g-text);
  border: 1px solid var(--g-border-strong);
  margin-top: 12px;
}

.writeBtn.ghost:hover {
  background: var(--g-bg-muted);
}

/* 统计卡片行 */
.statRow {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}

.statCard {
  background: var(--g-bg);
  border: 1px solid var(--g-border);
  border-radius: var(--g-radius-md);
  padding: 16px 18px;
}

.statNum {
  font-size: 28px;
  font-weight: 800;
  color: var(--g-text);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.03em;
  line-height: 1;
}

.statLabel {
  font-size: 12px;
  color: var(--g-text-muted);
  margin-top: 6px;
  font-weight: 500;
}

.statSub {
  font-size: 11px;
  color: var(--g-text-faint);
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.statDot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--g-text-faint);
}

.statDot.urgent {
  background: var(--g-danger);
}

.danger-text {
  color: var(--g-danger) !important;
  font-weight: 600;
}

/* 网格布局 */
.grid {
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  gap: 14px;
}

.ledgerCard { grid-column: span 12; }
.doingCard { grid-column: span 5; }
.chartCard { grid-column: span 7; }

/* 卡片通用 */
.card {
  background: var(--g-bg);
  border: 1px solid var(--g-border);
  border-radius: var(--g-radius-md);
  padding: 18px 20px 20px;
}

.cardHead {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  margin-bottom: 14px;
  border-bottom: 1px solid var(--g-border);
}

.cardHead.sub {
  padding-top: 14px;
  margin-top: 8px;
  padding-bottom: 10px;
  margin-bottom: 10px;
}

.cardKicker {
  font-size: 11px;
  color: var(--g-text-faint);
  letter-spacing: 0.06em;
  font-weight: 500;
}

.cardTitle {
  font-size: 15px;
  font-weight: 700;
  color: var(--g-text);
  margin: 2px 0 0;
  letter-spacing: -0.01em;
}

.cardMeta {
  font-size: 12px;
  color: var(--g-text-faint);
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

/* 台账列表 */
.ledgerList {
  list-style: none;
  margin: 0;
  padding: 0;
}

.ledgerRow {
  display: grid;
  grid-template-columns: 80px 1fr;
  gap: 14px;
  padding: 10px 8px;
  border-bottom: 1px solid var(--g-border);
  cursor: pointer;
  border-radius: var(--g-radius-sm);
  transition: background 0.1s;
}

.ledgerRow:last-child { border-bottom: 0; }

.ledgerRow:hover { background: var(--g-bg-subtle); }

.ledgerRow.urgent:hover { background: var(--g-danger-bg); }

.ledgerDate {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ledgerDate .d {
  font-family: var(--g-font-mono);
  font-size: 13px;
  font-weight: 600;
  color: var(--g-text);
}

.ledgerDate .rel {
  font-size: 11px;
  color: var(--g-text-faint);
}

.ledgerRow.urgent .ledgerDate .d {
  color: var(--g-danger);
}

.ledgerLine1 {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.kind {
  flex: 0 0 auto;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--g-text-muted);
  background: var(--g-bg-muted);
  padding: 2px 6px;
  border-radius: 3px;
}

.kind-urgent {
  background: var(--g-danger-bg);
  color: var(--g-danger);
}

.ledgerTitle {
  font-size: 13px;
  font-weight: 600;
  color: var(--g-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ledgerMeta {
  margin-top: 3px;
  font-size: 12px;
  color: var(--g-text-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 进行中队列 */
.doingList {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.doingRow {
  padding: 10px 12px;
  border-left: 3px solid var(--g-border-strong);
  background: var(--g-bg-subtle);
  cursor: pointer;
  border-radius: 0 var(--g-radius-sm) var(--g-radius-sm) 0;
  transition: all 0.12s;
}

.doingRow:hover { background: var(--g-bg-muted); border-left-color: var(--g-text-muted); }

.doingRow.overdue { border-left-color: var(--g-danger); }

.doingTitle {
  font-size: 13px;
  font-weight: 600;
  color: var(--g-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doingMeta {
  margin-top: 3px;
  font-size: 12px;
  color: var(--g-text-faint);
}

.doingRow.overdue .doingMeta {
  color: var(--g-danger);
  font-weight: 500;
}

/* 条形图 */
.bars {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.barRow {
  display: grid;
  grid-template-columns: 90px 1fr 64px;
  gap: 10px;
  align-items: center;
}

.barLabel {
  font-size: 12px;
  color: var(--g-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.barTrack {
  height: 6px;
  background: var(--g-bg-muted);
  border-radius: 3px;
}

.barFill {
  height: 100%;
  min-width: 4px;
  border-radius: 3px;
  transition: width 0.3s ease;
}

.barValue {
  font-family: var(--g-font-mono);
  font-size: 12px;
  text-align: right;
  color: var(--g-text-secondary);
  font-variant-numeric: tabular-nums;
}

.chartDivider {
  height: 1px;
  background: var(--g-border);
  margin: 16px 0 4px;
}

/* 空态 */
.empty {
  padding: 28px 8px;
  text-align: left;
}

.empty.compact { padding: 12px 0 8px; }

.emptyTitle {
  font-size: 13px;
  font-weight: 600;
  color: var(--g-text);
}

.emptyHint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--g-text-faint);
  line-height: 1.5;
}

.error {
  color: var(--g-danger);
  font-size: 13px;
  margin: 8px 0;
}

/* 响应式 */
@media (max-width: 1100px) {
  .doingCard, .chartCard { grid-column: span 12; }
  .statRow { grid-template-columns: repeat(2, 1fr); }
}

@media (max-width: 720px) {
  .hero { flex-direction: column; align-items: flex-start; }
  .statRow { grid-template-columns: repeat(2, 1fr); }
  .ledgerRow { grid-template-columns: 64px 1fr; }
  .barRow { grid-template-columns: 1fr 56px; }
  .barLabel { grid-column: 1 / -1; }
}
</style>
