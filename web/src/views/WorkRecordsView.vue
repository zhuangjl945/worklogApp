<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, Search, Refresh, Connection, Document, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  workCategoryEnabled,
  workRecordCreate,
  workRecordDelete,
  workRecordPage,
  workRecordUpdate,
  workRecordUpdateStatus,
  workRecordTransferCreate,
  workStatusEnabled,
  ossPolicy,
  ossDeleteObject
} from '../api/work'
import { userPage } from '../api/user'
import { deptAllEnabled, deptMyRootChildren } from '../api/dept'
import WorkRecordDetailDrawer from './WorkRecordDetailDrawer.vue'

const props = defineProps({
  user: {
    type: Object,
    default: null
  }
})

const loading = ref(false)
const tableData = ref([])
const total = ref(0)

const categoryOptions = ref([])
const statusOptions = ref([])
const deptOptions = ref([])
const deptOptionsAll = ref([])

const categoryMap = computed(() => {
  const m = new Map()
  for (const c of categoryOptions.value) m.set(c.id, c.categoryName)
  return m
})

const statusMap = computed(() => {
  const m = new Map()
  for (const s of statusOptions.value) m.set(s.id, s.statusName)
  return m
})

const deptMap = computed(() => {
  const m = new Map()
  for (const d of deptOptionsAll.value) m.set(String(d.id), `${d.deptName}`)
  return m
})

const filters = reactive({
  page: 1,
  size: 10,
  categoryIds: [],
  bizDeptId: null,
  statusIds: [1, 2],
  createTimeRange: null,
  title: ''
})

function normalizeCreateTimeRange(v) {
  if (!v || !Array.isArray(v) || v.length !== 2) return v
  const [start, end] = v
  if (!end) return v
  const endDate = new Date(Number(end))
  endDate.setHours(23, 59, 59, 999)
  return [start, endDate.getTime()]
}

function formatDateTime(dt) {
  if (!dt) return null
  const pad = (n) => String(n).padStart(2, '0')
  const d = new Date(dt)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function buildQueryParams() {
  const params = {
    page: filters.page,
    size: filters.size,
    categoryIds: filters.categoryIds.length > 0 ? filters.categoryIds.join(',') : undefined,
    bizDeptId: filters.bizDeptId ?? undefined,
    statusIds: filters.statusIds.length > 0 ? filters.statusIds.join(',') : undefined,
    title: filters.title?.trim() ? `%${filters.title.trim()}%` : undefined
  }

  if (filters.createTimeRange && filters.createTimeRange.length === 2) {
    params.createTimeFrom = formatDateTime(filters.createTimeRange[0])
    params.createTimeTo = formatDateTime(filters.createTimeRange[1])
  }

  return params
}

async function loadOptions() {
  try {
    const [cats, sts, deptsAll, deptsMy] = await Promise.all([
      workCategoryEnabled(),
      workStatusEnabled(),
      deptAllEnabled(),
      deptMyRootChildren()
    ])
    categoryOptions.value = cats.data
    statusOptions.value = sts.data
    deptOptionsAll.value = deptsAll.data
    deptOptions.value = deptsMy.data
  } catch (e) {
    ElMessage.error(e?.message || '加载字典失败')
  }
}

async function load() {
  loading.value = true
  try {
    const resp = await workRecordPage(buildQueryParams())
    tableData.value = resp.data.records
    total.value = resp.data.total
  } catch (e) {
    ElMessage.error(e?.message || '加载工作记录失败')
  } finally {
    loading.value = false
  }
}

function onSearch() {
  filters.page = 1
  load()
}

function onReset() {
  filters.page = 1
  filters.size = 10
  filters.categoryIds = []
  filters.statusIds = []
  filters.createTimeRange = null
  filters.title = ''
  load()
}

function onPageChange(p) {
  filters.page = p
  load()
}

function onSizeChange(s) {
  filters.size = s
  filters.page = 1
  load()
}

function toggleStatusFilter(id, checked) {
  if (checked) {
    if (!filters.statusIds.includes(id)) {
      filters.statusIds.push(id)
    }
  } else {
    filters.statusIds = filters.statusIds.filter((i) => i !== id)
  }
  onSearch()
}

function getStatusTagType(statusId) {
  if (statusId === 1) return 'info'
  if (statusId === 2) return 'warning'
  if (statusId === 3) return 'success'
  if (statusId === 0) return 'danger'
  return ''
}

function extractImageUrls(content) {
  if (!content) return []
  const urls = []
  const md = /!\[[^\]]*\]\((https?:\/\/[^\s)]+)\)/g
  const html = /<img[^>]*?src=["'](https?:\/\/[^"']+)["'][^>]*?>/gi
  let m
  while ((m = md.exec(content)) !== null) {
    urls.push(m[1])
  }
  while ((m = html.exec(content)) !== null) {
    urls.push(m[1])
  }
  const uniq = []
  for (const u of urls) {
    if (!uniq.includes(u)) uniq.push(u)
  }
  return uniq
}

