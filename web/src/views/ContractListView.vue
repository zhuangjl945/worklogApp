<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Tickets } from '@element-plus/icons-vue'
import { contractDelete, contractPage, contractStart, contractComplete, contractTerminate, contractRenew } from '../api/contract'
import { me } from '../api/auth'
import { parseFileUrlList } from '../utils/oss'
import FilePreviewDialog from '../components/FilePreviewDialog.vue'
import QueryConsole from '../components/QueryConsole.vue'
import { useQueryConsole } from '../composables/useQueryConsole'

const router = useRouter()

const loading = ref(false)
const tableData = ref([])
const total = ref(0)

const myDeptId = ref(null)

const filters = reactive({
  page: 1,
  size: 10,
  contractNo: '',
  contractName: '',
  status: null,

})

const statusOptions = [
  { value: null, label: '全部' },
  { value: 10, label: '草稿' },
  { value: 30, label: '执行中' },
  { value: 40, label: '已完成' },
  { value: 50, label: '已终止' },
  { value: 60, label: '已过期' }
]

function statusText(v) {
  return statusOptions.find((x) => x.value === v)?.label || String(v ?? '')
}

function statusTagType(v) {
  if (v === 10) return 'info'
  if (v === 30) return 'success'
  if (v === 40) return 'success'
  if (v === 50) return 'danger'
  if (v === 60) return 'warning'
  return 'info'
}

const previewRef = ref()
function rowFiles(row) {
  return parseFileUrlList(row?.contractFileUrl)
}
function previewFile(url) {
  previewRef.value?.open(url)
}

async function load() {
  loading.value = true
  try {
    const resp = await contractPage({
      page: filters.page,
      size: filters.size,
      contractNo: filters.contractNo || undefined,
      contractName: filters.contractName || undefined,
      status: filters.status ?? undefined,
      deptId: myDeptId.value ?? undefined
    })
    tableData.value = resp.data.records
    total.value = resp.data.total
  } catch (e) {
    ElMessage.error(e?.message || '加载合同列表失败')
  } finally {
    loading.value = false
  }
}

// ------------------------------------------------------------
// 检索台：条件一变就自动重查，不再需要「查询」按钮
// ------------------------------------------------------------

// 只对筛选字段敏感，翻页、改每页条数不应触发重查
const filterSignature = computed(() =>
  JSON.stringify({
    contractNo: filters.contractNo.trim(),
    contractName: filters.contractName.trim(),
    status: filters.status
  })
)

function runQuery() {
  filters.page = 1
  load()
}

const { expanded: filterExpanded, run: runSearch } = useQueryConsole({
  getSignature: () => filterSignature.value,
  runQuery
})

function onReset() {
  filters.contractNo = ''
  filters.contractName = ''
  filters.status = null
  runSearch()
}

// 状态在这张表是单选，胶囊再点一次就是取消选择
const statusPills = statusOptions.filter((o) => o.value !== null)

function pickStatus(value) {
  filters.status = filters.status === value ? null : value
}

const filterChips = computed(() => {
  const chips = []
  const name = filters.contractName.trim()
  if (name) chips.push({ key: 'contractName', field: '名称', value: `含「${name}」` })
  const no = filters.contractNo.trim()
  if (no) chips.push({ key: 'contractNo', field: '编号', value: no })
  if (filters.status !== null) chips.push({ key: 'status', field: '状态', value: statusText(filters.status) })
  return chips
})

function removeFilterChip(key) {
  if (key === 'contractName') filters.contractName = ''
  else if (key === 'contractNo') filters.contractNo = ''
  else if (key === 'status') filters.status = null
}

const hiddenActiveFilterCount = computed(() => {
  if (filterExpanded.value) return 0
  return filters.contractNo.trim() ? 1 : 0
})

function onPageChange(page) {
  filters.page = page
  load()
}

function onSizeChange(size) {
  filters.size = size
  filters.page = 1
  load()
}

async function doDelete(row) {
  try {
    await ElMessageBox.confirm('仅草稿合同可删除，确认删除吗？', '提示', { type: 'warning' })
    await contractDelete(row.id)
    ElMessage.success('删除成功')
    await load()
  } catch (e) {
    if (e && typeof e === 'object' && e.message) ElMessage.error(e.message)
  }
}

async function doStart(row) {
  try {
    await ElMessageBox.confirm('确认启动该合同吗？启动后将生成付款计划。', '提示', { type: 'warning' })
    await contractStart(row.id)
    ElMessage.success('启动成功')
    await load()
  } catch (e) {
    if (e && typeof e === 'object' && e.message) ElMessage.error(e.message)
  }
}

async function doComplete(row) {
  try {
    await ElMessageBox.confirm('确认标记为已完成吗？', '提示', { type: 'warning' })
    await contractComplete(row.id)
    ElMessage.success('操作成功')
    await load()
  } catch (e) {
    if (e && typeof e === 'object' && e.message) ElMessage.error(e.message)
  }
}

