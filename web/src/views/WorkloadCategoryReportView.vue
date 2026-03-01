<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, RefreshRight, TrendCharts, Download } from '@element-plus/icons-vue'
import * as XLSX from 'xlsx'
import { workRecordStatsUserDeptCategory } from '../api/work'
import { deptMyRootChildren } from '../api/dept'
import { me } from '../api/auth'

const loading = ref(false)
const errorMsg = ref('')

function pad(n) {
  return String(n).padStart(2, '0')
}

function formatDateTime(d) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function getMonthRange() {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth(), 1, 0, 0, 0)
  const end = new Date(now.getFullYear(), now.getMonth() + 1, 0, 23, 59, 59)
  return {
    from: formatDateTime(start),
    to: formatDateTime(end)
  }
}

const monthRange = getMonthRange()

const defaultDeptId = ref(null)

const form = reactive({
  createTimeFrom: monthRange.from,
  createTimeTo: monthRange.to,
  deptId: null
})

const rows = ref([])

// 计算合并单元格的逻辑
const spanArr = ref([])
const personSpanArr = ref([])

function getSpanData(data) {
  spanArr.value = []
  personSpanArr.value = []
  let pos = 0
  let pPos = 0
  
  for (let i = 0; i < data.length; i++) {
    if (i === 0) {
      spanArr.value.push(1)
      personSpanArr.value.push(1)
      pos = 0
      pPos = 0
    } else {
      // 合并科室
      if (data[i].deptName === data[i - 1].deptName) {
        spanArr.value[pos] += 1
        spanArr.value.push(0)
      } else {
        spanArr.value.push(1)
        pos = i
      }
      // 合并人员（在同科室前提下）
      if (data[i].realName === data[i - 1].realName && data[i].deptName === data[i - 1].deptName) {
        personSpanArr.value[pPos] += 1
        personSpanArr.value.push(0)
      } else {
        personSpanArr.value.push(1)
        pPos = i
      }
    }
  }
}

function objectSpanMethod({ rowIndex, columnIndex }) {
  // 第一列：人员
  if (columnIndex === 0) {
    const _row = personSpanArr.value[rowIndex]
    const _col = _row > 0 ? 1 : 0
    return { rowspan: _row, colspan: _col }
  }
  // 第二列：科室
  if (columnIndex === 1) {
    const _row = spanArr.value[rowIndex]
    const _col = _row > 0 ? 1 : 0
    return { rowspan: _row, colspan: _col }
  }
}

const deptOptions = ref([])

const deptFlatOptions = computed(() =>
  (deptOptions.value || []).map((d) => ({
    value: d.id,
    label: `${d.deptName} (${d.deptCode})`
  }))
)

const hasData = computed(() => Array.isArray(rows.value) && rows.value.length > 0)