function getRowImageUrls(row) {
  if (!row) return []
  const urls = []
  if (row.imageUrls) {
    try {
      const arr = JSON.parse(row.imageUrls)
      if (Array.isArray(arr)) urls.push(...arr)
    } catch (e) {
      const arr = row.imageUrls.split(',').filter(Boolean)
      urls.push(...arr)
    }
  }
  // 兼容老数据：从 content 提取
  const contentUrls = extractImageUrls(row.content)
  contentUrls.forEach((u) => {
    if (!urls.includes(u)) urls.push(u)
  })
  return [...new Set(urls.filter(Boolean))]
}

const dialogs = reactive({ edit: false })
const editMode = ref('create')
const currentId = ref(null)
const formRef = ref()

const uploading = ref(false)
const previewUrl = ref('')
const originalImageUrls = ref([])

function parseUrls(v) {
  if (!v) return []
  try {
    const arr = JSON.parse(v)
    return Array.isArray(arr) ? arr.filter(Boolean) : []
  } catch {
    return String(v)
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
  }
}

async function handleCancelEdit(done) {
  let proceed = true
  try {
    await ElMessageBox.confirm('取消后将删除本次上传但未保存的图片，确定取消吗？', '提示', { type: 'warning' })
  } catch {
    proceed = false
  }
  if (!proceed) return

  const currentUrls = contentImageUrls.value
  const addedUrls = currentUrls.filter((u) => !originalImageUrls.value.includes(u))

  for (const url of addedUrls) {
    const key = urlToOssKey(url)
    if (!key) continue
    try {
      await ossDeleteObject({ key })
    } catch (e) {
      // 2A：静默失败，不阻塞关闭
      console.error('清理未保存图片失败:', e)
    }
  }

  dialogs.edit = false
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''

  if (typeof done === 'function') done()
}

const form = reactive({
  bizDeptId: null,
  categoryId: null,
  statusId: 1,
  title: '',
  content: '',
  imageUrls: '',
  isImportant: 0
})

const contentImageUrls = computed(() => {
  const urls = []
  if (form.imageUrls) {
    try {
      const arr = JSON.parse(form.imageUrls)
      if (Array.isArray(arr)) urls.push(...arr)
    } catch {
      if (form.imageUrls.includes(',')) {
        urls.push(...form.imageUrls.split(','))
      } else {
        urls.push(form.imageUrls)
      }
    }
  }
  // 兼容老数据：如果 imageUrls 为空，则从 content 提取
  if (urls.length === 0 && form.content) {
    urls.push(...extractImageUrls(form.content))
  }
  return [...new Set(urls.filter(Boolean))]
})

function urlToOssKey(url) {
  if (!url) return null
  try {
    const u = new URL(url)
    return u.pathname?.replace(/^\//, '') || null
  } catch {
    return null
  }
}

function removeImageRefFromContent(url) {
  if (!url) return
  const escaped = url.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const mdRe = new RegExp(`\\n?\\s*!\\[[^\\]]*\\]\\(${escaped}\\)\\s*\\n?`, 'g')
  const htmlRe = new RegExp(`<img[^>]*?src=["']${escaped}["'][^>]*?>`, 'gi')
  form.content = (form.content || '').replace(mdRe, '\n').replace(htmlRe, '')
  form.content = (form.content || '').replace(/\n{3,}/g, '\n\n').trim()
}

async function deleteImage(url) {
  try {
    await ElMessageBox.confirm('确认删除该图片吗？将同时删除 OSS 上的文件。', '提示', { type: 'warning' })
  } catch {
    return
  }

  const key = urlToOssKey(url)
  if (!key) {
    ElMessage.error('无法解析图片 key')
    return
  }

  try {
    await ossDeleteObject({ key })

    // 从 imageUrls 中移除
    let arr = []
    try {
      if (form.imageUrls) arr = JSON.parse(form.imageUrls)
    } catch {
      arr = []
    }
    arr = Array.isArray(arr) ? arr : []
    arr = arr.filter((x) => x && x !== url)
    form.imageUrls = arr.length > 0 ? JSON.stringify(arr) : ''

    // 兼容：同时清理历史 content 中遗留的图片语法
    removeImageRefFromContent(url)

    ElMessage.success('图片已删除')
  } catch (e) {
    ElMessage.error(e?.message || '删除图片失败')
  }
}

const rules = {
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }]
}

