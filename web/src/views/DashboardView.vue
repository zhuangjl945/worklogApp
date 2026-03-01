<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { List } from '@element-plus/icons-vue'
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

const displayName = computed(() => props.user?.realName || props.user?.username || '用户')

const router = useRouter()

const tasksLoading = ref(false)
const tasksError = ref('')

const todoTasks = ref([])
const doingTasks = ref([])

const expiringContracts = ref([])
const expiringPayments = ref([])
const remindersLoading = ref(false)
const remindersError = ref('')

const myDeptId = ref(null)

// 统一 Tab 状态：contract | payment | todo | doing
const activeTab = ref('contract')

const todoCount = computed(() => todoTasks.value.length)
const doingCount = computed(() => doingTasks.value.length)

const categoryChartRef = ref(null)
let categoryChartInstance = null
const categoryChartLoading = ref(false)
const categoryChartError = ref('')

const expenseChartRef = ref(null)
let expenseChartInstance = null
const expenseChartLoading = ref(false)
const expenseChartError = ref('')

function goWorkRecords(t) {
  router.push({ path: '/work-records', query: { focusId: String(t?.id ?? '') } })
}

async function loadTasks() {
  tasksLoading.value = true
  tasksError.value = ''
  try {
    const [todoResp, doingResp] = await Promise.all([
      workRecordPage({ page: 1, size: 10, statusIds: [1] }),
      workRecordPage({ page: 1, size: 10, statusIds: [2] })
    ])
    todoTasks.value = todoResp.data?.records || []
    doingTasks.value = doingResp.data?.records || []
  } catch (e) {
    tasksError.value = e?.message || '加载任务失败'
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
    remindersError.value = e?.message || '加载提醒失败'
  } finally {
    remindersLoading.value = false
  }
}

function buildPieOption(name, items, opts = {}) {
  const isDonut = !!opts.donut
  return {
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c} ({d}%)'
    },
    legend: {
      orient: 'vertical',
      left: 'left',
      top: 'middle'
    },
    series: [
      {
        name,
        type: 'pie',
        radius: isDonut ? ['45%', '72%'] : '70%',
        center: ['65%', '50%'],
        data: items,
        itemStyle: {
          borderRadius: isDonut ? 10 : 0,
          borderWidth: isDonut ? 2 : 0,
          borderColor: '#fff'
        },
        emphasis: {
          itemStyle: {
            shadowBlur: 10,
            shadowOffsetX: 0,
            shadowColor: 'rgba(0, 0, 0, 0.5)'
          }
        },
        label: {
          show: true,
          formatter: '{b}: {c}'
        },
        labelLine: {
          show: true
        }
      }
    ]
  }
}

const currentYear = ref(new Date().getFullYear())

const yearRange = computed(() => {
  const year = currentYear.value
  return {
    start: `${year}-01-01 00:00:00`,
    end: `${year}-12-31 23:59:59`
  }
})

async function loadCategoryChart() {
  categoryChartLoading.value = true
  categoryChartError.value = ''
  try {
    const range = yearRange.value
    const resp = await workRecordStatsForDashboard({
      startTimeFrom: range.start,
      startTimeTo: range.end
    })
    const data = (resp.data || []).map((x) => ({
      name: x.categoryName,
      value: x.totalCount || 0
    }))
    if (!categoryChartInstance && categoryChartRef.value) {
      categoryChartInstance = echarts.init(categoryChartRef.value)
    }
    categoryChartInstance?.setOption(buildPieOption('工作量', data, { donut: true }), true)
  } catch (e) {
    categoryChartError.value = e?.message || '加载图表失败'
  } finally {
    categoryChartLoading.value = false
  }
}

async function loadExpenseChart() {
  expenseChartLoading.value = true
  expenseChartError.value = ''
  try {
    const range = yearRange.value
    const resp = await workRecordStatsByExpenseType({
      expenseTimeFrom: range.start,
      expenseTimeTo: range.end
    })
    const data = (resp.data || []).map((x) => ({
      name: x.expenseType,
      value: x.totalAmount || 0
    }))
    if (!expenseChartInstance && expenseChartRef.value) {
      expenseChartInstance = echarts.init(expenseChartRef.value)
    }
    expenseChartInstance?.setOption(buildPieOption('费用', data), true)
  } catch (e) {
    expenseChartError.value = e?.message || '加载图表失败'
  } finally {
    expenseChartLoading.value = false
  }
}

function onResize() {
  categoryChartInstance?.resize()
  expenseChartInstance?.resize()
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
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  categoryChartInstance?.dispose()
  expenseChartInstance?.dispose()
})
</script>

