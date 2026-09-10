<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, User } from '@element-plus/icons-vue'
import { userCreate, userPage, userUpdate, userUpdatePassword, userUpdateStatus } from '../api/user'
import { atLeast, currentProfile, ROLE } from '../utils/auth'
import { deptTree } from '../api/dept'
import QueryConsole from '../components/QueryConsole.vue'
import { useQueryConsole } from '../composables/useQueryConsole'

const loading = ref(false)
const tableData = ref([])
const total = ref(0)

const filters = reactive({
  page: 1,
  size: 10,
  username: '',
  realName: '',
  deptId: null,
  status: null
})

const deptOptions = ref([])

const deptMap = computed(() => {
  const map = new Map()
  const walk = (nodes) => {
    for (const n of nodes || []) {
      map.set(n.id, n.deptName)
      if (n.children?.length) walk(n.children)
    }
  }
  walk(deptOptions.value)
  return map
})

// 角色下拉：中文与后端 Role.getLabel() 一致；说明文字写清「这个角色能干什么」，
// 授予角色的人不用再去翻文档
const roleOptions = [
  { value: ROLE.USER, label: '普通员工', hint: '只管自己的记录，本科室看板只读' },
  { value: ROLE.DEPT_ADMIN, label: '科室管理员', hint: '可编辑本科室记录，维护工作分类与登记渠道' },
  { value: ROLE.ADMIN, label: '系统管理员', hint: '跨科室数据 + 员工、科室、参数配置' }
]
const roleLabelOf = (v) => roleOptions.find((r) => r.value === v)?.label || v || '普通员工'
const roleTagType = (v) => (v === ROLE.ADMIN ? 'danger' : v === ROLE.DEPT_ADMIN ? 'warning' : 'info')

const dialogs = reactive({
  create: false,
  edit: false,
  password: false
})

const currentId = ref(null)

const createFormRef = ref()
const createForm = reactive({
  username: '',
  password: '',
  realName: '',
  deptId: null,
  role: ROLE.USER,
  status: 1
})

const editFormRef = ref()
const editForm = reactive({
  id: null,
  username: '',
  realName: '',
  deptId: null,
  role: ROLE.USER,
  status: 1
})

const editRules = {
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }]
}

const passwordFormRef = ref()
const passwordForm = reactive({
  newPassword: ''
})

const createRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }]
}

// 后端 guardSelfLock：不能改自己的角色/状态，界面同步禁用，省得点了才报错
const isSelf = (row) => {
  const me = props.user?.id ?? currentProfile()?.id
  return !!me && row.id === me
}

const passwordRules = {
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }]
}

