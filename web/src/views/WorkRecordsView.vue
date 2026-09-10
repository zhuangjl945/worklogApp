<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Plus, Search, Connection, Document, Delete, Notebook } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  workCategoryEnabled,
  workRecordCreate,
  workRecordDelete,
  workRecordPage,
  workRecordUpdate,
  workRecordUpdateStatus,
  workRecordTransferCreate,
  workRecordWeeklyReport,
  workStatusEnabled,
  ossDeleteObject
} from '../api/work'
import { userRoster } from '../api/user'
import { deptAllEnabled, deptMyRootChildren } from '../api/dept'
import WorkRecordDetailDrawer from './WorkRecordDetailDrawer.vue'
import { useQueryConsole } from '../composables/useQueryConsole'
import QueryConsole from '../components/QueryConsole.vue'
import { uploadToOss } from '../utils/oss'
import { ensureSignedUrls, signedList, signedUrl } from '../utils/ossView'
import {
  fillTemplatePattern,
  parseCategoryTemplate,
  templateDateLabel,
  validateAgainstTemplate
} from '../utils/categoryTemplate.js'

const props = defineProps({
  user: {
    type: Object,
    default: null
  }
})

const route = useRoute()
const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const focusedRowId = ref(null)
const lastAppliedTitle = ref('')
const lastAppliedContent = ref('')

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

function currentTemplate() {
  const cat = categoryOptions.value.find((c) => c.id === form.categoryId)
  return parseCategoryTemplate(cat?.templateJson)
}

function templateContext() {
  const cat = categoryOptions.value.find((c) => c.id === form.categoryId)
  const biz = form.bizDeptId != null ? deptMap.value.get(String(form.bizDeptId)) : ''
  return {
    date: templateDateLabel(form.startTime),
    bizDept: biz || '',
    category: cat?.categoryName || ''
  }
}

function applyCategoryTemplate(forceContent = false) {
  const tpl = currentTemplate()
  if (!tpl) return
  const ctx = templateContext()
  const nextTitle = fillTemplatePattern(tpl.titlePattern, ctx)
  const nextContent = tpl.contentTemplate || ''
  if (nextTitle && (!form.title || form.title === lastAppliedTitle.value)) {
    form.title = nextTitle
    lastAppliedTitle.value = nextTitle
  }
  if (nextContent && (forceContent || !form.content || form.content === lastAppliedContent.value)) {
    form.content = nextContent
    lastAppliedContent.value = nextContent
  }
}

function onCategoryChange() {
  if (editMode.value === 'create') {
    lastAppliedTitle.value = ''
    lastAppliedContent.value = ''
    applyCategoryTemplate(true)
    return
  }
  applyCategoryTemplate(false)
}

function onBizDeptOrStartChange() {
  applyCategoryTemplate(false)
}