<template>
  <div class="page">
    <div class="hero">
      <div class="heroTitle">欢迎回来，{{ displayName }}</div>
    </div>

    <div class="grid">
      <!-- 左侧统计卡片 -->
      <el-card class="card stat-card reminder-stat-card" shadow="never" v-loading="remindersLoading || tasksLoading">
        <div class="stat-group">
          <div class="stat-item clickable" @click="activeTab = 'contract'">
            <div class="stat-value" :class="{ 'has-data': expiringContracts.length > 0 }">{{ expiringContracts.length }}</div>
            <div class="stat-label">合同到期</div>
          </div>
          <el-divider direction="vertical" />
          <div class="stat-item clickable" @click="activeTab = 'payment'">
            <div class="stat-value" :class="{ 'has-data': expiringPayments.length > 0 }">{{ expiringPayments.length }}</div>
            <div class="stat-label">付款到期</div>
          </div>
          <el-divider direction="vertical" />
          <div class="stat-item clickable" @click="activeTab = 'todo'">
            <div class="stat-value" :class="{ 'has-data': todoCount > 0 }">{{ todoCount }}</div>
            <div class="stat-label">待办任务</div>
          </div>
          <el-divider direction="vertical" />
          <div class="stat-item clickable" @click="activeTab = 'doing'">
            <div class="stat-value" :class="{ 'has-data': doingCount > 0 }">{{ doingCount }}</div>
            <div class="stat-label">进行中</div>
          </div>
        </div>
      </el-card>

      <el-card v-if="loading" class="card info-card" shadow="never">
        <div class="cardTitle">加载中</div>
        <div class="muted">正在获取当前用户信息…</div>
      </el-card>

      <el-card v-else-if="errorMsg" class="card info-card" shadow="never">
        <div class="cardTitle">加载失败</div>
        <div class="error">{{ errorMsg }}</div>
      </el-card>

      <!-- 右侧统一 Tab 区域 -->
      <el-card v-else-if="user" class="card info-card" shadow="never">
        <div class="cardTitle">
          <span class="cardTitleLeft">
            <el-icon class="cardTitleIcon"><List /></el-icon>
            <span>工作看板</span>
          </span>
        </div>

        <div class="unified-tabs-section">
          <div class="tab-list">
            <div class="tab-item" :class="{ active: activeTab === 'contract' }" @click="activeTab = 'contract'">
              合同到期 ({{ expiringContracts.length }})
            </div>
            <div class="tab-item" :class="{ active: activeTab === 'payment' }" @click="activeTab = 'payment'">
              待处理付款 ({{ expiringPayments.length }})
            </div>
            <div class="tab-item" :class="{ active: activeTab === 'todo' }" @click="activeTab = 'todo'">
              待办任务 ({{ todoCount }})
            </div>
            <div class="tab-item" :class="{ active: activeTab === 'doing' }" @click="activeTab = 'doing'">
              进行中 ({{ doingCount }})
            </div>
          </div>

          <div class="tab-content-area" v-loading="remindersLoading || tasksLoading">
            <!-- 合同到期 -->
            <div v-if="activeTab === 'contract'">
              <div v-if="!expiringContracts || expiringContracts.length === 0" class="muted">未来30天内无即将到期的执行中合同</div>
              <div v-else class="list-wrapper">
                <div v-for="c in expiringContracts" :key="c.id" class="list-row reminder-row clickable" @click="$router.push(`/contract/detail/${c.id}`)">
                  <div class="r-title">{{ c.contractName }}</div>
                  <div class="r-info">{{ c.contractNo }} | 到期：{{ c.endDate }}</div>
                </div>
              </div>
            </div>

            <!-- 付款到期 -->
            <div v-else-if="activeTab === 'payment'">
              <div v-if="!expiringPayments || expiringPayments.length === 0" class="muted">未来30天内无待处理的付款计划</div>
              <div v-else class="list-wrapper">
                <div v-for="p in expiringPayments" :key="p.id" class="list-row reminder-row clickable" @click="$router.push(`/contract/detail/${p.contractId}`)">
                  <div class="r-title">{{ p.planNo }} - ¥{{ p.planAmount }}</div>
                  <div class="r-info">合同ID：{{ p.contractId }} | 计划日期：{{ p.planDate }}</div>
                </div>
              </div>
            </div>

            <!-- 待办任务 (两行显示) -->
            <div v-else-if="activeTab === 'todo'">
              <div v-if="todoTasks.length === 0" class="muted">暂无待办任务</div>
              <div v-else class="list-wrapper">
                <div v-for="t in todoTasks" :key="t.id" class="list-row task-row clickable" @click="goWorkRecords(t)">
                  <div class="task-line1">
                    <div class="task-title">{{ t.title }}</div>
                    <div class="task-status status-todo">待处理</div>
                  </div>
                  <div class="task-line2">{{ t.content || '（无内容）' }}</div>
                </div>
              </div>
            </div>

            <!-- 进行中 (两行显示) -->
            <div v-else-if="activeTab === 'doing'">
              <div v-if="doingTasks.length === 0" class="muted">暂无进行中任务</div>
              <div v-else class="list-wrapper">
                <div v-for="t in doingTasks" :key="t.id" class="list-row task-row clickable" @click="goWorkRecords(t)">
                  <div class="task-line1">
                    <div class="task-title">{{ t.title }}</div>
                    <div class="task-status status-doing">进行中</div>
                  </div>
                  <div class="task-line2">{{ t.content || '（无内容）' }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </el-card>

      <!-- 图表统计 -->
      <el-card class="card chart-card" shadow="never">
        <div class="cardTitle">按工作分类统计工作量（{{ currentYear }}年）</div>
        <div v-if="categoryChartError" class="error">{{ categoryChartError }}</div>
        <div v-else class="chartWrap" v-loading="categoryChartLoading">
          <div ref="categoryChartRef" class="chart" />
        </div>
      </el-card>

      <el-card class="card chart-card" shadow="never">
        <div class="cardTitle">按费用类型统计费用（{{ currentYear }}年）</div>
        <div v-if="expenseChartError" class="error">{{ expenseChartError }}</div>
        <div v-else class="chartWrap" v-loading="expenseChartLoading">
          <div ref="expenseChartRef" class="chart" />
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.page {
  padding: 12px;
}

.hero {
  padding: 12px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #e9edf5;
}

.heroTitle {
  font-size: 16px;
  font-weight: 900;
  color: #111827;
}

.grid {
  margin-top: 10px;
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  gap: 10px;
}

.card {
  border-radius: 10px;
}

.stat-card {
  grid-column: span 3;
  text-align: center;
}

.reminder-stat-card {
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.stat-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-around;
  gap: 8px;
}

.stat-item {
  flex: 1;
  min-width: 70px;
  padding: 4px;
}

.stat-item.clickable {
  cursor: pointer;
  transition: all 0.2s;
}

.stat-item.clickable:hover {
  background: #f8fafc;
  border-radius: 6px;
}

.stat-value {
  font-size: 24px;
  font-weight: 900;
  color: #64748b;
}

.stat-value.has-data {
  color: #b91c1c;
}

.stat-label {
  margin-top: 2px;
  font-size: 11px;
  color: #667085;
}

.info-card {
  grid-column: span 9;
}

.cardTitle {
  font-weight: 900;
  margin-bottom: 8px;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cardTitleLeft {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.cardTitleIcon {
  font-size: 14px;
  color: #111827;
}

/* Unified Tabs */
.unified-tabs-section {
  margin-top: 4px;
}

.tab-list {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.tab-item {
  font-size: 11px;
  font-weight: 800;
  color: #667085;
  padding: 4px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  cursor: pointer;
  user-select: none;
  transition: all 0.2s;
}

.tab-item:hover {
  border-color: #c7d2fe;
}

.tab-item.active {
  color: #111827;
  background: #eef2ff;
  border-color: #c7d2fe;
}

.tab-content-area {
  border: 1px solid #e9edf5;
  border-radius: 8px;
  padding: 8px;
  min-height: 160px;
  max-height: 320px;
  overflow-y: auto;
}

.list-wrapper {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.list-row {
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid transparent;
  transition: all 0.2s;
}

.list-row.clickable {
  cursor: pointer;
}

/* 提醒行样式 */
.reminder-row {
  background: #fff9f9;
  border-color: #fee2e2;
}

.reminder-row:hover {
  border-color: #fca5a5;
  background: #fef2f2;
}

.r-title {
  font-size: 12px;
  font-weight: 800;
  color: #b91c1c;
}

.r-info {
  margin-top: 2px;
  font-size: 11px;
  color: #7f1d1d;
}

/* 任务行样式 (2行显示) */
.task-row {
  background: #fff;
  border-color: #e9edf5;
}

.task-row:hover {
  border-color: #c7d2fe;
  background: #f8fafc;
}

.task-line1 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.task-title {
  font-size: 12px;
  font-weight: 800;
  color: #111827;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.task-status {
  font-size: 10px;
  color: #fff;
  padding: 1px 5px;
  border-radius: 4px;
  white-space: nowrap;
}

.status-todo { background: #3b82f6; }
.status-doing { background: #10b981; }

.task-line2 {
  margin-top: 4px;
  font-size: 11px;
  color: #667085;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 其他布局 */
.chart-card {
  grid-column: span 6;
}

.chartWrap {
  height: 280px;
}

.chart {
  width: 100%;
  height: 100%;
}

.muted {
  font-size: 11px;
  color: #667085;
  text-align: center;
  padding: 20px 0;
}

.error {
  color: #b42318;
  font-size: 12px;
}

@media (max-width: 1200px) {
  .info-card, .stat-card {
    grid-column: span 12;
  }
}

@media (max-width: 768px) {
  .chart-card {
    grid-column: span 12;
  }
}
</style>
