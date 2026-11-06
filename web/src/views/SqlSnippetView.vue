<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Delete, DocumentCopy, Edit, Plus, Search } from '@element-plus/icons-vue'
import { sqlSnippetCategories, sqlSnippetCreate, sqlSnippetDelete, sqlSnippetList, sqlSnippetUpdate } from '../api/sqlSnippet'

const DB_TYPES = [
  { value: 'mysql', label: 'MySQL' },
  { value: 'oracle', label: 'Oracle' },
  { value: 'postgresql', label: 'PostgreSQL' },
  { value: 'sqlserver', label: 'SQL Server' },
  { value: 'dm', label: '达梦 DM' },
  { value: 'kingbase', label: '人大金仓' }
]

const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const categories = ref([])
const filters = reactive({ page: 1, size: 15, keyword: '', category: '', dbType: '' })

const dialogVisible = ref(false)
const dialogMode = ref('create')
const dialogLoading = ref(false)
const formRef = ref()
const editingId = ref(null)
const form = reactive({ title: '', category: '', dbType: '', sqlText: '', remark: '' })
const formRules = {
  title: [{ required: true, message: '请填写标题', trigger: 'blur' }],
  sqlText: [{ required: true, message: '请填写 SQL', trigger: 'blur' }]
}

const previewVisible = ref(false)
const previewRow = ref(null)

function dbTypeLabel(value) {
  return DB_TYPES.find((item) => item.value === value)?.label || ''
}

async function loadCategories() {
  try {
    const resp = await sqlSnippetCategories()
    categories.value = resp.data || []
  } catch {
    categories.value = []
  }
}

async function load() {
  loading.value = true
  try {
    const resp = await sqlSnippetList({
      page: filters.page,
      size: filters.size,
      keyword: filters.keyword || undefined,
      category: filters.category || undefined,
      dbType: filters.dbType || undefined
    })
    const data = resp.data || {}
    tableData.value = data.records || []
    total.value = data.total || 0
    if (!tableData.value.length && filters.page > 1) {
      filters.page -= 1
      await load()
    }
  } catch (e) {
    ElMessage.error(e?.message || '加载常用 SQL 失败')
  } finally {
    loading.value = false
  }
}

function onSearch() {
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

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  form.title = ''
  form.category = ''
  form.dbType = ''
  form.sqlText = ''
  form.remark = ''
  dialogVisible.value = true
}

function openEdit(row) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  form.title = row.title || ''
  form.category = row.category || ''
  form.dbType = row.dbType || ''
  form.sqlText = row.sqlText || ''
  form.remark = row.remark || ''
  dialogVisible.value = true
}

function openPreview(row) {
  previewRow.value = row
  previewVisible.value = true
}

async function onSubmit() {
  if (!formRef.value) return
  await formRef.value.validate()
  dialogLoading.value = true
  try {
    const payload = {
      title: form.title,
      category: form.category,
      dbType: form.dbType,
      sqlText: form.sqlText,
      remark: form.remark
    }
    if (dialogMode.value === 'create') {
      await sqlSnippetCreate(payload)
      ElMessage.success('已保存')
    } else {
      await sqlSnippetUpdate(editingId.value, payload)
      ElMessage.success('已更新')
    }
    dialogVisible.value = false
    load()
    loadCategories()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
  } finally {
    dialogLoading.value = false
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      '确定删除「' + (row.title || '') + '」吗？删除后不能恢复。',
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await sqlSnippetDelete(row.id)
    ElMessage.success('已删除')
    loadCategories()
    if (previewRow.value && previewRow.value.id === row.id) {
      previewVisible.value = false
      previewRow.value = null
    }
    load()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

async function copySql(text) {
  const value = text || ''
  if (!value) {
    ElMessage.warning('这条语句是空的')
    return
  }
  try {
    await navigator.clipboard.writeText(value)
  } catch {
    const area = document.createElement('textarea')
    area.value = value
    area.setAttribute('readonly', 'readonly')
    document.body.appendChild(area)
    area.select()
    document.execCommand('copy')
    document.body.removeChild(area)
  }
  ElMessage.success('已复制')
}

function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 19)
}

