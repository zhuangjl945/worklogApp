<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { OfficeBuilding } from '@element-plus/icons-vue'
import { supplierDisable, supplierEnable, supplierPage } from '../api/supplier'

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

function onReset() {
  filters.supplierCode = ''
  filters.supplierName = ''
  filters.deleted = null
  filters.page = 1
  load()
}

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

    <el-card class="card" shadow="never">
      <div class="filter-bar">
        <el-form :model="filters" inline class="filter-form">
          <el-form-item label="供应商编码">
            <el-input v-model="filters.supplierCode" placeholder="精确匹配" clearable />
          </el-form-item>
          <el-form-item label="供应商名称">
            <el-input v-model="filters.supplierName" placeholder="模糊搜索" clearable />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="filters.deleted" placeholder="全部" clearable style="width: 120px">
              <el-option label="启用" :value="0" />
              <el-option label="禁用" :value="1" />
            </el-select>
          </el-form-item>
          <el-form-item class="action-buttons">
            <el-button type="primary" @click="load">查询</el-button>
            <el-button @click="onReset">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

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
.titleIcon { font-size: 22px; color: #3b82f6; }
.title { font-size: 18px; font-weight: 900; color: #111827; }
.actions { display: flex; align-items: center; gap: 10px; }
.card { border-radius: 14px; }
.filter-bar { margin-bottom: 14px; }
.filter-form { display: flex; flex-wrap: wrap; gap: 10px; }
.pagination { margin-top: 14px; }
.row-disabled {
  color: #9ca3af;
}
</style>
