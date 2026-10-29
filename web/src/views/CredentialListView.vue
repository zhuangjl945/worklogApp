<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Lock, Search, Plus, View, Edit, Delete, CopyDocument, Key } from '@element-plus/icons-vue'
import {
  credentialList,
  credentialCreate,
  credentialUpdate,
  credentialDetail,
  credentialReveal,
  credentialDelete
} from '../api/credential'

// ------------------------------------------------------------------
// 列表与分页
// ------------------------------------------------------------------

const loading = ref(false)
const tableData = ref([])
const total = ref(0)

const filters = reactive({
  page: 1,
  size: 15,
  keyword: '',
  category: ''
})

// 分类选项（与后端枚举保持一致）
const categoryOptions = [
  { value: 'database', label: '数据库' },
  { value: 'server', label: '服务器' },
  { value: 'app', label: '应用系统' },
  { value: 'network', label: '网络设备' },
  { value: 'other', label: '其他' }
]

// 分类值 -> 标签颜色
const categoryTagType = (cat) => {
  const map = { database: '', server: 'success', app: 'warning', network: 'info', other: 'danger' }
  return map[cat] || 'info'
}

// 分类值 -> 显示名
const categoryLabel = (cat) => {
  return categoryOptions.find((o) => o.value === cat)?.label || cat
}

