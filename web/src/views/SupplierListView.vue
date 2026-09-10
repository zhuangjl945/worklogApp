<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { OfficeBuilding, Search } from '@element-plus/icons-vue'
import { supplierDisable, supplierEnable, supplierPage } from '../api/supplier'
import QueryConsole from '../components/QueryConsole.vue'
import { useQueryConsole } from '../composables/useQueryConsole'

const loading = ref(false)
const tableData = ref([])
const total = ref(0)

const filters = reactive({
  page: 1,
  size: 10,
  supplierCode: '',
  supplierName: '',
  deleted: null
})

async function load() {
  loading.value = true
  try {
    const resp = await supplierPage({
      page: filters.page,
      size: filters.size,
      supplierCode: filters.supplierCode || undefined,
      supplierName: filters.supplierName || undefined,
      deleted: filters.deleted !== null ? filters.deleted : undefined
    })
    tableData.value = resp.data.records
    total.value = resp.data.total
  } catch (e) {
    ElMessage.error(e?.message || '加载供应商列表失败')
  } finally {
    loading.value = false
  }
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

// ------------------------------------------------------------
// 检索台：条件一变就自动重查，不再需要「查询」按钮
// ------------------------------------------------------------

// 只对筛选字段敏感，翻页、改每页条数不应触发重查
const filterSignature = computed(() =>
  JSON.stringify({
    supplierCode: filters.supplierCode.trim(),
    supplierName: filters.supplierName.trim(),
    deleted: filters.deleted
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
  filters.supplierCode = ''
  filters.supplierName = ''
  filters.deleted = null
  runSearch()
}

// deleted 是库里的字段名，界面上说人话：启用 / 禁用
const statusPills = [
  { value: 0, label: '启用' },
  { value: 1, label: '禁用' }
]

function pickStatus(value) {
  filters.deleted = filters.deleted === value ? null : value
}

const filterChips = computed(() => {
  const chips = []
  const name = filters.supplierName.trim()
  if (name) chips.push({ key: 'supplierName', field: '名称', value: `含「${name}」` })
  const code = filters.supplierCode.trim()
  if (code) chips.push({ key: 'supplierCode', field: '编码', value: code })
  if (filters.deleted !== null) {
    chips.push({ key: 'deleted', field: '状态', value: statusPills.find((x) => x.value === filters.deleted).label })
  }
  return chips
})

function removeFilterChip(key) {
  if (key === 'supplierName') filters.supplierName = ''
  else if (key === 'supplierCode') filters.supplierCode = ''
  else if (key === 'deleted') filters.deleted = null
}

const hiddenActiveFilterCount = computed(() => {
  if (filterExpanded.value) return 0
  return filters.supplierCode.trim() ? 1 : 0
})

async function onStatusChange(row, newValue) {
  const isEnabling = newValue === 0
  const actionText = isEnabling ? '启用' : '禁用'
  
  // 记录旧值以便失败时回滚
  const oldValue = row.deleted === 0 ? 0 : 1
  
  try {
    if (isEnabling) {
      await supplierEnable(row.id)
    } else {
      await supplierDisable(row.id)
    }
    ElMessage.success(`${row.supplierName}已${actionText}`)
    // 更新本地状态
    row.deleted = newValue
  } catch (e) {
    // 失败回滚
    row.deleted = oldValue
    ElMessage.error(e?.message || `${actionText}失败`)
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><OfficeBuilding /></el-icon>
        <div class="title">供应商列表</div>
      </div>
      <div class="actions">
        <el-button type="primary" @click="$router.push('/supplier/edit')">新增供应商</el-button>
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
        <el-input v-model="filters.supplierName" class="qc-search" placeholder="搜索供应商名称，回车即查" clearable @keyup.enter="runSearch">
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
            :class="{ 'is-on': filters.deleted === opt.value }"
            :aria-pressed="filters.deleted === opt.value"
            @click="pickStatus(opt.value)"
          >
            <span class="qc-pill-dot" aria-hidden="true" />{{ opt.label }}
          </button>
        </div>
      </template>

      <template #more>
        <div class="qc-grid">
          <div class="qc-cell">
            <span class="qc-flabel">供应商编码</span>
            <el-input v-model="filters.supplierCode" placeholder="完整编码，精确匹配" clearable @keyup.enter="runSearch" />
          </div>
        </div>
      </template>
    </QueryConsole>

    <el-card class="card" shadow="never">

      <el-table v-loading="loading" :data="tableData" border stripe :row-class-name="({ row }) => row.deleted === 1 ? 'row-disabled' : ''">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="supplierCode" label="供应商编码" width="160" />
        <el-table-column prop="supplierName" label="供应商名称" min-width="220" />
        <el-table-column prop="bankName" label="开户行" min-width="180" />
        <el-table-column prop="bankAccount" label="账号" min-width="180" />
        <el-table-column prop="contactPhone" label="联系电话" width="140" />
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-switch
              :model-value="row.deleted === 0"
              :active-text="'启'"
              :inactive-text="'停'"
              inline-prompt
              :disabled="loading"
              @change="(val) => onStatusChange(row, val ? 0 : 1)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button 
              size="small" 
              type="primary" 
              :disabled="row.deleted === 1"
              @click="$router.push(`/supplier/edit/${row.id}`)"
            >编辑</el-button>
            <el-button 
              size="small" 
              :disabled="row.deleted === 1"
              @click="$router.push(`/supplier/edit/${row.id}?tab=contacts`)"
            >联系人</el-button>
          </template>
        </el-table-column>
      </el-table>

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
    </el-card>
  </div>
</template>

<style scoped>
.page { padding: 18px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.titleWrap { display: flex; align-items: center; gap: 8px; }
.titleIcon { font-size: 22px; color: var(--g-text); }
.title { font-size: 18px; font-weight: 900; color: var(--g-text); }
.actions { display: flex; align-items: center; gap: 10px; }
.card { border-radius: var(--g-radius-md); }
.pagination { margin-top: 14px; }
.row-disabled {
  color: var(--g-text-faint);
}
</style>