async function doTerminate(row) {
  try {
    await ElMessageBox.confirm('确认终止该合同吗？', '提示', { type: 'warning' })
    await contractTerminate(row.id)
    ElMessage.success('操作成功')
    await load()
  } catch (e) {
    if (e && typeof e === 'object' && e.message) ElMessage.error(e.message)
  }
}

async function doRenew(row) {
  try {
    await ElMessageBox.confirm(`确认基于合同 [${row.contractNo}] 续签一份新合同吗？`, '提示', { type: 'warning' })
    const resp = await contractRenew(row.id)
    const newId = resp?.data?.id || resp?.id
    if (!newId) {
      ElMessage.error('续签成功但未获取到新合同ID，请刷新后重试')
      return
    }
    ElMessage.success('续签成功，已生成新草稿')
    router.push(`/contract/edit/${newId}`)
  } catch (e) {
    if (e && typeof e === 'object' && e.message) ElMessage.error(e.message)
  }
}

function goDetail(row) {
  router.push(`/contract/detail/${row.id}`)
}

async function init() {
  try {
    const resp = await me()
    myDeptId.value = resp?.data?.deptId ?? null
  } catch {
    myDeptId.value = null
  }
  await load()
}

onMounted(init)

function formatWan(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '0.00'
  return (n / 10000).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 实际付款金额（元）：付款计划中已登记的实付合计
function getActualPaidAmount(row) {
  const paid = Number(row.paidAmount ?? 0)
  return Number.isFinite(paid) ? paid : 0
}

// 付款余额（元）= 合同总额 - 实际付款金额
function getRemainAmount(row) {
  const remainFromApi = Number(row.remainAmount)
  if (row.remainAmount != null && Number.isFinite(remainFromApi)) return remainFromApi
  const total = Number(row.contractAmount ?? 0)
  const paid = getActualPaidAmount(row)
  if (!Number.isFinite(total)) return 0
  return Math.round((total - paid) * 100) / 100
}

// 当前列表所有合同的付款余额总和（元）
function getRemainTotal() {
  return tableData.value.reduce((sum, row) => sum + getRemainAmount(row), 0)
}

// 检测多行文本是否溢出
function checkOverflow(e, row, field) {
  const el = e.currentTarget
  row[field] = el.scrollHeight > el.clientHeight
}
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Tickets /></el-icon>
        <div class="title">合同列表</div>
      </div>
      <div class="actions">
        <el-button type="primary" @click="$router.push('/contract/edit')">新增合同</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <QueryConsole
      class="qc-block"
      v-model:expanded="filterExpanded"
      :chips="filterChips"
      :hidden-active-count="hiddenActiveFilterCount"
      :result-text="'共 ' + total + ' 条'"
      :busy="loading"
      @remove-chip="removeFilterChip"
      @clear-all="onReset"
    >
      <template #search>
        <el-input v-model="filters.contractName" class="qc-search" placeholder="搜索合同名称，回车即查" clearable @keyup.enter="runSearch">
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
      </template>

      <template #inline>
        <span class="qc-flabel">状态</span>
        <div class="qc-pills" role="group" aria-label="按状态筛选">
          <button
            v-for="opt in statusPills"
            :key="opt.value"
            type="button"
            class="qc-pill"
            :class="{ 'is-on': filters.status === opt.value }"
            :aria-pressed="filters.status === opt.value"
            @click="pickStatus(opt.value)"
          >
            <span class="qc-pill-dot" aria-hidden="true" />{{ opt.label }}
          </button>
        </div>
      </template>

      <template #more>
        <div class="qc-grid">
          <div class="qc-cell">
            <span class="qc-flabel">合同编号</span>
            <el-input v-model="filters.contractNo" placeholder="完整编号，精确匹配" clearable @keyup.enter="runSearch" />
          </div>
        </div>
      </template>
    </QueryConsole>

    <el-card class="card" shadow="never">

      <el-table
        v-loading="loading"
        :data="tableData"
        border
        stripe
        @row-click="goDetail"
      >
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="contractNo" label="合同编号" width="120" show-overflow-tooltip />
        <el-table-column prop="contractName" label="合同名称" min-width="200">
          <template #default="{ row }">
            <el-tooltip
              :content="row.contractName"
              placement="top"
              :disabled="!row._nameOverflow"
            >
              <div class="two-line-text" @mouseenter="checkOverflow($event, row, '_nameOverflow')">
                <span>{{ row.contractName }}</span>
              </div>
            </el-tooltip>
            <el-tag v-if="row.renewFromNo" size="small" type="info" style="margin-top: 4px;">
              续签自: {{ row.renewFromNo }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="200">
          <template #default="{ row }">
            <el-tooltip
              :content="row.supplierName"
              placement="top"
              :disabled="!row._supplierOverflow"
            >
              <div class="two-line-text" @mouseenter="checkOverflow($event, row, '_supplierOverflow')">
                {{ row.supplierName }}
              </div>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="contractAmount" label="合同金额" width="100">
          <template #default="{ row }">¥{{ formatWan(row.contractAmount) }}万</template>
        </el-table-column>
        <el-table-column label="付款进度" width="120">
          <template #default="{ row }">
            <div style="display: flex; flex-direction: column; gap: 4px;">
              <span class="amount">¥{{ formatWan(getActualPaidAmount(row)) }}万</span>
              <span v-if="row.paymentTerms === 1" style="font-size: 12px; color: #909399;">一次性付款</span>
              <span v-else style="font-size: 12px; color: #909399;">次数: {{ row.paidPlanCount ?? 0 }} / {{ row.totalPlanCount ?? 0 }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="付款余额" width="100">
          <template #default="{ row }">
            <span style="color: #f56c6c; font-weight: 600;">
              ¥{{ formatWan(getRemainAmount(row)) }}万
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="startDate" label="开始" width="110" />
        <el-table-column prop="endDate" label="结束" width="110" />
        <el-table-column label="附件" width="88" :cell-class-name="() => 'no-row-click'">
          <template #default="{ row }">
            <template v-if="rowFiles(row).length">
              <template v-if="rowFiles(row).length > 1">
                <el-popover placement="top" :width="220" trigger="hover">
                  <template #reference>
                    <el-link type="primary" @click.stop>浏览({{ rowFiles(row).length }})</el-link>
                  </template>
                  <div v-for="(u, idx) in rowFiles(row)" :key="idx" style="margin: 4px 0">
                    <el-link type="primary" size="small" @click.stop="previewFile(u)">附件 {{ idx + 1 }}</el-link>
                  </div>
                </el-popover>
              </template>
              <el-link v-else type="primary" @click.stop="previewFile(rowFiles(row)[0])">浏览</el-link>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" :cell-class-name="() => 'no-row-click'">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" @click.stop>{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" :cell-class-name="() => 'no-row-click'">
          <template #default="{ row }">
            <div style="display: flex; flex-direction: column; gap: 6px;">
              <div style="display: flex; gap: 6px; flex-wrap: wrap;">
                <el-button size="small" type="primary" @click.stop="$router.push(`/contract/detail/${row.id}`)">详情</el-button>
                <el-button size="small" @click.stop="$router.push(`/contract/edit/${row.id}`)" :disabled="row.status !== 10">编辑</el-button>
              </div>
              <div style="display: flex; gap: 6px;">
                <el-button size="small" type="success" @click.stop="doStart(row)" :disabled="row.status !== 10">启用</el-button>
                <el-dropdown @click.stop>
                  <el-button class="more-btn" size="small" @click.stop>更多</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item :disabled="row.status !== 30" @click="doComplete(row)">标记完成</el-dropdown-item>
                      <el-dropdown-item :disabled="row.status !== 30" @click="doTerminate(row)">终止</el-dropdown-item>
                      <el-dropdown-item @click="doRenew(row)">续签</el-dropdown-item>
                      <el-dropdown-item divided :disabled="row.status !== 10" @click="doDelete(row)">删除</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="footer-bar">
        <div class="summary-bar">
          <span>当前列表付款余额合计：</span>
          <span class="summary-amount">¥{{ formatWan(getRemainTotal()) }}万</span>
        </div>

        <el-pagination
          class="pagination"
          background
          layout="prev, pager, next, sizes, total"
          :total="total"
          :current-page="filters.page"
          :page-size="filters.size"
          @update:current-page="onPageChange"
          @update:page-size="onSizeChange"
        />
      </div>
    </el-card>
    <FilePreviewDialog ref="previewRef" />
  </div>
</template>

<style scoped>
.page { padding: 18px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.titleWrap { display: flex; align-items: center; gap: 8px; }
.titleIcon { font-size: 20px; color: var(--g-text); }
.title { font-size: 18px; font-weight: 800; color: var(--g-text); letter-spacing: -0.02em; }
.actions { display: flex; align-items: center; gap: 10px; }
.card { border-radius: var(--g-radius-md); border: 1px solid var(--g-border); }


.footer-bar {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 16px;
}

.pagination {
  margin-top: 0;
}

.summary-bar {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--g-text-secondary);
}

.summary-amount {
  font-weight: 600;
  color: var(--g-warning);
}

.op-col { margin-left: -6px; }
.more-btn { margin-left: 12px; }

.two-line-text {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  line-height: 18px;
  max-height: 36px;
  white-space: normal;
  word-break: break-all;
}
</style>