const flatDeptOptions = computed(() => {
  const out = []
  const walk = (nodes, depth = 0) => {
    for (const n of nodes || []) {
      out.push({
        id: n.id,
        label: `${'—'.repeat(depth)}${depth > 0 ? ' ' : ''}${n.deptName} (${n.deptCode})`
      })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(deptOptions.value, 0)
  return out
})

const props = defineProps({
  user: {
    type: Object,
    default: null
  }
})

// 之前是按用户名 == admin 判定的，改名或再加管理员就失效；现在统一看角色
const isAdmin = computed(() => atLeast(ROLE.ADMIN))

async function load() {
  loading.value = true
  try {
    // 非管理员进不了这个页面（路由守卫 + 接口双重收口），不必再把查询条件锁成自己
    const params = { ...filters }
    const resp = await userPage(params)
    tableData.value = resp.data.records
    total.value = resp.data.total
  } catch (e) {
    ElMessage.error(e?.message || '加载用户列表失败')
  } finally {
    loading.value = false
  }
}

async function loadDepts() {
  try {
    const resp = await deptTree()
    deptOptions.value = resp.data
  } catch {
    // ignore
  }
}

function openCreate() {
  createForm.username = ''
  createForm.password = ''
  createForm.realName = ''
  createForm.deptId = null
  createForm.role = ROLE.USER
  createForm.status = 1
  dialogs.create = true
}

async function submitCreate() {
  await createFormRef.value?.validate?.(async (valid) => {
    if (!valid) return
    try {
      await userCreate(createForm)
      ElMessage.success('创建成功')
      dialogs.create = false
      await load()
    } catch (e) {
      ElMessage.error(e?.message || '创建失败')
    }
  })
}

function openEdit(row) {
  editForm.id = row.id
  editForm.username = row.username
  editForm.realName = row.realName || ''
  editForm.deptId = row.deptId ?? null
  editForm.role = row.role || ROLE.USER
  editForm.status = row.status ?? 1
  dialogs.edit = true
}

async function submitEdit() {
  await editFormRef.value?.validate?.(async (valid) => {
    if (!valid) return
    try {
      await userUpdate(editForm.id, {
        realName: editForm.realName,
        deptId: editForm.deptId,
        // 自己那行的角色/状态控件是禁用的，这里回填原值，避免把改动提交上去吃 40003
        role: isSelf({ id: editForm.id }) ? undefined : editForm.role,
        status: isSelf({ id: editForm.id }) ? undefined : editForm.status
      })
      ElMessage.success('保存成功')
      dialogs.edit = false
      await load()
    } catch (e) {
      ElMessage.error(e?.message || '保存失败')
    }
  })
}

function openPassword(row) {
  currentId.value = row.id
  passwordForm.newPassword = ''
  dialogs.password = true
}

async function submitPassword() {
  await passwordFormRef.value?.validate?.(async (valid) => {
    if (!valid) return
    try {
      await userUpdatePassword(currentId.value, passwordForm.newPassword)
      ElMessage.success('修改密码成功')
      dialogs.password = false
    } catch (e) {
      ElMessage.error(e?.message || '修改密码失败')
    }
  })
}

async function toggleStatus(row) {
  const next = row.status === 1 ? 0 : 1
  const text = next === 1 ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(`确认要${text}该用户吗？`, '提示', { type: 'warning' })
    await userUpdateStatus(row.id, next)
    ElMessage.success(`${text}成功`)
    await load()
  } catch (e) {
    if (e && typeof e === 'object' && e.message) {
      ElMessage.error(e.message)
    }
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
    username: filters.username.trim(),
    realName: filters.realName.trim(),
    deptId: filters.deptId,
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
  filters.username = ''
  filters.realName = ''
  filters.deptId = null
  filters.status = null
  runSearch()
}

// 状态只有两个值，用胶囊比下拉少一次点击；再点一次就是取消
const statusPills = [
  { value: 1, label: '启用' },
  { value: 0, label: '禁用' }
]

function pickStatus(value) {
  filters.status = filters.status === value ? null : value
}

const filterChips = computed(() => {
  const chips = []
  const realName = filters.realName.trim()
  if (realName) chips.push({ key: 'realName', field: '姓名', value: `含「${realName}」` })
  const username = filters.username.trim()
  if (username) chips.push({ key: 'username', field: '用户名', value: `含「${username}」` })
  if (filters.deptId !== null) {
    chips.push({ key: 'deptId', field: '科室', value: deptMap.value.get(filters.deptId) || `#${filters.deptId}` })
  }
  if (filters.status !== null) {
    chips.push({ key: 'status', field: '状态', value: statusPills.find((x) => x.value === filters.status).label })
  }
  return chips
})

function removeFilterChip(key) {
  if (key === 'realName') filters.realName = ''
  else if (key === 'username') filters.username = ''
  else if (key === 'deptId') filters.deptId = null
  else if (key === 'status') filters.status = null
}

const hiddenActiveFilterCount = computed(() => {
  if (filterExpanded.value) return 0
  let n = 0
  if (filters.username.trim()) n += 1
  if (filters.deptId !== null) n += 1
  return n
})

onMounted(() => {
  load()
  loadDepts()
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><User /></el-icon>
        <div class="title">员工管理</div>
      </div>
    </div>

    <div class="content">
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
            <el-input v-model="filters.realName" class="qc-search" placeholder="搜索姓名，回车即查" clearable @keyup.enter="runSearch">
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
                <span class="qc-flabel">用户名</span>
                <el-input v-model="filters.username" placeholder="登录名，模糊匹配" clearable @keyup.enter="runSearch" />
              </div>
              <div class="qc-cell">
                <span class="qc-flabel">科室</span>
                <el-select v-model="filters.deptId" clearable filterable placeholder="全部科室">
                  <el-option v-for="opt in flatDeptOptions" :key="opt.id" :value="opt.id" :label="opt.label" />
                </el-select>
              </div>
            </div>
          </template>
        </QueryConsole>

        <el-card class="card" shadow="never">

        <div class="table-actions" style="margin-bottom: 16px;">
          <el-button v-if="isAdmin" type="primary" @click="openCreate">新增员工</el-button>
          <el-button @click="load">刷新</el-button>
        </div>

        <el-table v-loading="loading" :data="tableData" border stripe>
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="username" label="用户名" />
          <el-table-column prop="realName" label="姓名" />
          <el-table-column prop="deptId" label="科室">
            <template #default="{ row }">
              {{ deptMap.get(row.deptId) || '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="role" label="角色" width="120">
            <template #default="{ row }">
              <!-- 中文标签用后端 roleLabel，前端不再各存一份映射；缺失时退回本地表 -->
              <el-tag :type="roleTagType(row.role)" effect="light">
                {{ row.roleLabel || roleLabelOf(row.role) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'danger'">
                {{ row.status === 1 ? '启用' : '禁用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" />
          <el-table-column label="操作" width="300">
            <template #default="{ row }">
              <el-button
                size="small"
                type="primary"
                :disabled="!isAdmin && row.id !== props.user?.id"
                @click="openPassword(row)"
              >
                修改密码
              </el-button>
              <el-button size="small" @click="openEdit(row)">编辑</el-button>
              <!-- 后端禁止改自己的角色/状态（防锁死），这里同步禁用并把原因写在 title 里 -->
              <el-button
                v-if="isAdmin"
                size="small"
                type="warning"
                :disabled="isSelf(row)"
                :title="isSelf(row) ? '不能修改自己的状态' : ''"
                @click="toggleStatus(row)"
              >
                {{ row.status === 1 ? '禁用' : '启用' }}
              </el-button>
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

    <el-dialog v-model="dialogs.create" title="新增员工" width="520px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="createForm.username" placeholder="用于登录" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="createForm.password" placeholder="初始密码" type="password" show-password />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="createForm.realName" placeholder="员工真实姓名" />
        </el-form-item>
        <el-form-item label="科室">
          <el-select v-model="createForm.deptId" clearable placeholder="选择所属科室" style="width: 100%">
            <el-option v-for="opt in flatDeptOptions" :key="opt.id" :value="opt.id" :label="opt.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="createForm.role" style="width: 100%">
            <el-option v-for="opt in roleOptions" :key="opt.value" :value="opt.value" :label="opt.label">
              <span class="opt-line">
                <span class="opt-label">{{ opt.label }}</span>
                <span class="opt-hint">{{ opt.hint }}</span>
              </span>
            </el-option>
          </el-select>
          <div class="field-tip">新建账号默认普通员工，可稍后在「编辑」里授予角色</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="createForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogs.create = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogs.edit" title="编辑员工" width="520px">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-position="top">
        <el-form-item label="用户名">
          <el-input v-model="editForm.username" disabled />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="editForm.realName" placeholder="员工真实姓名" />
        </el-form-item>
        <el-form-item label="科室">
          <el-select v-model="editForm.deptId" clearable placeholder="选择所属科室" style="width: 100%">
            <el-option v-for="opt in flatDeptOptions" :key="opt.id" :value="opt.id" :label="opt.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="editForm.role" :disabled="isSelf(editForm)" style="width: 100%">
            <el-option v-for="opt in roleOptions" :key="opt.value" :value="opt.value" :label="opt.label">
              <span class="opt-line">
                <span class="opt-label">{{ opt.label }}</span>
                <span class="opt-hint">{{ opt.hint }}</span>
              </span>
            </el-option>
          </el-select>
          <!-- 改自己的角色会被后端拒（防止把自己降权锁在系统外），这里提前说明而不是等报错 -->
          <div class="field-tip" v-if="isSelf(editForm)">不能修改自己的角色，请让其他系统管理员操作</div>
          <div class="field-tip" v-else>角色调整后需对方重新登录才生效（角色随登录令牌签发）</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="editForm.status" :disabled="isSelf(editForm)">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogs.edit = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogs.password" title="修改密码" width="520px">
      <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-position="top">
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" placeholder="请输入新密码" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogs.password = false">取消</el-button>
        <el-button type="primary" @click="submitPassword">确定</el-button>
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
  gap: 12px;
  margin-bottom: 14px;
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
  font-weight: 900;
  color: var(--g-text);
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card {
  border-radius: var(--g-radius-md);
}

/* 角色下拉里的「名称 + 能力说明」两行式选项 */
.opt-line {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
  padding: 2px 0;
}

.opt-label {
  font-size: 13px;
  color: var(--g-text);
}

.opt-hint {
  font-size: 11px;
  color: var(--g-text-faint);
}

.field-tip {
  font-size: 12px;
  color: var(--g-text-muted);
  margin-top: 4px;
  line-height: 1.5;
}
.pagination {
  margin-top: 14px;
}
</style>