const detailDrawer = reactive({
  visible: false,
  recordId: null
})

const dialogsTransfer = reactive({ visible: false })
const transferFormRef = ref()
const transferRecordId = ref(null)
const transferUsersLoading = ref(false)
const transferUserOptions = ref([])
const transferForm = reactive({
  toUserId: null,
  reason: ''
})

const transferRules = {
  toUserId: [{ required: true, message: '请选择接收人', trigger: 'change' }]
}

async function loadTransferUsers() {
  transferUsersLoading.value = true
  try {
    const resp = await userPage({ page: 1, size: 200, status: 1 })
    transferUserOptions.value = resp?.data?.records || []
  } catch (e) {
    ElMessage.error(e?.message || '加载用户列表失败')
  } finally {
    transferUsersLoading.value = false
  }
}

async function openTransfer(row) {
  transferRecordId.value = row.id
  transferForm.toUserId = null
  transferForm.reason = ''
  dialogsTransfer.visible = true
  if (transferUserOptions.value.length === 0) {
    await loadTransferUsers()
  }
}

async function submitTransfer() {
  await transferFormRef.value?.validate?.(async (valid) => {
    if (!valid) return
    try {
      await workRecordTransferCreate(transferRecordId.value, {
        toUserId: transferForm.toUserId,
        reason: transferForm.reason
      })
      ElMessage.success('已发起转移')
      dialogsTransfer.visible = false
    } catch (e) {
      ElMessage.error(e?.message || '发起转移失败')
    }
  })
}

function openDetail(row) {
  detailDrawer.recordId = row.id
  detailDrawer.visible = true
}

function handleRowClick(row, column) {
  const statusLabels = ['状态', '操作', '图片']
  if (statusLabels.includes(column.label)) {
    return
  }
  openDetail(row)
}

function openCreate() {
  editMode.value = 'create'
  currentId.value = null
  form.bizDeptId = null
  form.categoryId = null
  form.statusId = 1
  form.title = ''
  form.content = ''
  form.imageUrls = ''
  form.isImportant = 0
  originalImageUrls.value = []
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  originalImageUrls.value = parseUrls(form.imageUrls)
  dialogs.edit = true
}

function openEdit(row) {
  editMode.value = 'edit'
  currentId.value = row.id
  form.bizDeptId = row.bizDeptId
  form.categoryId = row.categoryId
  form.statusId = row.statusId
  form.title = row.title
  form.content = row.content
  form.imageUrls = row.imageUrls || ''
  form.isImportant = row.isImportant ?? 0

  // 迁移逻辑：如果 content 里有图但 imageUrls 没存，自动提取并清理 content
  if (form.content) {
    const urlsInContent = extractImageUrls(form.content)
    if (urlsInContent.length > 0) {
      let arr = []
      try {
        if (form.imageUrls) arr = JSON.parse(form.imageUrls)
      } catch { /* ignore */ }
      
      urlsInContent.forEach(u => {
        if (!arr.includes(u)) arr.push(u)
        // 从 content 里移除该图的显示
        removeImageRefFromContent(u)
      })
      form.imageUrls = JSON.stringify(arr)
    }
  }

  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  dialogs.edit = true
}

async function submit() {
  await formRef.value?.validate?.(async (valid) => {
    if (!valid) return
    try {
      if (editMode.value === 'create') {
        await workRecordCreate(form)
        ElMessage.success('创建成功')
      } else {
        await workRecordUpdate(currentId.value, form)
        ElMessage.success('更新成功')
      }
      originalImageUrls.value = parseUrls(form.imageUrls)
      dialogs.edit = false
      if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
      previewUrl.value = ''
      await load()
    } catch (e) {
      ElMessage.error(e?.message || '提交失败')
    }
  })
}