async function load() {
  loading.value = true
  try {
    const resp = await credentialList({
      page: filters.page,
      size: filters.size,
      keyword: filters.keyword || undefined,
      category: filters.category || undefined
    })
    tableData.value = resp.data.records
    total.value = resp.data.total
  } catch (e) {
    ElMessage.error(e?.message || '加载凭据列表失败')
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

function onSearch() {
  filters.page = 1
  load()
}

function onCategoryChange() {
  filters.page = 1
  load()
}

// ------------------------------------------------------------------
// 过期日状态：红色=已过期，黄色=30天内到期，否则正常色
// ------------------------------------------------------------------

function expiryStatus(dateStr) {
  if (!dateStr) return 'none'
  const d = new Date(dateStr)
  const now = new Date()
  const diff = (d - now) / (1000 * 60 * 60 * 24)
  if (diff < 0) return 'expired'
  if (diff <= 30) return 'warning'
  return 'normal'
}

// ------------------------------------------------------------------
// 新增/编辑弹窗
// ------------------------------------------------------------------

const dialogVisible = ref(false)
const dialogMode = ref('create')
const dialogLoading = ref(false)
const formRef = ref()
const editingId = ref(null)

const form = reactive({
  name: '',
  category: 'other',
  account: '',
  password: '',
  secretKey: '',
  url: '',
  tags: '',
  expireDate: '',
  notes: ''
})

const formRules = {
  name: [{ required: true, message: '请输入凭据名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }]
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  form.name = ''
  form.category = 'other'
  form.account = ''
  form.password = ''
  form.secretKey = ''
  form.url = ''
  form.tags = ''
  form.expireDate = ''
  form.notes = ''
  dialogVisible.value = true
}

async function openEdit(row) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  dialogLoading.value = true
  dialogVisible.value = true
  try {
    const resp = await credentialDetail(row.id)
    const d = resp.data
    form.name = d.name || ''
    form.category = d.category || 'other'
    form.account = d.account || ''
    // 详情接口返回的密码/密钥是脱敏的 null，编辑时留空表示不修改
    form.password = ''
    form.secretKey = ''
    form.url = d.url || ''
    form.tags = d.tags || ''
    form.expireDate = d.expireDate || ''
    form.notes = d.notes || ''
  } catch (e) {
    ElMessage.error(e?.message || '加载凭据详情失败')
    dialogVisible.value = false
  } finally {
    dialogLoading.value = false
  }
}

async function onSubmit() {
  if (!formRef.value) return
  await formRef.value.validate()
  dialogLoading.value = true
  try {
    const payload = { ...form }
    // 编辑时密码/密钥留空表示不修改，不传给后端
    if (dialogMode.value === 'edit') {
      if (!payload.password) delete payload.password
      if (!payload.secretKey) delete payload.secretKey
    }
    if (dialogMode.value === 'create') {
      await credentialCreate(payload)
      ElMessage.success('凭据已创建')
    } else {
      await credentialUpdate(editingId.value, payload)
      ElMessage.success('凭据已更新')
    }
    dialogVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    dialogLoading.value = false
  }
}

// ------------------------------------------------------------------
// 查看密码弹窗
// ------------------------------------------------------------------

const revealVisible = ref(false)
const revealLoading = ref(false)
const revealValue = ref('')
const revealLabel = ref('')

async function openReveal(row) {
  try {
    await ElMessageBox.confirm(
      '查看明文将记录操作日志，确认继续？',
      '安全确认',
      { confirmButtonText: '确认查看', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    // 用户取消
    return
  }
  revealLoading.value = true
  revealValue.value = ''
  revealLabel.value = row.name
  revealVisible.value = true
  try {
    const resp = await credentialReveal(row.id)
    const d = resp.data
    // 优先显示密码，其次显示密钥
    revealValue.value = d.password || d.secretKey || '(无密文)'
  } catch (e) {
    ElMessage.error(e?.message || '解密失败')
    revealVisible.value = false
  } finally {
    revealLoading.value = false
  }
}

function copyReveal() {
  if (!revealValue.value) return
  navigator.clipboard.writeText(revealValue.value).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败，请手动复制')
  })
}

// ------------------------------------------------------------------
// 删除
// ------------------------------------------------------------------

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      '确定删除该凭据？此操作不可恢复。',
      '确认删除',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await credentialDelete(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <!-- 页头 -->
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Lock /></el-icon>
        <div class="title">凭据保管库</div>
      </div>
      <div class="actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">新增凭据</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filterBar">
      <el-select
        v-model="filters.category"
        placeholder="全部分类"
        clearable
        style="width: 160px"
        @change="onCategoryChange"
      >
        <el-option v-for="o in categoryOptions" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-input
        v-model="filters.keyword"
        placeholder="搜索名称/账号/地址…"
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

    <!-- 表格 -->
    <el-card class="card" shadow="never">
      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="name" label="凭据名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="category" label="分类" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="categoryTagType(row.category)" size="small">
              {{ categoryLabel(row.category) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="account" label="账号" min-width="130" show-overflow-tooltip />
        <el-table-column label="密码/密钥" width="100" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :icon="Key" @click="openReveal(row)">
              查看
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="url" label="访问地址" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <a v-if="row.url" :href="row.url" target="_blank" rel="noopener" class="linkUrl">
              {{ row.url }}
            </a>
            <span v-else class="textFaint">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="expireDate" label="过期日" width="120" align="center">
          <template #default="{ row }">
            <span :class="'expiry-' + expiryStatus(row.expireDate)">
              {{ row.expireDate || '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" :icon="Delete" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pager">
        <el-pagination
          v-model:current-page="filters.page"
          v-model:page-size="filters.size"
          :total="total"
          :page-sizes="[10, 15, 30, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新增凭据' : '编辑凭据'"
      width="580px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="formRules"
        label-width="90px"
        v-loading="dialogLoading"
      >
        <el-form-item label="凭据名称" prop="name">
          <el-input v-model="form.name" placeholder="如：HIS 数据库" maxlength="100" />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="o in categoryOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="账号">
          <el-input v-model="form.account" placeholder="登录账号" maxlength="200" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="dialogMode === 'edit' ? '留空表示不修改' : '密码'"
            maxlength="500"
          />
        </el-form-item>
        <el-form-item label="密钥">
          <el-input
            v-model="form.secretKey"
            type="password"
            show-password
            :placeholder="dialogMode === 'edit' ? '留空表示不修改' : '密钥/Token'"
            maxlength="2000"
          />
        </el-form-item>
        <el-form-item label="访问地址">
          <el-input v-model="form.url" placeholder="https://..." maxlength="500" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="逗号分隔，如：生产,核心" maxlength="200" />
        </el-form-item>
        <el-form-item label="过期日期">
          <el-date-picker
            v-model="form.expireDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择过期日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.notes" type="textarea" :rows="3" placeholder="补充说明" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dialogLoading" @click="onSubmit">
          {{ dialogMode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 查看密码弹窗 -->
    <el-dialog
      v-model="revealVisible"
      title="凭据明文"
      width="460px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div v-loading="revealLoading" class="revealBody">
        <div class="revealLabel">{{ revealLabel }}</div>
        <div class="revealValue">
          <code>{{ revealValue || '—' }}</code>
        </div>
        <el-button
          v-if="revealValue"
          type="primary"
          :icon="CopyDocument"
          size="small"
          style="margin-top: 12px"
          @click="copyReveal"
        >
          复制到剪贴板
        </el-button>
      </div>
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
  flex-wrap: wrap;
  gap: 4px;
}

.card {
  border-radius: 8px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.linkUrl {
  color: var(--el-color-primary);
  text-decoration: none;
}

.linkUrl:hover {
  text-decoration: underline;
}

.textFaint {
  color: var(--g-text-faint, #bbb);
}

/* 过期日状态色 */
.expiry-expired {
  color: var(--el-color-danger);
  font-weight: 600;
}

.expiry-warning {
  color: var(--el-color-warning);
  font-weight: 600;
}

.expiry-normal {
  color: inherit;
}

.expiry-none {
  color: var(--g-text-faint, #bbb);
}

/* 查看密码弹窗 */
.revealBody {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-height: 80px;
}

.revealLabel {
  font-size: 14px;
  color: var(--g-text-secondary, #666);
  margin-bottom: 8px;
}

.revealValue {
  background: var(--g-bg-muted, #f5f5f5);
  border-radius: 6px;
  padding: 10px 14px;
  word-break: break-all;
  font-size: 15px;
  width: 100%;
  box-sizing: border-box;
}

.revealValue code {
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 15px;
  color: var(--g-text, #333);
}
</style>