const filters = reactive({
  page: 1,
  size: 10,
  categoryIds: [],
  bizDeptId: null,
  statusIds: [1, 2],
  createTimeRange: null,
  title: '',
  overdue: false,
  importantOnly: false
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
  // 日期选择器按 value-format="x" 回传的是纯数字字符串，
  // 直接 new Date("1757...") 会得到 Invalid Date，这里先把它转回数值
  const d = new Date(typeof dt === 'string' && /^\d+$/.test(dt) ? Number(dt) : dt)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function buildQueryParams() {
  const params = {
    page: filters.page,
    size: filters.size,
    categoryIds: filters.categoryIds.length > 0 ? filters.categoryIds.join(',') : undefined,
    bizDeptId: filters.bizDeptId ?? undefined,
    statusIds: filters.statusIds.length > 0 ? filters.statusIds.join(',') : undefined,
    title: filters.title?.trim() ? `%${filters.title.trim()}%` : undefined,
    overdue: filters.overdue ? true : undefined,
    isImportant: filters.importantOnly ? 1 : undefined
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
    await applyFocusFromRoute()
  } catch (e) {
    ElMessage.error(e?.message || '加载工作记录失败')
  } finally {
    loading.value = false
  }
}

// ------------------------------------------------------------
// 检索台逻辑：条件一变就自动重查，不再需要「查询」按钮
// ------------------------------------------------------------

// 只对筛选字段敏感，翻页、改每页条数不应触发重查
const filterSignature = computed(() =>
  JSON.stringify({
    categoryIds: [...filters.categoryIds].sort((a, b) => a - b),
    statusIds: [...filters.statusIds].sort((a, b) => a - b),
    range: filters.createTimeRange || null,
    title: filters.title.trim(),
    overdue: filters.overdue,
    importantOnly: filters.importantOnly
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
  filters.categoryIds = []
  filters.statusIds = []
  filters.createTimeRange = null
  filters.title = ''
  filters.overdue = false
  filters.importantOnly = false
  runSearch()
}

// --- 常用视图：把每天重复的那几套检索固化成一排按钮 ---
const OPEN_STATUS = [1, 2]

const filterPresets = [
  { key: 'todo', label: '待办' },
  { key: 'overdue', label: '逾期' },
  { key: 'important', label: '重要未闭环' },
  { key: 'week', label: '本周新增' },
  { key: 'all', label: '全部记录' }
]

function startOfThisWeek() {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7))
  return d.getTime()
}

function endOfToday() {
  const d = new Date()
  d.setHours(23, 59, 59, 999)
  return d.getTime()
}

function isSameIdSet(list, ids) {
  if (list.length !== ids.length) return false
  const s = new Set(list)
  return ids.every((i) => s.has(i))
}

// 当前条件恰好等于某个视图时才高亮，手动改过任何一项就落回「自定义」
const activeFilterPreset = computed(() => {
  const noCat = filters.categoryIds.length === 0
  const hasRange = filters.createTimeRange && filters.createTimeRange.length === 2
  const noFlags = !filters.overdue && !filters.importantOnly
  const openOnly = isSameIdSet(filters.statusIds, OPEN_STATUS)
  const allStatus = filters.statusIds.length === 0
  if (noCat && !hasRange && openOnly && filters.overdue && !filters.importantOnly) return 'overdue'
  if (noCat && !hasRange && openOnly && filters.importantOnly && !filters.overdue) return 'important'
  if (noCat && !hasRange && openOnly && noFlags) return 'todo'
  if (noCat && hasRange && allStatus && noFlags && Number(filters.createTimeRange[0]) === startOfThisWeek()) return 'week'
  if (noCat && !hasRange && allStatus && noFlags) return 'all'
  return null
})

function applyFilterPreset(key) {
  // 预设只切「范围」，保留已输入的标题关键字，方便在结果里继续收窄
  filters.categoryIds = []
  filters.createTimeRange = null
  filters.overdue = false
  filters.importantOnly = false
  filters.statusIds = key === 'todo' || key === 'overdue' || key === 'important' ? [...OPEN_STATUS] : []
  if (key === 'overdue') filters.overdue = true
  if (key === 'important') filters.importantOnly = true
  if (key === 'week') filters.createTimeRange = [startOfThisWeek(), endOfToday()]
  runSearch()
}

// --- 检索式回显：把此刻真正生效的条件写成一行，每个词块可单独摘掉 ---
function shortStamp(v) {
  const d = new Date(Number(v))
  if (!Number.isFinite(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const filterChips = computed(() => {
  const chips = []
  const t = filters.title.trim()
  if (t) chips.push({ key: 'title', field: '标题', value: `含「${t}」` })
  if (filters.statusIds.length) {
    chips.push({
      key: 'status',
      field: '状态',
      value: filters.statusIds.map((id) => statusMap.value.get(id) || `#${id}`).join('、')
    })
  }
  if (filters.categoryIds.length) {
    chips.push({
      key: 'category',
      field: '分类',
      value: filters.categoryIds.map((id) => categoryMap.value.get(id) || `#${id}`).join('、')
    })
  }
  if (filters.createTimeRange && filters.createTimeRange.length === 2) {
    chips.push({ key: 'range', field: '创建', value: `${shortStamp(filters.createTimeRange[0])} → ${shortStamp(filters.createTimeRange[1])}` })
  }
  const marks = []
  if (filters.overdue) marks.push('逾期')
  if (filters.importantOnly) marks.push('重要')
  if (marks.length) chips.push({ key: 'mark', field: '标记', value: marks.join('、') })
  return chips
})

function removeFilterChip(key) {
  if (key === 'title') filters.title = ''
  else if (key === 'status') filters.statusIds = []
  else if (key === 'category') filters.categoryIds = []
  else if (key === 'range') filters.createTimeRange = null
  else if (key === 'mark') {
    filters.overdue = false
    filters.importantOnly = false
  }
}

// 折叠起来之后，还有几个条件在悄悄生效就得标出来，免得用户以为看到的是全量
const hiddenActiveFilterCount = computed(() => {
  if (filterExpanded.value) return 0
  let n = 0
  if (filters.categoryIds.length) n += 1
  if (filters.createTimeRange && filters.createTimeRange.length === 2) n += 1
  if (filters.overdue || filters.importantOnly) n += 1
  return n
})

const dateShortcuts = [
  { text: '最近 7 天', value: () => [Date.now() - 6 * 86400000, Date.now()] },
  { text: '最近 30 天', value: () => [Date.now() - 29 * 86400000, Date.now()] },
  { text: '本周', value: () => [startOfThisWeek(), Date.now()] },
  {
    text: '本月',
    value: () => {
      const d = new Date()
      return [new Date(d.getFullYear(), d.getMonth(), 1).getTime(), Date.now()]
    }
  }
]

function toggleStatusFilter(id) {
  if (filters.statusIds.includes(id)) {
    filters.statusIds = filters.statusIds.filter((i) => i !== id)
  } else {
    filters.statusIds = [...filters.statusIds, id]
  }
}

function toDateTimeValue(v) {
  if (!v) return null
  if (Array.isArray(v) && v.length >= 3) {
    const [y, m, d, h = 0, min = 0, s = 0] = v
    const pad = (n) => String(n).padStart(2, '0')
    return `${y}-${pad(m)}-${pad(d)} ${pad(h)}:${pad(min)}:${pad(s)}`
  }
  const s = String(v).replace('T', ' ')
  if (s.length >= 19) return s.slice(0, 19)
  if (s.length >= 16) return `${s.slice(0, 16)}:00`
  return s
}

function nowDateTimeValue() {
  return formatDateTime(Date.now())
}

function isOpenStatus(statusId) {
  return statusId === 1 || statusId === 2
}

function isOverdue(row) {
  if (!row?.endTime || !isOpenStatus(row.statusId)) return false
  const raw = toDateTimeValue(row.endTime)
  if (!raw) return false
  const t = new Date(raw.replace(' ', 'T'))
  return Number.isFinite(t.getTime()) && t.getTime() < Date.now()
}

function deadlineRel(row) {
  const raw = toDateTimeValue(row?.endTime)
  if (!raw) return ''
  const d = new Date(raw.slice(0, 10) + 'T00:00:00')
  if (!Number.isFinite(d.getTime())) return ''
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const diff = Math.round((d.getTime() - today.getTime()) / 86400000)
  if (diff < 0) return `已过 ${Math.abs(diff)} 天`
  if (diff === 0) return '今天'
  if (diff === 1) return '明天'
  return `${diff} 天后`
}

function formatDeadline(row) {
  const raw = toDateTimeValue(row?.endTime)
  if (!raw) return '未定期'
  const rel = deadlineRel(row)
  return rel ? `${raw.slice(5, 16)} · ${rel}` : raw.slice(5, 16)
}

function tableRowClassName({ row }) {
  const classes = []
  if (isOverdue(row)) classes.push('is-overdue')
  if (focusedRowId.value != null && String(row.id) === String(focusedRowId.value)) classes.push('is-focused')
  return classes.join(' ')
}

async function applyFocusFromRoute() {
  const id = route.query.focusId
  if (!id) {
    focusedRowId.value = null
    return
  }
  focusedRowId.value = id
  await nextTick()
  const el = document.querySelector('.modern-table .is-focused')
  el?.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
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
const showInlineCreate = ref(false)
const showFullpageEdit = ref(false)
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

  showFullpageEdit.value = false
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
  startTime: null,
  endTime: null,
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
    const resp = await userRoster({ size: 200 })
    transferUserOptions.value = resp?.data || []
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
  if (showInlineCreate.value) { showInlineCreate.value = false; return }
  editMode.value = 'create'
  currentId.value = null
  form.bizDeptId = null
  form.categoryId = null
  form.statusId = 1
  form.title = ''
  form.content = ''
  form.imageUrls = ''
  form.startTime = nowDateTimeValue()
  form.endTime = null
  form.isImportant = 0
  lastAppliedTitle.value = ''
  lastAppliedContent.value = ''
  originalImageUrls.value = []
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  originalImageUrls.value = parseUrls(form.imageUrls)
  showInlineCreate.value = true
}

function cancelInlineCreate() {
  const currentUrls = contentImageUrls.value
  const addedUrls = currentUrls.filter((u) => !originalImageUrls.value.includes(u))
  for (const url of addedUrls) {
    const key = urlToOssKey(url)
    if (key) { try { ossDeleteObject({ key }) } catch (e) { /* 静默 */ } }
  }
  showInlineCreate.value = false
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ""
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
  form.startTime = toDateTimeValue(row.startTime)
  form.endTime = toDateTimeValue(row.endTime)
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
  showFullpageEdit.value = true
}

async function submit() {
  await formRef.value?.validate?.(async (valid) => {
    if (!valid) return
    if (form.startTime && form.endTime && form.startTime > form.endTime) {
      ElMessage.error('截止日期不能早于开始时间')
      return
    }
    const tplErrors = validateAgainstTemplate(currentTemplate(), form)
    if (tplErrors.length) {
      ElMessage.warning(tplErrors[0])
      return
    }
    const payload = {
      ...form,
      startTime: form.startTime || null,
      endTime: form.endTime || null
    }
    try {
      if (editMode.value === 'create') {
        await workRecordCreate(payload)
        ElMessage.success('创建成功')
      } else {
        await workRecordUpdate(currentId.value, payload)
        ElMessage.success('更新成功')
      }
      originalImageUrls.value = parseUrls(form.imageUrls)
      if (editMode.value === "create") { showInlineCreate.value = false } else { showFullpageEdit.value = false }
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
    const imgUrl = await uploadToOss(file, 'work-records')

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

const weeklyDialog = reactive({
  visible: false,
  loading: false,
  week: 'this',
  text: '',
  from: '',
  to: ''
})

async function openWeeklyReport() {
  weeklyDialog.visible = true
  await loadWeeklyReport()
}

async function loadWeeklyReport() {
  weeklyDialog.loading = true
  try {
    const resp = await workRecordWeeklyReport({ week: weeklyDialog.week })
    weeklyDialog.text = resp.data?.text || ''
    weeklyDialog.from = resp.data?.from || ''
    weeklyDialog.to = resp.data?.to || ''
  } catch (e) {
    ElMessage.error(e?.message || '生成周报失败')
  } finally {
    weeklyDialog.loading = false
  }
}

async function copyWeeklyReport() {
  const text = weeklyDialog.text
  if (!text) {
    ElMessage.warning('没有可复制的内容')
    return
  }
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.left = '-9999px'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    ElMessage.success('已复制，可粘贴到微信/OA')
  } catch (e) {
    ElMessage.error(e?.message || '复制失败，请手动全选复制')
  }
}

watch(
  () => route.query.focusId,
  () => {
    applyFocusFromRoute()
  }
)

// 工作记录图片：数据一变就批量换短时签名地址。
// 放这里而不是放在渲染函数里，是为了让 <el-image> 求值时只读缓存、不带副作用，
// 否则每次重渲染都会往队列里塞请求。
watch(
  () => tableData.value.flatMap((r) => getRowImageUrls(r)),
  (urls) => ensureSignedUrls(urls)
)

watch(contentImageUrls, (urls) => ensureSignedUrls(urls), { immediate: true })

onMounted(async () => {
  await loadOptions()
  await load()
})
</script>

<template>
  <div class="page">
    <div v-if="!showFullpageEdit">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Document /></el-icon>
        <div class="title">工作记录</div>
      </div>
    </div>

    <QueryConsole
      class="qc-block"
      v-model:expanded="filterExpanded"
      :presets="filterPresets"
      :active-preset="activeFilterPreset"
      :chips="filterChips"
      :hidden-active-count="hiddenActiveFilterCount"
      :result-text="'共 ' + total + ' 条'"
      :busy="loading"
      @select-preset="applyFilterPreset"
      @remove-chip="removeFilterChip"
      @clear-all="onReset"
    >
      <template #search>
        <el-input v-model="filters.title" class="qc-search" placeholder="搜索标题关键字，回车即查" clearable @keyup.enter="runSearch">
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
      </template>

      <template #inline>
        <span class="qc-flabel">状态</span>
        <div class="qc-pills" role="group" aria-label="按状态筛选">
          <button
            v-for="s in statusOptions"
            :key="s.id"
            type="button"
            class="qc-pill"
            :class="{ 'is-on': filters.statusIds.includes(s.id) }"
            :aria-pressed="filters.statusIds.includes(s.id)"
            @click="toggleStatusFilter(s.id)"
          >
            <span class="qc-pill-dot" aria-hidden="true" />
            {{ s.statusName }}
          </button>
        </div>
      </template>

      <template #more>
        <div class="qc-grid">
          <div class="qc-cell">
            <span class="qc-flabel">分类</span>
            <el-select v-model="filters.categoryIds" multiple collapse-tags collapse-tags-tooltip clearable placeholder="全部分类" style="width: 100%">
              <el-option v-for="c in categoryOptions" :key="c.id" :value="c.id" :label="c.categoryName" />
            </el-select>
          </div>
          <div class="qc-cell qc-cell--wide">
            <span class="qc-flabel">创建时间</span>
            <el-date-picker
              :model-value="filters.createTimeRange"
              :shortcuts="dateShortcuts"
              type="datetimerange"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              value-format="x"
              style="width: 100%"
              @update:model-value="(v) => (filters.createTimeRange = normalizeCreateTimeRange(v))"
            />
          </div>
          <div class="qc-cell">
            <span class="qc-flabel">标记</span>
            <div class="qc-pills">
              <button type="button" class="qc-pill" :class="{ 'is-on': filters.overdue }" :aria-pressed="filters.overdue" @click="filters.overdue = !filters.overdue">
                <span class="qc-pill-dot" aria-hidden="true" />仅逾期
              </button>
              <button type="button" class="qc-pill" :class="{ 'is-on': filters.importantOnly }" :aria-pressed="filters.importantOnly" @click="filters.importantOnly = !filters.importantOnly">
                <span class="qc-pill-dot" aria-hidden="true" />仅重要
              </button>
            </div>
          </div>
        </div>
      </template>
    </QueryConsole>

    <el-card shadow="never" class="card">
      <div class="table-actions">
        <el-button type="primary" :icon="Plus" @click="openCreate" :class="{ 'is-active': showInlineCreate }">
          {{ showInlineCreate ? '收起表单' : '新增记录' }}
        </el-button>
        <el-button :icon="Notebook" @click="openWeeklyReport">生成本周周报</el-button>
      </div>

      <!-- 内联展开卡片 - 新增记录 -->
      <Transition name="inline-card">
        <div v-if="showInlineCreate" class="inline-create-card">
          <div class="inline-card-header">
            <div class="inline-card-title">
              <el-icon class="inline-card-icon"><Plus /></el-icon>
              <span>新增工作记录</span>
            </div>
            <div class="inline-card-actions">
              <el-button @click="cancelInlineCreate">取消</el-button>
              <el-button type="primary" :loading="uploading" @click="submit">提交</el-button>
            </div>
          </div>

          <div class="inline-card-body">
            <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="small" class="inline-form">
              <div class="inline-form-grid">
                <el-form-item label="业务科室">
                  <el-select v-model="form.bizDeptId" clearable filterable placeholder="可选" style="width: 100%" @change="onBizDeptOrStartChange">
                    <el-option :value="null" label="不指定" />
                    <el-option v-for="d in deptOptions" :key="d.id" :value="d.id" :label="`${d.deptName} (${d.deptCode})`" />
                  </el-select>
                </el-form-item>
                <el-form-item label="分类" prop="categoryId">
                  <el-select v-model="form.categoryId" placeholder="请选择分类" style="width: 100%" @change="onCategoryChange">
                    <el-option v-for="c in categoryOptions" :key="c.id" :value="c.id" :label="c.categoryName" />
                  </el-select>
                </el-form-item>
                <el-form-item label="状态">
                  <el-select v-model="form.statusId" placeholder="请选择状态" style="width: 100%">
                    <el-option v-for="s in statusOptions" :key="s.id" :value="s.id" :label="s.statusName" />
                  </el-select>
                </el-form-item>
              </div>

              <div class="inline-form-grid">
                <el-form-item label="开始时间">
                  <el-date-picker
                    v-model="form.startTime"
                    type="datetime"
                    placeholder="可选"
                    value-format="YYYY-MM-DD HH:mm:ss"
                    format="YYYY-MM-DD HH:mm"
                    style="width: 100%"
                    @change="onBizDeptOrStartChange"
                  />
                </el-form-item>
                <el-form-item label="截止日期">
                  <el-date-picker
                    v-model="form.endTime"
                    type="datetime"
                    placeholder="建议填写，用于逾期提醒"
                    value-format="YYYY-MM-DD HH:mm:ss"
                    format="YYYY-MM-DD HH:mm"
                    style="width: 100%"
                  />
                </el-form-item>
              </div>

              <el-form-item label="标题" prop="title">
                <el-input v-model="form.title" placeholder="请输入标题" />
              </el-form-item>

              <el-form-item label="内容（支持粘贴图片，最多5张，最大2M）">
                <el-input v-model="form.content" type="textarea" :rows="4" placeholder="可选" @paste="onPaste" />
              </el-form-item>

              <el-form-item>
                <div class="inline-extras">
                  <div style="display: flex; align-items: center; gap: 12px;">
                    <el-upload :auto-upload="false" :show-file-list="false" accept="image/*" :on-change="onPickImage">
                      <el-button size="small" :loading="uploading" :disabled="uploading">上传图片</el-button>
                    </el-upload>
                    <div class="inline-important">
                      <span class="inline-label" style="margin-bottom: 0;">重要</span>
                      <el-switch v-model="form.isImportant" :active-value="1" :inactive-value="0" />
                    </div>
                  </div>

                  <div v-if="previewUrl" class="inline-preview">
                    <el-image :src="previewUrl" style="width: 80px; height: 80px; border-radius: 8px;" fit="cover" />
                  </div>

                  <div v-if="contentImageUrls.length > 0" class="inline-images">
                    <div v-for="u in contentImageUrls" :key="u" class="inline-image-item">
                      <el-image :src="signedUrl(u)" style="width: 64px; height: 64px; border-radius: 8px;" fit="cover" :preview-src-list="signedList(contentImageUrls)" :initial-index="contentImageUrls.indexOf(u)" preview-teleported />
                      <el-button circle type="danger" :icon="Delete" size="small" class="inline-image-delete" @click.stop="deleteImage(u)" />
                    </div>
                  </div>
                </div>
              </el-form-item>
            </el-form>
          </div>
        </div>
      </Transition>

      <el-table
        v-loading="loading"
        :data="tableData"
        class="modern-table"
        row-key="id"
        :row-class-name="tableRowClassName"
        @row-click="handleRowClick"
      >
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
              :src="signedUrl(getRowImageUrls(row)[0])"
              style="width: 46px; height: 30px; border-radius: 8px;"
              fit="cover"
              :preview-src-list="signedList(getRowImageUrls(row))"
              preview-teleported
            />
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column label="截止" width="168">
          <template #default="{ row }">
            <span class="deadline" :class="{ overdue: isOverdue(row) }">{{ formatDeadline(row) }}</span>
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
    </div>

    <!-- 全页编辑视图 -->
    <div v-if="showFullpageEdit" class="fe-card">
      <div class="fe-header">
        <div class="fe-header-left">
          <el-button @click="handleCancelEdit" circle>&#x2190;</el-button>
          <div class="fe-title">
            <div>编辑工作记录</div>
            <div class="fe-subtitle">{{ categoryMap.get(form.categoryId) || '-' }} · {{ deptMap.get(String(form.bizDeptId)) || '-' }}</div>
          </div>
        </div>
        <div class="fe-header-right">
          <el-button @click="handleCancelEdit">取消</el-button>
          <el-button type="primary" @click="submit">保存</el-button>
        </div>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="fe-form">
        <div class="fe-section">
          <div class="fe-section-title">基本信息</div>
          <div class="fe-row">
            <el-form-item label="业务科室" class="fe-field">
              <el-select v-model="form.bizDeptId" clearable filterable placeholder="可选" @change="onBizDeptOrStartChange">
                <el-option :value="null" label="不指定" />
                <el-option v-for="d in deptOptions" :key="d.id" :value="d.id" :label="`${d.deptName} (${d.deptCode})`" />
              </el-select>
            </el-form-item>
            <el-form-item label="分类" prop="categoryId" class="fe-field">
              <el-select v-model="form.categoryId" placeholder="请选择分类" @change="onCategoryChange">
                <el-option v-for="c in categoryOptions" :key="c.id" :value="c.id" :label="c.categoryName" />
              </el-select>
            </el-form-item>
          </div>
          <div class="fe-row fe-row-compact">
            <el-form-item label="状态" class="fe-field fe-field-status">
              <el-select v-model="form.statusId" placeholder="请选择状态">
                <el-option v-for="s in statusOptions" :key="s.id" :value="s.id" :label="s.statusName" />
              </el-select>
            </el-form-item>
            <el-form-item label="重要" class="fe-field fe-field-switch">
              <el-switch v-model="form.isImportant" :active-value="1" :inactive-value="0" />
            </el-form-item>
          </div>
        </div>
        <div class="fe-section">
          <div class="fe-section-title">时间安排</div>
          <div class="fe-row fe-row-dates">
            <el-form-item label="开始时间" class="fe-field">
              <el-date-picker v-model="form.startTime" type="datetime" placeholder="可选" value-format="YYYY-MM-DD HH:mm:ss" format="YYYY-MM-DD HH:mm" @change="onBizDeptOrStartChange" />
            </el-form-item>
            <el-form-item label="截止日期" class="fe-field">
              <el-date-picker v-model="form.endTime" type="datetime" placeholder="建议填写" value-format="YYYY-MM-DD HH:mm:ss" format="YYYY-MM-DD HH:mm" />
            </el-form-item>
          </div>
        </div>
        <div class="fe-section">
          <div class="fe-section-title">工作内容</div>
          <div class="fe-row">
            <el-form-item label="标题" prop="title" class="fe-field fe-field-full">
              <el-input v-model="form.title" placeholder="请输入标题" />
            </el-form-item>
          </div>
          <div class="fe-row">
            <el-form-item label="内容" class="fe-field fe-field-full">
              <el-input v-model="form.content" type="textarea" :rows="6" placeholder="可选，支持粘贴图片" @paste="onPaste" />
            </el-form-item>
          </div>
        </div>
        <div class="fe-section">
          <div class="fe-section-title">图片附件</div>
          <div class="fe-row">
            <el-form-item label="图片" class="fe-field fe-field-full">
              <div class="fe-images">
                <el-upload :auto-upload="false" :show-file-list="false" accept="image/*" :on-change="onPickImage">
                  <el-button :loading="uploading" :disabled="uploading" round plain>上传图片</el-button>
                </el-upload>
                <div v-if="previewUrl" class="fe-img-preview">
                  <el-image :src="previewUrl" class="fe-img" fit="cover" />
                </div>
                <div v-for="u in contentImageUrls" :key="u" class="fe-img-wrap">
                  <el-image :src="signedUrl(u)" class="fe-img" fit="cover" :preview-src-list="signedList(contentImageUrls)" :initial-index="contentImageUrls.indexOf(u)" preview-teleported />
                  <el-button circle type="danger" :icon="Delete" size="small" class="fe-img-del" @click.stop="deleteImage(u)" />
                </div>
              </div>
            </el-form-item>
          </div>
        </div>
      </el-form>
    </div>

    <el-dialog v-if="editMode === 'edit'"
      v-model="dialogs.edit"
      :title="editMode === 'create' ? '新增工作记录' : '编辑工作记录'"
      width="780px"
      top="3vh"
      class="compact-dialog"
      :before-close="handleCancelEdit"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="small" class="compact-form">
        <el-form-item label="业务科室">
          <el-select v-model="form.bizDeptId" clearable filterable placeholder="可选" style="width: 100%" @change="onBizDeptOrStartChange">
            <el-option :value="null" label="不指定" />
            <el-option v-for="d in deptOptions" :key="d.id" :value="d.id" :label="`${d.deptName} (${d.deptCode})`" />
          </el-select>
        </el-form-item>

        <el-form-item label="分类" prop="categoryId">
          <el-select v-model="form.categoryId" placeholder="请选择分类" style="width: 100%" @change="onCategoryChange">
            <el-option v-for="c in categoryOptions" :key="c.id" :value="c.id" :label="c.categoryName" />
          </el-select>
        </el-form-item>

        <el-form-item label="状态">
          <el-select v-model="form.statusId" placeholder="请选择状态" style="width: 100%">
            <el-option v-for="s in statusOptions" :key="s.id" :value="s.id" :label="s.statusName" />
          </el-select>
        </el-form-item>

        <div style="display: flex; gap: 16px;">
          <el-form-item label="开始时间" style="flex: 1;">
            <el-date-picker
              v-model="form.startTime"
              type="datetime"
              placeholder="可选"
              value-format="YYYY-MM-DD HH:mm:ss"
              format="YYYY-MM-DD HH:mm"
              style="width: 100%"
              @change="onBizDeptOrStartChange"
            />
          </el-form-item>
          <el-form-item label="截止日期" style="flex: 1;">
            <el-date-picker
              v-model="form.endTime"
              type="datetime"
              placeholder="建议填写，用于逾期提醒"
              value-format="YYYY-MM-DD HH:mm:ss"
              format="YYYY-MM-DD HH:mm"
              style="width: 100%"
            />
          </el-form-item>
        </div>

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
                  :src="signedUrl(u)"
                  style="width: 100px; height: 100px; border-radius: 10px;"
                  fit="cover"
                  :preview-src-list="signedList(contentImageUrls)"
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

    <el-dialog v-model="weeklyDialog.visible" title="工作周报" width="560px">
      <div class="weekly-toolbar">
        <el-radio-group v-model="weeklyDialog.week" @change="loadWeeklyReport">
          <el-radio-button value="this">本周</el-radio-button>
          <el-radio-button value="last">上周</el-radio-button>
        </el-radio-group>
        <span v-if="weeklyDialog.from" class="weekly-range">{{ weeklyDialog.from }} 至 {{ weeklyDialog.to }}</span>
      </div>
      <el-input
        v-model="weeklyDialog.text"
        type="textarea"
        :rows="18"
        class="weekly-text"
      />
      <template #footer>
        <el-button @click="weeklyDialog.visible = false">关闭</el-button>
        <el-button type="primary" :loading="weeklyDialog.loading" @click="copyWeeklyReport">复制到微信/OA</el-button>
      </template>
    </el-dialog>
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

/* --- 表格上方的动作条 --- */
.table-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
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

.modern-table :deep(.el-table__row.is-overdue > td.el-table__cell) {
  background: #fef2f2;
}

.modern-table :deep(.el-table__row.is-focused > td.el-table__cell) {
  background: #fff7ed;
  box-shadow: inset 3px 0 0 #c23a2b;
}

.deadline {
  font-size: 12px;
  color: #64748b;
}

.deadline.overdue {
  color: #c23a2b;
  font-weight: 700;
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


/* ========================================
   内联新增卡片 - 展开式表单
   ======================================== */
.inline-create-card {
  margin-bottom: 16px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.06);
}

.inline-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid #f1f5f9;
  background: linear-gradient(135deg, #f8fafc, #f1f5f9);
}

.inline-card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
}

.inline-card-icon {
  font-size: 16px;
  color: #3b82f6;
  background: rgba(59, 130, 246, 0.1);
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: grid;
  place-items: center;
}

.inline-card-actions {
  display: flex;
  gap: 8px;
}

.inline-card-body {
  padding: 20px;
}

.inline-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 16px;
}

.inline-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.inline-label {
  font-size: 12px;
  font-weight: 600;
  color: #475569;
  display: flex;
  align-items: center;
  gap: 2px;
}

.inline-label .required {
  color: #ef4444;
  font-size: 14px;
}

.inline-extras {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.inline-important {
  display: flex;
  align-items: center;
  gap: 8px;
}

.inline-preview {
  margin-top: 4px;
}

.inline-images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
}

.inline-image-item {
  position: relative;
}

.inline-image-delete {
  position: absolute;
  bottom: 4px;
  right: 4px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.2);
}

/* 展开/收起动画 */
.inline-card-enter-active {
  animation: inlineCardIn 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.inline-card-leave-active {
  animation: inlineCardIn 0.2s cubic-bezier(0.4, 0, 0.2, 1) reverse;
}

@keyframes inlineCardIn {
  from {
    opacity: 0;
    transform: translateY(-12px);
    max-height: 0;
  }
  to {
    opacity: 1;
    transform: translateY(0);
    max-height: 900px;
  }
}

/* 新增按钮激活态 */
.table-actions .is-active {
  background: #64748b !important;
  border-color: #64748b !important;
}

.weekly-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.weekly-range {
  font-size: 13px;
  color: #64748b;
}

.weekly-text :deep(textarea) {
  font-family: inherit;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .inline-form-grid {
    grid-template-columns: 1fr;
  }
}

/* ========================================
   全页编辑视图
   ======================================== */
.fe-card {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: #f5f7fb;
  z-index: 1000;
  display: flex;
  flex-direction: column;
  animation: feSlideIn 0.25s ease-out;
}

@keyframes feSlideIn {
  from { opacity: 0; transform: translateY(-8px); }
  to { opacity: 1; transform: translateY(0); }
}

.fe-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 28px;
  background: #ffffff;
  border-bottom: 1px solid #e2e8f0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  flex-shrink: 0;
}

.fe-header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.fe-title {
  font-size: 18px;
  font-weight: 600;
  color: #1e293b;
}

.fe-subtitle {
  font-size: 13px;
  color: #71717a;
  margin-top: 2px;
}

.fe-header-right {
  display: flex;
  gap: 8px;
}

.fe-form {
  flex: 1;
  overflow-y: auto;
  padding: 32px 28px;
}

.fe-form > div {
  max-width: 820px;
  margin: 0 auto;
}

.fe-section {
  margin-bottom: 32px;
}

.fe-section-title {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e4e4e7;
}

.fe-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 20px;
  margin-bottom: 20px;
}

.fe-row:last-child {
  margin-bottom: 0;
}

/* 紧凑行布局 - 状态 + 重要开关 */
.fe-row-compact {
  display: flex;
  align-items: flex-start;
  gap: 24px;
}

.fe-field-status {
  width: 200px;
  flex-shrink: 0;
}

.fe-field-switch {
  display: inline-flex;
  flex-direction: column;
  padding-top: 4px;
}

.fe-field-switch :deep(.el-form-item__content) {
  margin-top: 4px;
}

/* 日期选择器行 - 限制宽度 */
.fe-row-dates {
  max-width: 640px;
  grid-template-columns: 1fr 1fr;
}

.fe-field {
  display: flex;
  flex-direction: column;
}

.fe-field :deep(.el-form-item__label) {
  font-weight: 500;
  font-size: 13px;
  color: #475569;
  margin-bottom: 8px;
}

.fe-field :deep(.el-form-item__content) {
  flex: 1;
}

.fe-field :deep(.el-input__wrapper),
.fe-field :deep(.el-textarea__inner),
.fe-field :deep(.el-select .el-input__wrapper) {
  border-radius: 8px;
  box-shadow: 0 0 0 1px #e2e8f0 !important;
  transition: all 0.2s;
}

.fe-field :deep(.el-input__wrapper:hover),
.fe-field :deep(.el-textarea__inner:hover),
.fe-field :deep(.el-select .el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #cbd5e1 !important;
}

.fe-field :deep(.el-input__wrapper.is-focus),
.fe-field :deep(.el-textarea__inner:focus),
.fe-field :deep(.el-select .el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px #3b82f6 !important;
}

.fe-field :deep(.el-textarea__inner) {
  padding: 12px 14px;
  line-height: 1.6;
}

.fe-field :deep(.el-select),
.fe-field :deep(.el-date-editor) {
  width: 100% !important;
}

.fe-field-full {
  grid-column: 1 / -1;
}

.fe-images {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-start;
}

.fe-img-preview,
.fe-img-wrap {
  position: relative;
}

.fe-img {
  width: 100px;
  height: 100px;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
}

.fe-img-del {
  position: absolute;
  bottom: 6px;
  right: 6px;
  width: 24px;
  height: 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

@media (max-width: 768px) {
  .fe-header {
    padding: 14px 16px;
  }
  .fe-row-compact {
    flex-direction: column;
    gap: 0;
  }
  .fe-field-status {
    width: 100%;
  }
  .fe-row-dates {
    max-width: none;
    grid-template-columns: 1fr;
  }
  .fe-form {
    padding: 20px 16px;
  }
  .fe-row {
    grid-template-columns: 1fr;
    gap: 16px;
  }
  .fe-title {
    font-size: 16px;
  }
}

</style>
