<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ticketPage, ticketStatuses } from '../api/ticket'

const router = useRouter()

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const statuses = ref([])

const query = reactive({
  page: 1,
  size: 20,
  scope: 'open',
  statusIds: [],
  keyword: '',
  overdue: false
})

const scopeOptions = [
  { value: 'open', label: '待办' },
  { value: 'mine', label: '我处理的' },
  { value: 'all', label: '本科室全部' }
]

const hasMore = computed(() => total.value > query.page * query.size)

let pollTimer = null

onMounted(async () => {
  try {
    const resp = await ticketStatuses()
    statuses.value = resp.data || []
  } catch (e) {
    // 状态下拉失败不影响列表本身
  }
  await load()
  // 与任务转移提醒同一套机制：轮询而非 WebSocket，内网够用且不用改部署
  pollTimer = setInterval(load, 30000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})

function buildParams() {
  const params = { page: query.page, size: query.size }
  if (query.scope === 'open') params.scope = 'open'
  if (query.scope === 'mine') params.scope = 'mine'
  if (query.statusIds.length) params.statusIds = query.statusIds
  if (query.keyword.trim()) params.keyword = query.keyword.trim()
  if (query.overdue) params.overdue = true
  return params
}

async function load() {
  loading.value = true
  try {
    const resp = await ticketPage(buildParams())
    rows.value = resp.data?.records || []
    total.value = resp.data?.total || 0
  } catch (e) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

function shiftScope(scope) {
  query.scope = scope
  query.page = 1
  load()
}

// 紧急程度 tag 颜色：code 越小越紧急
function urgencyTagType(code) {
  if (code <= 1) return 'danger'
  if (code === 2) return 'warning'
  if (code === 3) return ''
  return 'info'
}

function statusTagType(code) {
  if (code === 0) return 'danger'
  if (code === 10 || code === 20) return 'warning'
  if (code === 30 || code === 40) return 'success'
  return 'info'
}

function remainText(row) {
  if (row.slaRemainMinutes == null) return '—'
  const m = row.slaRemainMinutes
  if (m < 0) return `超时 ${fmtSpan(-m)}`
  return `剩 ${fmtSpan(m)}`
}

function fmtSpan(minutes) {
  if (minutes < 60) return `${minutes} 分钟`
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return m ? `${h} 小时 ${m} 分` : `${h} 小时`
}

function overdue(row) {
  return row.slaRemainMinutes != null && row.slaRemainMinutes < 0 && [0, 10, 20].includes(row.status)
}

/** 状态列下补一行「多久后自动确认」，受理人才能解释这条待确认单不会一直挂着 */
function autoConfirmText(row) {
  if (row.status !== 20 || row.autoConfirmRemainMinutes == null) return ''
  const m = row.autoConfirmRemainMinutes
  return m <= 0 ? '即将自动确认' : `${fmtSpan(m)}后自动确认`
}

function open(row) {
  router.push(`/tickets/${row.id}`)
}

// 键盘流：受理高峰是批量作业，Enter 进详情比鼠标移动快得多
function onKeydown(e) {
  if (e.target?.tagName === 'INPUT' || e.target?.tagName === 'TEXTAREA') return
  if (e.key === 'Enter' && rows.value.length) open(rows.value[0])
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="ticket-inbox">
    <div class="toolbar">
      <el-radio-group :model-value="query.scope" size="default" @update:model-value="shiftScope">
        <el-radio-button v-for="s in scopeOptions" :key="s.value" :value="s.value">{{ s.label }}</el-radio-button>
      </el-radio-group>

      <el-select v-model="query.statusIds" multiple collapse-tags placeholder="状态" style="width: 190px" clearable>
        <el-option v-for="s in statuses" :key="s.code" :label="s.name" :value="s.code" />
      </el-select>

      <el-input v-model="query.keyword" placeholder="标题 / 地点 / 姓名 / 单号" style="width: 240px" clearable
                @keyup.enter="search" @clear="search" />
      <el-checkbox v-model="query.overdue" @change="search">只看超时</el-checkbox>
      <el-button type="primary" :loading="loading" @click="search">查询</el-button>
      <span class="total">共 {{ total }} 条</span>
    </div>

    <el-table v-loading="loading" :data="rows" row-key="id" size="small" border @row-dblclick="open">
      <!-- 短流水号只有 8 位（ST100001），老号 14 位：120 级别宽度够放等宽字体的老号 -->
        <el-table-column label="单号" width="128">
        <template #default="{ row }">
          <span :class="{ 'ov': overdue(row) }">{{ row.ticketNo }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="118">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small" disable-transitions>{{ row.statusName }}</el-tag>
          <div v-if="autoConfirmText(row)" class="auto-confirm">{{ autoConfirmText(row) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="SLA" width="120">
        <template #default="{ row }">
          <span :class="{ 'ov': overdue(row) }">{{ remainText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="问题" min-width="230" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="open(row)">{{ row.title }}</el-link>
          <el-tag v-if="row.urgencyName" :type="urgencyTagType(row.urgency)" size="small" effect="plain">{{ row.urgencyName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="categoryName" label="类型" width="110" show-overflow-tooltip />
      <el-table-column prop="bizDeptName" label="问题科室" width="120" show-overflow-tooltip />
      <el-table-column prop="location" label="地点" width="130" show-overflow-tooltip />
      <el-table-column label="报修人" width="130">
        <template #default="{ row }">
          {{ row.contactName || '匿名' }}
          <span v-if="row.contactPhone" class="phone">{{ row.contactPhone.slice(-4) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="图" width="48" align="center">
        <template #default="{ row }">
          <span v-if="row.images && row.images.length">
            {{ row.images.length }}
          </span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="assigneeName" label="处理人" width="96" />
      <el-table-column prop="createTime" label="提交时间" width="150" />
      <el-table-column label="操作" width="96" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">处理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-button :disabled="query.page <= 1" @click="query.page = 1; load()">首页</el-button>
      <el-button :disabled="query.page <= 1" @click="query.page--; load()">上一页</el-button>
      <span class="page-no">第 {{ query.page }} 页</span>
      <el-button :disabled="!hasMore" @click="query.page++; load()">下一页</el-button>
    </div>
  </div>
</template>

<style scoped>
.ticket-inbox {
  padding: 4px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.total {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.pager {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-no {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.ov {
  color: var(--el-color-danger);
  font-weight: 600;
}

.phone {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.auto-confirm {
  font-size: 11px;
  line-height: 1.4;
  color: var(--el-color-warning-dark-2);
}

.muted {
  color: var(--el-text-color-placeholder);
}
</style>