function normalizeEndOfDay(dateTimeStr) {
  if (!dateTimeStr) return dateTimeStr
  const d = new Date(dateTimeStr)
  if (Number.isNaN(d.getTime())) return dateTimeStr
  d.setHours(23, 59, 59, 0)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} 23:59:59`
}

function formatYuan(amount) {
  const n = Number(amount ?? 0)
  if (Number.isNaN(n)) return '￥0.00'
  return `￥${n.toFixed(2)}`
}

function getSummaries(param) {
  const { columns, data } = param
  const sums = []
  for (let i = 0; i < columns.length; i++) {
    const column = columns[i]
    if (i === 0) {
      sums[i] = '合计'
      continue
    }

    if (column.property === 'totalCount') {
      const total = (data || []).reduce((acc, row) => acc + Number(row.totalCount ?? 0), 0)
      sums[i] = String(total)
      continue
    }

    if (column.property === 'totalAmount') {
      const total = (data || []).reduce((acc, row) => acc + Number(row.totalAmount ?? 0), 0)
      sums[i] = formatYuan(total)
      continue
    }

    sums[i] = ''
  }
  return sums
}

async function loadData() {
  loading.value = true
  errorMsg.value = ''
  try {
    const params = {}
    if (form.createTimeFrom) params.endTimeFrom = form.createTimeFrom
    if (form.createTimeTo) params.endTimeTo = form.createTimeTo

    const deptId = form.deptId != null ? form.deptId : defaultDeptId.value
    if (deptId != null) params.deptId = deptId

    const resp = await workRecordStatsUserDeptCategory(params)
    rows.value = resp.data || []
    getSpanData(rows.value)
  } catch (e) {
    errorMsg.value = e?.message || '加载统计失败'
    ElMessage.error(errorMsg.value)
  } finally {
    loading.value = false
  }
}

function onReset() {
  const r = getMonthRange()
  form.createTimeFrom = r.from
  form.createTimeTo = r.to
  form.deptId = defaultDeptId.value
  loadData()
}

async function loadDepts() {
  try {
    const resp = await deptMyRootChildren()
    deptOptions.value = resp.data || []
  } catch (e) {
    ElMessage.error(e?.message || '加载科室失败')
  }
}

function buildExportRows(data) {
  return (data || []).map((r) => ({
    人员: r.realName ?? '',
    科室: r.deptName ?? '',
    工作分类: r.categoryName ?? '',
    合计数: Number(r.totalCount ?? 0),
    合计消耗金额: Number(r.totalAmount ?? 0)
  }))
}

function buildExportFileName() {
  const safe = (s) => String(s || '').replaceAll(':', '-').replaceAll(' ', '_')
  const from = safe(form.createTimeFrom)
  const to = safe(form.createTimeTo)
  return `工作量统计_按人员科室分类_${from}__${to}.xlsx`
}

function onExportExcel() {
  if (!hasData.value) {
    ElMessage.warning('暂无数据可导出')
    return
  }
  try {
    const exportRows = buildExportRows(rows.value)
    const ws = XLSX.utils.json_to_sheet(exportRows)

    ws['!cols'] = [{ wch: 12 }, { wch: 16 }, { wch: 18 }, { wch: 10 }, { wch: 16 }]

    for (let r = 1; r <= exportRows.length; r++) {
      const amountCell = ws[XLSX.utils.encode_cell({ r, c: 4 })]
      if (amountCell) amountCell.z = '"￥"0.00'
    }

    const wb = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(wb, ws, '工作量统计')
    XLSX.writeFile(wb, buildExportFileName())
  } catch (e) {
    ElMessage.error(e?.message || '导出失败')
  }
}

async function init() {
  try {
    const resp = await me()
    defaultDeptId.value = resp?.data?.deptId ?? null
    form.deptId = defaultDeptId.value
  } catch {
    // ignore
  }
  await loadDepts()
  await loadData()
}

init()
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><TrendCharts /></el-icon>
        <div class="title">工作量统计</div>
      </div>
      <div class="subtitle">按人员 / 科室 / 工作分类统计工作量与费用汇总</div>
    </div>

    <el-card class="card" shadow="never">
      <div class="filters">
        <el-form :model="form" inline class="filter-form">
          <el-form-item label="创建时间从">
            <el-date-picker
              v-model="form.createTimeFrom"
              type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss"
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="开始时间"
              clearable
            />
          </el-form-item>
          <el-form-item label="创建时间到">
            <el-date-picker
              :model-value="form.createTimeTo"
              @update:model-value="(v) => (form.createTimeTo = normalizeEndOfDay(v))"
              type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss"
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="结束时间"
              clearable
            />
          </el-form-item>

          <el-form-item label="科室">
            <el-select
              v-model="form.deptId"
              filterable
              clearable
              placeholder="请选择科室"
              style="min-width: 220px"
            >
              <el-option v-for="opt in deptFlatOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </el-form-item>


          <el-form-item class="action-buttons">
            <el-button type="primary" :icon="Search" :loading="loading" @click="loadData">查询</el-button>
            <el-button :icon="RefreshRight" :disabled="loading" @click="onReset">重置</el-button>
            <el-button :icon="Download" :disabled="loading || !hasData" @click="onExportExcel">导出Excel</el-button>
          </el-form-item>
        </el-form>
      </div>

      <div v-if="errorMsg" class="error-text">{{ errorMsg }}</div>

      <div v-else-if="!loading && !hasData" class="empty-text">暂无数据</div>

      <el-table
        v-else
        v-loading="loading"
        :data="rows"
        border
        stripe
        show-summary
        :span-method="objectSpanMethod"
        :summary-method="getSummaries"
        style="width: 100%"
        :default-sort="{ prop: 'deptName', order: 'ascending' }"
      >
        <el-table-column prop="realName" label="人员" min-width="120" />
        <el-table-column prop="deptName" label="科室" min-width="140" />
        <el-table-column prop="categoryName" label="工作分类" min-width="160" />
        <el-table-column prop="totalCount" label="合计数" width="110" sortable />
        <el-table-column prop="totalAmount" label="合计消耗金额" width="150" sortable>
          <template #default="scope">
            {{ formatYuan(scope.row.totalAmount) }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  padding: 18px;
}

.toolbar {
  margin-bottom: 14px;
}

.titleWrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.titleIcon {
  font-size: 22px;
  color: #3b82f6;
}

.title {
  font-size: 18px;
  font-weight: 900;
  color: #111827;
}

.subtitle {
  margin-top: 6px;
  font-size: 12px;
  color: #667085;
}

.card {
  border-radius: 14px;
}

.filters {
  margin-bottom: 12px;
  padding: 12px;
  border-radius: 12px;
  border: 1px solid #e9edf5;
  background: #fafbff;
}

.filter-form {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 14px;
}

.filter-form .action-buttons {
  margin-top: auto;
  padding-bottom: 2px;
}

.error-text {
  text-align: center;
  color: #b42318;
  padding: 18px;
  border: 1px dashed #fda29b;
  border-radius: 12px;
  background: #fffbfa;
  margin-bottom: 12px;
}

.empty-text {
  text-align: center;
  color: #667085;
  padding: 22px;
  border: 1px dashed #d0d5dd;
  border-radius: 12px;
  background: #fcfcfd;
  margin-bottom: 12px;
}
</style>
