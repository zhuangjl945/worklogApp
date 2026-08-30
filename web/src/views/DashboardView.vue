<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { workRecordStatsForDashboard, workRecordStatsByExpenseType, workRecordPage } from '../api/work'
import { contractExpiring, paymentPlanExpiring } from '../api/contract'
import { me } from '../api/auth'

const props = defineProps({
  user: {
    type: Object,
    default: null
  },
  loading: {
    type: Boolean,
    default: true
  },
  errorMsg: {
    type: String,
    default: ''
  }
})

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']
const INK_BARS = ['#1B3A4B', '#2F5363', '#4A6A78', '#5C6B73', '#8A969C']

const displayName = computed(() => props.user?.realName || props.user?.username || '用户')
const router = useRouter()

const now = new Date()
const stampDay = String(now.getDate())
const stampMonth = `${now.getMonth() + 1}月`
const stampWeek = `周${WEEKDAYS[now.getDay()]}`
const stampYear = String(now.getFullYear())

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

function pad(n) {
  return String(n).padStart(2, '0')
}

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

const hasDueSoon = computed(
  () =>
    expiringContracts.value.length > 0 ||
    expiringPayments.value.length > 0 ||
    dueWork.value.some((t) => isWorkOverdue(t) || isWorkDueToday(t))
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
      key: `c-${c.id}`,
      kind: 'contract',
      kindLabel: '合同',
      title: c.contractName || '未命名合同',
      meta: c.contractNo || '',
      date: c.endDate,
      urgent: true,
      go: () => router.push(`/contract/detail/${c.id}`)
    })
  }
  for (const p of expiringPayments.value) {
    items.push({
      key: `p-${p.id}`,
      kind: 'payment',
      kindLabel: '付款',
      title: `${p.planNo || '付款计划'} · ¥${formatMoney(p.planAmount)}`,
      meta: p.conditionDesc || (p.contractId ? `合同 #${p.contractId}` : ''),
      date: p.planDate,
      urgent: true,
      go: () => router.push(`/contract/detail/${p.contractId}`)
    })
  }
  for (const t of dueWork.value) {
    const overdue = isWorkOverdue(t)
    items.push({
      key: `w-${t.id}`,
      kind: 'work',
      kindLabel: overdue ? '逾期' : '工作',
      title: t.title || '未命名任务',
      meta: t.content || '',
      date: t.endTime || null,
      urgent: overdue || isWorkDueToday(t),
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
  const overdueN = dueWork.value.filter((t) => isWorkOverdue(t)).length
  if (overdueN) parts.push(`逾期 ${overdueN}`)
  if (dueWork.value.length) parts.push(`工作 ${dueWork.value.length}`)
  return n ? parts.join(' · ') : '暂无待办'
})

function barWidth(value, rows) {
  const max = Math.max(...rows.map((r) => Number(r.value) || 0), 1)
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
    tasksError.value = e?.message || '待办加载失败，请刷新后重试'
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
    remindersError.value = e?.message || '到期提醒加载失败，请刷新后重试'
  } finally {
    remindersLoading.value = false
  }
}

function yearRange() {
  return {
    start: `${currentYear}-01-01 00:00:00`,
    end: `${currentYear}-12-31 23:59:59`
  }
}

async function loadCategoryChart() {
  categoryChartLoading.value = true
  categoryChartError.value = ''
  try {
    const range = yearRange()
    const resp = await workRecordStatsForDashboard({
      startTimeFrom: range.start,
      startTimeTo: range.end
    })
    categoryRows.value = (resp.data || []).map((x) => ({
      name: x.categoryName || '未分类',
      value: x.totalCount || 0
    }))
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
    const resp = await workRecordStatsByExpenseType({
      expenseTimeFrom: range.start,
      expenseTimeTo: range.end
    })
    expenseRows.value = (resp.data || []).map((x) => ({
      name: x.expenseType || '未分类',
      value: Number(x.totalAmount) || 0
    }))
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

  await Promise.all([
    loadTasks(),
    loadReminders(),
    loadCategoryChart(),
    loadExpenseChart()
  ])
})
</script>