onMounted(() => {
  load()
  loadCategories()
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><DocumentCopy /></el-icon>
        <div class="title">常用 SQL</div>
      </div>
      <div class="actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">新增语句</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <div class="filterBar">
      <el-select
        v-model="filters.category"
        clearable
        filterable
        placeholder="全部分类"
        style="width: 160px"
        @change="onSearch"
      >
        <el-option v-for="name in categories" :key="name" :label="name" :value="name" />
      </el-select>
      <el-select
        v-model="filters.dbType"
        clearable
        placeholder="全部数据库"
        style="width: 150px; margin-left: 8px"
        @change="onSearch"
      >
        <el-option v-for="item in DB_TYPES" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-input
        v-model="filters.keyword"
        placeholder="搜索标题、SQL、分类或备注"
        clearable
        style="width: 280px; margin-left: 8px"
        @keyup.enter="onSearch"
        @clear="onSearch"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-button style="margin-left: 8px" @click="onSearch">搜索</el-button>
    </div>

    <el-card class="card" shadow="never">
      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column label="分类" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.category">{{ row.category }}</span>
            <span v-else class="textFaint">—</span>
          </template>
        </el-table-column>
        <el-table-column label="数据库" width="120" align="center">
          <template #default="{ row }">
            <span v-if="dbTypeLabel(row.dbType)">{{ dbTypeLabel(row.dbType) }}</span>
            <span v-else class="textFaint">—</span>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button link type="primary" @click="openPreview(row)">{{ row.title }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.remark">{{ row.remark }}</span>
            <span v-else class="textFaint">—</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="180" align="center">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :icon="CopyDocument" @click="copySql(row.sqlText)">复制</el-button>
            <el-button link type="primary" size="small" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" :icon="Delete" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="filters.page"
          v-model:page-size="filters.size"
          :total="total"
          :page-sizes="[10, 15, 30, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新增语句' : '编辑语句'"
      width="680px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="96px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" maxlength="200" show-word-limit placeholder="方便以后搜到，比如：查在院人数" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select
            v-model="form.category"
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="选一个已有分类，或直接输入新分类"
            style="width: 100%"
          >
            <el-option v-for="name in categories" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据库类型">
          <el-select v-model="form.dbType" clearable placeholder="可以不选" style="width: 100%">
            <el-option v-for="item in DB_TYPES" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="SQL" prop="sqlText">
          <el-input
            v-model="form.sqlText"
            type="textarea"
            :rows="12"
            placeholder="把常用语句贴在这里"
            class="sqlInput"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="这条语句用在什么场合" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dialogLoading" @click="onSubmit">
          {{ dialogMode === 'create' ? '保存' : '更新' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="previewVisible" :title="previewRow?.title || '语句'" width="720px" destroy-on-close>
      <div v-if="previewRow" class="preview">
        <div v-if="previewRow.category || previewRow.dbType" class="previewMeta">
          <span v-if="previewRow.category">{{ previewRow.category }}</span>
          <span v-if="previewRow.category && previewRow.dbType"> · </span>
          <span v-if="previewRow.dbType">{{ dbTypeLabel(previewRow.dbType) }}</span>
        </div>
        <div v-if="previewRow.remark" class="previewRemark">{{ previewRow.remark }}</div>
        <pre class="sqlBlock">{{ previewRow.sqlText }}</pre>
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
        <el-button type="primary" :icon="CopyDocument" @click="copySql(previewRow?.sqlText)">复制 SQL</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.titleWrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.titleIcon {
  font-size: 22px;
  color: var(--g-text);
}

.title {
  font-size: 18px;
  font-weight: 600;
  color: var(--g-text);
}

.actions {
  display: flex;
  gap: 8px;
}

.filterBar {
  display: flex;
  align-items: center;
}

.card {
  border-radius: 8px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.textFaint {
  color: var(--g-text-faint, #bbb);
}

.sqlInput :deep(textarea) {
  font-family: Consolas, 'Courier New', monospace;
  line-height: 1.5;
}

.previewMeta {
  margin-bottom: 8px;
  color: var(--g-text);
}

.previewRemark {
  margin-bottom: 10px;
  color: var(--g-text-secondary, #666);
}

.sqlBlock {
  margin: 0;
  padding: 12px 14px;
  max-height: 420px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--g-bg-muted, #f5f5f5);
  border-radius: 6px;
  font-family: Consolas, 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.5;
}
</style>