async function uploadImageFile(file) {
  if (!file) return

  const maxSize = 2 * 1024 * 1024
  if (file.size > maxSize) {
    ElMessage.error('单张图片大小不能超过 2MB')
    return
  }

  if (contentImageUrls.value.length >= 5) {
    ElMessage.error('最多只能上传 5 张图片')
    return
  }

  uploading.value = true
  try {
    const policyResp = await ossPolicy({ dir: 'work-records' })
    const p = policyResp.data

    const fd = new FormData()
    fd.append('key', p.key)
    fd.append('policy', p.policy)
    fd.append('OSSAccessKeyId', p.accessKeyId)
    fd.append('signature', p.signature)
    fd.append('success_action_status', '200')
    fd.append('file', file)

    const resp = await fetch(p.host, { method: 'POST', body: fd })
    if (!resp.ok) throw new Error('上传 OSS 失败')

    const imgUrl = p.url || `${p.host}/${p.key}`

    let urls = []
    if (form.imageUrls) {
      try {
        const parsed = JSON.parse(form.imageUrls)
        urls = Array.isArray(parsed) ? parsed : [form.imageUrls]
      } catch {
        urls = form.imageUrls.split(',').filter(Boolean)
      }
    }
    if (!urls.includes(imgUrl)) urls.push(imgUrl)
    form.imageUrls = JSON.stringify(urls)

    // 清理历史 content 中遗留的图片语法（避免内容框显示 URL）
    removeImageRefFromContent(imgUrl)

    ElMessage.success('图片已上传')
  } catch (e) {
    ElMessage.error(e?.message || '上传失败')
  } finally {
    uploading.value = false
    if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
}

async function onPickImage(uploadFile) {
  const file = uploadFile?.raw
  if (!file) return

  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = URL.createObjectURL(file)

  await uploadImageFile(file)
}

async function onPaste(event) {
  const items = event.clipboardData?.items
  if (!items) return

  for (const item of items) {
    if (item.kind === 'file' && item.type && item.type.startsWith('image/')) {
      const file = item.getAsFile()
      if (file) {
        event.preventDefault()

        if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
        previewUrl.value = URL.createObjectURL(file)

        await uploadImageFile(file)
        return
      }
    }
  }
}
async function changeStatus(row, statusId) {
  try {
    await workRecordUpdateStatus(row.id, statusId)
    ElMessage.success('状态已更新')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '更新状态失败')
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm('确认删除该工作记录吗？（软删）', '提示', { type: 'warning' })
    await workRecordDelete(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    if (e && typeof e === 'object' && e.message) ElMessage.error(e.message)
  }
}

onMounted(async () => {
  await loadOptions()
  await load()
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Document /></el-icon>
        <div class="title">工作记录</div>
      </div>
    </div>

    <el-card shadow="never" class="card">
      <div class="filter-bar">
        <el-form :model="filters" inline class="filter-form">
          <div class="filter-row">
            <el-form-item label="分类">
              <el-select
                v-model="filters.categoryIds"
                multiple
                collapse-tags
                collapse-tags-tooltip
                clearable
                placeholder="全部"
                style="width: 220px"
                @change="onSearch"
              >
                <el-option v-for="c in categoryOptions" :key="c.id" :value="c.id" :label="c.categoryName" />
              </el-select>
            </el-form-item>

            <el-form-item label="状态">
              <el-select v-model="filters.statusIds" multiple collapse-tags collapse-tags-tooltip clearable placeholder="全部" style="width: 200px" @change="onSearch">
                <el-option v-for="s in statusOptions" :key="s.id" :value="s.id" :label="s.statusName" />
              </el-select>
            </el-form-item>

            <el-form-item label="创建时间">
              <el-date-picker
                :model-value="filters.createTimeRange"
                @update:model-value="(v) => (filters.createTimeRange = normalizeCreateTimeRange(v))"
                type="datetimerange"
                range-separator="至"
                start-placeholder="开始"
                end-placeholder="结束"
                value-format="x"
                style="width: 340px"
              />
            </el-form-item>

            <el-form-item class="action-buttons">
              <el-button type="primary" :icon="Search" @click="onSearch">查询</el-button>
              <el-button :icon="Refresh" @click="onReset">重置</el-button>
            </el-form-item>
          </div>

          <div class="filter-row">
            <el-form-item label="标题">
              <el-input v-model="filters.title" placeholder="标题（模糊匹配）" clearable style="width: 450px" @keyup.enter="onSearch" />
            </el-form-item>
          </div>
        </el-form>
      </div>

      <div class="table-actions" style="margin-bottom: 16px;">
        <el-button type="primary" :icon="Plus" @click="openCreate">新增记录</el-button>
      </div>

      <el-table v-loading="loading" :data="tableData" class="modern-table" @row-click="handleRowClick">
        <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tooltip :content="row.content || '暂无详细内容'" placement="top" :disabled="!row.content" :show-after="200">
              <div style="width: 100%;">{{ row.title }}</div>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="bizDeptId" label="业务科室" width="150">
          <template #default="{ row }">{{ deptMap.get(String(row.bizDeptId)) || '-' }}</template>
        </el-table-column>
        <el-table-column prop="categoryId" label="分类" width="150">
          <template #default="{ row }">{{ categoryMap.get(row.categoryId) || row.categoryId }}</template>
        </el-table-column>

        <el-table-column prop="statusId" label="状态" width="100">
          <template #default="{ row }">
            <el-dropdown trigger="click">
              <el-tag class="status-chip" :type="getStatusTagType(row.statusId)">
                {{ statusMap.get(row.statusId) || row.statusId }}
              </el-tag>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-for="s in statusOptions" :key="s.id" @click="changeStatus(row, s.id)">
                    {{ s.statusName }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>

        <el-table-column prop="isImportant" label="重要" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.isImportant === 1" type="danger" size="small">重要</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column label="图片" width="100">
          <template #default="{ row }">
            <el-image
              v-if="getRowImageUrls(row).length > 0"
              :src="getRowImageUrls(row)[0]"
              style="width: 46px; height: 30px; border-radius: 8px;"
              fit="cover"
              :preview-src-list="getRowImageUrls(row)"
              preview-teleported
            />
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column prop="createTime" label="创建时间" width="180" />

        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="info" @click="openDetail(row)">详情</el-button>
            <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
            <el-button size="small" :icon="Connection" @click="openTransfer(row)">转移</el-button>
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

    <el-dialog
      v-model="dialogs.edit"
      :title="editMode === 'create' ? '新增工作记录' : '编辑工作记录'"
      width="780px"
      top="3vh"
      class="compact-dialog"
      :before-close="handleCancelEdit"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="small" class="compact-form">
        <el-form-item label="业务科室">
          <el-select v-model="form.bizDeptId" clearable filterable placeholder="可选" style="width: 100%">
            <el-option :value="null" label="不指定" />
            <el-option v-for="d in deptOptions" :key="d.id" :value="d.id" :label="`${d.deptName} (${d.deptCode})`" />
          </el-select>
        </el-form-item>

        <el-form-item label="分类" prop="categoryId">
          <el-select v-model="form.categoryId" placeholder="请选择分类" style="width: 100%">
            <el-option v-for="c in categoryOptions" :key="c.id" :value="c.id" :label="c.categoryName" />
          </el-select>
        </el-form-item>

        <el-form-item label="状态">
          <el-select v-model="form.statusId" placeholder="请选择状态" style="width: 100%">
            <el-option v-for="s in statusOptions" :key="s.id" :value="s.id" :label="s.statusName" />
          </el-select>
        </el-form-item>

        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" placeholder="请输入标题" />
        </el-form-item>

        <el-form-item label="内容(支持粘贴图片,最多5张图片，最大小于2M)">
          <el-input v-model="form.content" type="textarea" :rows="6" placeholder="可选" @paste="onPaste" />
        </el-form-item>

        <div style="display: flex; gap: 20px;">
          <el-form-item style="flex: 1;">
            <el-upload :auto-upload="false" :show-file-list="false" accept="image/*" :on-change="onPickImage">
              <el-button :loading="uploading" :disabled="uploading">选择图片并上传</el-button>
            </el-upload>

            <div v-if="previewUrl" style="margin-top: 10px;">
              <el-image :src="previewUrl" style="width: 160px; height: 160px;" fit="cover" />
            </div>

            <div v-if="contentImageUrls.length > 0" style="margin-top: 10px; display: flex; flex-wrap: wrap; gap: 10px;">
              <div v-for="u in contentImageUrls" :key="u" style="position: relative; width: 100px;">
                <el-image
                  :src="u"
                  style="width: 100px; height: 100px; border-radius: 10px;"
                  fit="cover"
                  :preview-src-list="contentImageUrls"
                  :initial-index="contentImageUrls.indexOf(u)"
                  preview-teleported
                />
                <el-button
                  circle
                  type="danger"
                  :icon="Delete"
                  size="default"
                  style="position: absolute; bottom: 8px; right: 8px; box-shadow: 0 6px 16px rgba(0,0,0,0.25);"
                  @click.stop="deleteImage(u)"
                />
              </div>
            </div>
          </el-form-item>

          <el-form-item label="重要" style="width: 120px;">
            <div style="height: 32px; display: flex; align-items: center;">
              <el-switch v-model="form.isImportant" :active-value="1" :inactive-value="0" />
            </div>
          </el-form-item>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="handleCancelEdit">取消</el-button>
        <el-button type="primary" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogsTransfer.visible" title="转移任务" width="520px">
      <el-form ref="transferFormRef" :model="transferForm" :rules="transferRules" label-position="top">
        <el-form-item label="接收人" prop="toUserId">
          <el-select v-model="transferForm.toUserId" filterable placeholder="请选择接收人" style="width: 100%" :loading="transferUsersLoading">
            <el-option v-for="u in transferUserOptions" :key="u.id" :value="u.id" :label="`${u.realName || u.username}（${u.username}）`" />
          </el-select>
        </el-form-item>

        <el-form-item label="原因">
          <el-input v-model="transferForm.reason" type="textarea" :rows="3" placeholder="可选" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogsTransfer.visible = false">取消</el-button>
        <el-button type="primary" @click="submitTransfer">确定</el-button>
      </template>
    </el-dialog>

    <WorkRecordDetailDrawer v-model:visible="detailDrawer.visible" :record-id="detailDrawer.recordId" />
  </div>
</template>

<style scoped>
.page {
  padding: 18px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
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

.card {
  border-radius: 14px;
}

.filter-bar {
  margin-bottom: 12px;
}

.filter-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

:deep(.filter-form .el-form-item) {
  margin-bottom: 6px;
}

.filter-form .action-buttons {
  margin-top: auto;
  padding-bottom: 2px;
}

.pagination {
  margin-top: 14px;
}

.status-chip {
  display: inline-flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 6px 12px;
  height: auto;
  border-radius: 12px;
  font-weight: 800;
  cursor: pointer;
  user-select: none;
  line-height: 1.2;
  white-space: nowrap;
}

.status-chip:hover {
  filter: brightness(0.98);
}

.modern-table {
  border-radius: 12px;
  overflow: hidden;
}

.modern-table :deep(.el-table__inner-wrapper::before) {
  height: 0;
}

.modern-table :deep(.el-table__header-wrapper th.el-table__cell) {
  background: #f8fafc;
  color: #111827;
  font-weight: 900;
  border-bottom: 1px solid #e9edf5;
}

.modern-table :deep(.el-table__body-wrapper td.el-table__cell) {
  border-bottom: 1px solid #eef2f7;
}

.modern-table :deep(.el-table__row) {
  transition: background-color 0.15s ease;
}

.modern-table :deep(.el-table__row:hover > td.el-table__cell) {
  background: #f8fafc;
}

.modern-table :deep(.el-table__cell) {
  padding-top: 6px;
  padding-bottom: 6px;
}

.modern-table :deep(.el-table__fixed),
.modern-table :deep(.el-table__fixed-right) {
  box-shadow: none;
}

.modern-table :deep(.el-table__fixed-right::before),
.modern-table :deep(.el-table__fixed::before) {
  height: 0;
}

.compact-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.compact-form :deep(.el-form-item__label) {
  padding-bottom: 4px;
  font-weight: 700;
  font-size: 13px;
}

.compact-dialog :deep(.el-dialog__body) {
  padding-top: 10px;
  padding-bottom: 10px;
}

</style>