<template>
  <div class="page">
    <header class="hero">
      <div class="stamp" :class="{ urgent: hasDueSoon }" aria-hidden="true">
        <div class="stampMonth">{{ stampMonth }}</div>
        <div class="stampDay">{{ stampDay }}</div>
        <div class="stampFoot">{{ stampWeek }} · {{ stampYear }}</div>
      </div>
      <div class="heroCopy">
        <div class="heroKicker">值班台账</div>
        <h1 class="heroTitle">{{ displayName }}，先处理眼前的事</h1>
        <p class="heroSub">未来 30 天到期项与待办写在同一本账上。有红印就先看合同、付款和逾期工作。</p>
        <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
      </div>
      <button class="writeBtn" type="button" @click="goWriteWork">记一条工作</button>
    </header>

    <div class="grid">
      <section class="sheet ledger" v-loading="ledgerLoading">
        <div class="sheetHead">
          <div>
            <div class="sheetKicker">未来 30 天 · 含逾期</div>
            <h2 class="sheetTitle">台账</h2>
          </div>
          <div class="sheetMeta">{{ ledgerCountLabel }}</div>
        </div>

        <p v-if="ledgerError" class="error">{{ ledgerError }}</p>

        <div v-else-if="ledgerItems.length === 0" class="empty">
          <div class="emptyTitle">这本账是空的</div>
          <div class="emptyHint">30 天内没有到期合同、付款或已填截止日期的工作。有事要跟，去记一条并写上截止日期。</div>
          <button class="writeBtn ghost" type="button" @click="goWriteWork">记一条工作</button>
        </div>

        <ol v-else class="ledgerList">
          <li
            v-for="item in ledgerItems"
            :key="item.key"
            class="ledgerRow"
            :class="[item.kind, { urgent: item.urgent }]"
            @click="item.go"
            @keyup.enter="item.go"
            tabindex="0"
            role="button"
          >
            <div class="ledgerDate">
              <span class="d">{{ formatShortDate(item.date) }}</span>
              <span class="rel">{{ item.date ? relativeDay(item.date) : '未定期' }}</span>
            </div>
            <div class="ledgerBody">
              <div class="ledgerLine1">
                <span class="kind">{{ item.kindLabel }}</span>
                <span class="ledgerTitle">{{ item.title }}</span>
              </div>
              <div v-if="item.meta" class="ledgerMeta">{{ item.meta }}</div>
            </div>
          </li>
        </ol>
      </section>

      <section class="sheet doing">
        <div class="sheetHead">
          <div>
            <div class="sheetKicker">进行中</div>
            <h2 class="sheetTitle">队列</h2>
          </div>
          <div class="sheetMeta">{{ doingTasks.length }} 条</div>
        </div>
        <div v-if="doingTasks.length === 0" class="empty compact">
          <div class="emptyTitle">没有进行中的工作</div>
          <div class="emptyHint">从待办里点开一条，改成进行中即可出现在这里。</div>
        </div>
        <ul v-else class="doingList">
          <li
            v-for="t in doingTasks"
            :key="t.id"
            class="doingRow"
            :class="{ overdue: isWorkOverdue(t) }"
            tabindex="0"
            role="button"
            @click="goWorkRecords(t)"
            @keyup.enter="goWorkRecords(t)"
          >
            <div class="doingTitle">{{ t.title }}</div>
            <div class="doingMeta">{{ doingDeadlineLabel(t) }}</div>
          </li>
        </ul>
      </section>

      <section class="sheet charts">
        <div class="sheetHead">
          <div>
            <div class="sheetKicker">{{ currentYear }} 年</div>
            <h2 class="sheetTitle">分类工作量</h2>
          </div>
        </div>
        <p v-if="categoryChartError" class="error">{{ categoryChartError }}</p>
        <div v-else v-loading="categoryChartLoading">
          <div v-if="!categoryRows.length" class="empty compact">
            <div class="emptyTitle">还没有分类数据</div>
            <div class="emptyHint">今年有工作记录后，条会按分类铺开。</div>
          </div>
          <ul v-else class="bars">
            <li v-for="(row, i) in categoryRows" :key="row.name" class="barRow">
              <div class="barLabel">{{ row.name }}</div>
              <div class="barTrack">
                <div class="barFill" :style="{ width: barWidth(row.value, categoryRows), background: INK_BARS[i % INK_BARS.length] }" />
              </div>
              <div class="barValue">{{ row.value }}</div>
            </li>
          </ul>
        </div>

        <div class="chartSplit" />

        <div class="sheetHead sub">
          <div>
            <div class="sheetKicker">{{ currentYear }} 年</div>
            <h2 class="sheetTitle">费用类型</h2>
          </div>
        </div>
        <p v-if="expenseChartError" class="error">{{ expenseChartError }}</p>
        <div v-else v-loading="expenseChartLoading">
          <div v-if="!expenseRows.length" class="empty compact">
            <div class="emptyTitle">还没有费用</div>
            <div class="emptyHint">在工作记录里登记费用后会出现在这里。</div>
          </div>
          <ul v-else class="bars">
            <li v-for="(row, i) in expenseRows" :key="row.name" class="barRow">
              <div class="barLabel">{{ row.name }}</div>
              <div class="barTrack">
                <div class="barFill" :style="{ width: barWidth(row.value, expenseRows), background: INK_BARS[i % INK_BARS.length] }" />
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
  --paper: #f7f8fa;
  --ledger: #edeff2;
  --ink: #1b3a4b;
  --lead: #5c6b73;
  --vermilion: #c23a2b;
  --wash: #f4e6e3;
  --rule: #d5d9de;
  margin: -16px;
  padding: 16px 16px 28px;
  min-height: 100%;
  background: var(--ledger);
  color: var(--ink);
}

