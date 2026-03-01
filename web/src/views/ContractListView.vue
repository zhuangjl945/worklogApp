<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Tickets } from '@element-plus/icons-vue'
import { contractDelete, contractPage, contractStart, contractComplete, contractTerminate, contractRenew } from '../api/contract'
import { me } from '../api/auth'

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

function onReset() {
  filters.contractNo = ''
  filters.contractName = ''
  filters.status = null
  filters.page = 1
  load()
}

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

// 剩余金额（元）
function getRemainAmount(row) {
  const total = Number(row.contractAmount ?? 0)
  const paid = Number(row.paidAmount ?? 0)
  if (!Number.isFinite(total) || !Number.isFinite(paid)) return 0
  return total - paid
}

// 当前列表所有合同的剩余金额总和（元）
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
    </div>

    <el-card class="card" shadow="never">
      <div class="filter-bar">
        <el-form :model="filters" inline class="filter-form">
          <el-form-item label="合同编号">
            <el-input v-model="filters.contractNo" placeholder="精确匹配" clearable />
          </el-form-item>
          <el-form-item label="合同名称">
            <el-input v-model="filters.contractName" placeholder="模糊搜索" clearable />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="filters.status" clearable placeholder="选择状态" style="width: 160px" @change="load">
              <el-option v-for="opt in statusOptions" :key="String(opt.value)" :value="opt.value" :label="opt.label" />
            </el-select>
          </el-form-item>
          <el-form-item class="action-buttons">
            <el-button type="primary" @click="load">查询</el-button>
            <el-button @click="onReset">重置</el-button>
          </el-form-item>
          <el-form-item style="margin-left: auto; margin-right: 0;">
            <el-button type="primary" @click="$router.push('/contract/edit')">新增合同</el-button>
            <el-button @click="load">刷新</el-button>
          </el-form-item>
        </el-form>
      </div>

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
            <!-- 一次性付款合同不走分期计划，避免显示 0.00万 0/0，这里只展示文案 -->
            <div v-if="row.paymentTerms === 1" style="font-size: 12px; color: #909399;">
              一次性付款
            </div>
            <div v-else style="display: flex; flex-direction: column; gap: 4px;">
              <span class="amount">¥{{ formatWan(row.paidAmount) }}万</span>
              <span style="font-size: 12px; color: #909399;">次数: {{ row.paidPlanCount ?? 0 }} / {{ row.totalPlanCount ?? 0 }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="剩余金额" width="100">
          <template #default="{ row }">
            <span style="color: #f56c6c; font-weight: 600;">
              ¥{{ formatWan(getRemainAmount(row)) }}万
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="startDate" label="开始" width="110" />
        <el-table-column prop="endDate" label="结束" width="110" />
        <el-table-column label="附件" width="60" :cell-class-name="() => 'no-row-click'">
          <template #default="{ row }">
            <template v-if="row.contractFileUrl">
              <template v-if="(() => { try { const p = JSON.parse(row.contractFileUrl); return Array.isArray(p) && p.length > 1 } catch { return false } })()">
                <el-popover placement="top" :width="220" trigger="hover">
                  <template #reference>
                    <el-link type="primary" @click.stop>查看({{ (() => { try { return JSON.parse(row.contractFileUrl).length } catch { return 1 } })() }})</el-link>
                  </template>
                  <div v-for="(u, idx) in (() => { try { const p = JSON.parse(row.contractFileUrl); return Array.isArray(p) ? p : [row.contractFileUrl] } catch { return [row.contractFileUrl] } })()" :key="idx" style="margin: 4px 0">
                    <el-link :href="u" target="_blank" rel="noopener" type="primary" size="small" @click.stop>附件 {{ idx + 1 }}</el-link>
                  </div>
                </el-popover>
              </template>
              <el-link v-else :href="(() => { try { const p = JSON.parse(row.contractFileUrl); return Array.isArray(p) ? p[0] : row.contractFileUrl } catch { return row.contractFileUrl } })()" target="_blank" rel="noopener" type="primary" @click.stop>查看</el-link>
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
          <span>当前列表剩余金额合计：</span>
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
  </div>
</template>

<style scoped>
.page { padding: 18px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.titleWrap { display: flex; align-items: center; gap: 8px; }
.titleIcon { font-size: 22px; color: #3b82f6; }
.title { font-size: 18px; font-weight: 900; color: #111827; }
.actions { display: flex; align-items: center; gap: 10px; }
.card { border-radius: 14px; }
.filter-bar { margin-bottom: 10px; }
.filter-form { display: flex; flex-wrap: wrap; gap: 10px; }


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
  color: #4b5563;
}

.summary-amount {
  font-weight: 600;
  color: #d97706;
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
