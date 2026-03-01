<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { User } from '@element-plus/icons-vue'
import { userCreate, userPage, userUpdatePassword, userUpdateStatus } from '../api/user'
import { deptTree } from '../api/dept'

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

const dialogs = reactive({
  create: false,
  password: false
})

const currentId = ref(null)

const createFormRef = ref()
const createForm = reactive({
  username: '',
  password: '',
  realName: '',
  deptId: null,
  status: 1
})

const passwordFormRef = ref()
const passwordForm = reactive({
  newPassword: ''
})

const createRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }]
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

const isAdmin = computed(() => props.user?.username === 'admin')

async function load() {
  loading.value = true
  try {
    const params = { ...filters }
    if (!isAdmin.value && props.user?.id) {
      params.username = props.user.username
    }
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

function onReset() {
  filters.username = ''
  filters.realName = ''
  filters.deptId = null
  filters.status = null
  load()
}

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
      <el-card class="card" shadow="never">
        <div class="filter-bar">
          <el-form :model="filters" inline class="filter-form">
            <el-form-item label="用户名">
              <el-input v-model="filters.username" placeholder="搜索用户名" clearable />
            </el-form-item>
            <el-form-item label="姓名">
              <el-input v-model="filters.realName" placeholder="搜索姓名" clearable />
            </el-form-item>
            <el-form-item label="科室">
              <el-select v-model="filters.deptId" clearable placeholder="选择科室" style="width: 220px">
                <el-option :value="null" label="全部" />
                <el-option v-for="opt in flatDeptOptions" :key="opt.id" :value="opt.id" :label="opt.label" />
              </el-select>
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="filters.status" clearable placeholder="选择状态" style="width: 180px">
                <el-option :value="null" label="全部" />
                <el-option :value="1" label="启用" />
                <el-option :value="0" label="禁用" />
              </el-select>
            </el-form-item>
            <el-form-item class="action-buttons">
              <el-button type="primary" @click="load">查询</el-button>
              <el-button @click="onReset">重置</el-button>
            </el-form-item>
          </el-form>
        </div>

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
          <el-table-column prop="status" label="状态">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'danger'">
                {{ row.status === 1 ? '启用' : '禁用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" />
          <el-table-column label="操作" width="240">
            <template #default="{ row }">
              <el-button
                size="small"
                type="primary"
                :disabled="!isAdmin && row.id !== props.user?.id"
                @click="openPassword(row)"
              >
                修改密码
              </el-button>
              <el-button
                v-if="isAdmin"
                size="small"
                type="warning"
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
  color: #3b82f6;
}

.title {
  font-size: 18px;
  font-weight: 900;
  color: #111827;
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card {
  border-radius: 14px;
}

.filter-bar {
  margin-bottom: 14px;
}

.filter-form {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.filter-form .action-buttons {
  margin-top: auto;
  padding-bottom: 2px; /* Adjust if button alignment is slightly off */
}

.pagination {
  margin-top: 14px;
}
</style>