.hero {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 20px;
  align-items: center;
  padding: 8px 4px 18px;
}

.stamp {
  width: 92px;
  height: 92px;
  border: 3px double currentColor;
  color: var(--ink);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: var(--paper);
  box-shadow: 2px 3px 0 rgba(27, 58, 75, 0.08);
  animation: stampIn 0.22s ease-out;
  transform: rotate(-6deg);
}

.stamp.urgent {
  color: var(--vermilion);
  box-shadow: 2px 3px 0 rgba(194, 58, 43, 0.12);
}

.stampMonth,
.stampFoot {
  font-family: "Noto Serif SC", "STSong", "SimSun", serif;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

.stampDay {
  font-family: "Noto Serif SC", "STSong", "SimSun", serif;
  font-size: 36px;
  font-weight: 900;
  line-height: 1;
  margin: 2px 0;
}

.heroKicker {
  font-size: 11px;
  letter-spacing: 0.28em;
  text-transform: uppercase;
  color: var(--lead);
  font-weight: 700;
}

.heroTitle {
  margin: 4px 0 6px;
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.heroSub {
  margin: 0;
  font-size: 13px;
  color: var(--lead);
  max-width: 42em;
}

.writeBtn {
  border: 1px solid var(--ink);
  background: var(--ink);
  color: #f7f8fa;
  padding: 10px 16px;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  white-space: nowrap;
}

.writeBtn:hover,
.writeBtn:focus-visible {
  background: #142c39;
}

.writeBtn:focus-visible,
.ledgerRow:focus-visible,
.doingRow:focus-visible {
  outline: 2px solid var(--ink);
  outline-offset: 2px;
}

.writeBtn.ghost {
  background: transparent;
  color: var(--ink);
  margin-top: 12px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  gap: 12px;
}

.sheet {
  background: var(--paper);
  border: 1px solid var(--rule);
  padding: 16px 18px 18px;
}

.ledger {
  grid-column: span 12;
}

.doing {
  grid-column: span 5;
}

.charts {
  grid-column: span 7;
}

.sheetHead {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--rule);
  padding-bottom: 10px;
  margin-bottom: 12px;
}

.sheetHead.sub {
  margin-top: 8px;
}

.sheetKicker {
  font-size: 11px;
  color: var(--lead);
  letter-spacing: 0.08em;
}

.sheetTitle {
  margin: 2px 0 0;
  font-size: 16px;
  font-weight: 800;
}

.sheetMeta {
  font-size: 12px;
  color: var(--lead);
  font-variant-numeric: tabular-nums;
}

.ledgerList,
.doingList,
.bars {
  list-style: none;
  margin: 0;
  padding: 0;
}

.ledgerRow {
  display: grid;
  grid-template-columns: 88px 1fr;
  gap: 12px;
  padding: 10px 8px;
  border-bottom: 1px dashed var(--rule);
  cursor: pointer;
}

.ledgerRow:last-child {
  border-bottom: 0;
}

.ledgerRow:hover {
  background: #f3f4f6;
}

.ledgerRow.urgent:hover {
  background: var(--wash);
}

.ledgerDate {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-variant-numeric: tabular-nums;
}

.ledgerDate .d {
  font-family: Consolas, "Cascadia Mono", ui-monospace, monospace;
  font-size: 13px;
  font-weight: 700;
}

.ledgerDate .rel {
  font-size: 11px;
  color: var(--lead);
}

.ledgerRow.urgent .ledgerDate .d,
.ledgerRow.urgent .kind {
  color: var(--vermilion);
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
  font-weight: 800;
  letter-spacing: 0.08em;
  color: var(--ink);
  border: 1px solid currentColor;
  padding: 1px 5px;
}

.ledgerTitle {
  font-size: 13px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ledgerMeta {
  margin-top: 3px;
  font-size: 12px;
  color: var(--lead);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doingList {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.doingRow {
  padding: 10px 10px 10px 12px;
  border-left: 3px solid var(--ink);
  background: #f3f4f6;
  cursor: pointer;
}

.doingRow:hover {
  background: #eceef1;
}

.doingRow.overdue {
  border-left-color: var(--vermilion);
}

.doingTitle {
  font-size: 13px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doingMeta {
  margin-top: 3px;
  font-size: 12px;
  color: var(--lead);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bars {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.barRow {
  display: grid;
  grid-template-columns: 88px 1fr 72px;
  gap: 8px;
  align-items: center;
}

.barLabel {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.barTrack {
  height: 8px;
  background: var(--ledger);
}

.barFill {
  height: 100%;
  min-width: 4px;
}

.barValue {
  font-family: Consolas, "Cascadia Mono", ui-monospace, monospace;
  font-size: 12px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.chartSplit {
  height: 1px;
  background: var(--rule);
  margin: 18px 0 4px;
}

.empty {
  padding: 28px 8px;
  text-align: left;
}

.empty.compact {
  padding: 12px 0 8px;
}

.emptyTitle {
  font-size: 14px;
  font-weight: 800;
}

.emptyHint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--lead);
}

.error {
  color: var(--vermilion);
  font-size: 13px;
  margin: 8px 0 0;
}

@keyframes stampIn {
  from {
    opacity: 0;
    transform: rotate(-6deg) scale(1.12);
  }
  to {
    opacity: 1;
    transform: rotate(-6deg) scale(1);
  }
}

@media (prefers-reduced-motion: reduce) {
  .stamp {
    animation: none;
  }
}

@media (max-width: 1100px) {
  .doing,
  .charts {
    grid-column: span 12;
  }
}

@media (max-width: 720px) {
  .hero {
    grid-template-columns: auto 1fr;
  }

  .writeBtn {
    grid-column: 1 / -1;
    justify-self: start;
  }

  .ledgerRow {
    grid-template-columns: 72px 1fr;
  }

  .barRow {
    grid-template-columns: 1fr 64px;
  }

  .barLabel {
    grid-column: 1 / -1;
  }
}
</style>
