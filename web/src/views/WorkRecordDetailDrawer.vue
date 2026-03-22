<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getWorkRecordLogs,
  addWorkRecordLog,
  updateWorkRecordLog,
  deleteWorkRecordLog,
  getWorkRecordExpenses,
  addWorkRecordExpense,
  updateWorkRecordExpense,
  deleteWorkRecordExpense,
  workRecordDetail
} from '../api/work'

const props = defineProps({
  recordId: {
    type: [Number, String],
    default: null
  },
  visible: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:visible', 'refresh'])

const activeTab = ref('logs')
const loading = ref(false)

const recordDetail = ref(null)
const recordTitle = computed(() => recordDetail.value?.title || '')
const recordTitleDisplay = computed(() => recordTitle.value?.trim() || '工作记录详情')
const recordSubTitle = computed(() => {
  const idPart = props.recordId ? `#${props.recordId}` : ''
  const tabPart = activeTab.value === 'logs' ? '处理详情' : '费用记录'
  const parts = [idPart, tabPart].filter(Boolean)
  return parts.join(' · ')
})

async function fetchRecordDetail() {
  if (!props.recordId) return
  try {
    const resp = await workRecordDetail(props.recordId)
    recordDetail.value = resp.data
  } catch (e) {
    // ignore title fetch errors to avoid blocking drawer usage
  }
}

// Logs
const logs = ref([])
const newLogContent = ref('')
const isAddingLog = ref(false)

// Expenses
const expenses = ref([])
const expenseDialogVisible = ref(false)
const isSubmittingExpense = ref(false)
const expenseFormRef = ref()
const expenseEditMode = ref('create')
const currentExpenseId = ref(null)
const expenseForm = ref({
  expenseType: '配件',
  amount: 0,
  description: '',
  expenseTime: new Date()
})

const expenseRules = {
  expenseType: [{ required: true, message: '请选择费用类型', trigger: 'change' }],
  amount: [{ required: true, message: '请输入金额', trigger: 'blur' }]
}

const expenseTypeOptions = [
  { value: '配件', label: '配件' },
  { value: '耗材', label: '耗材' },
  { value: '服务费', label: '服务费' },
  { value: '合同', label: '合同' },
  { value: '交通费', label: '交通费' },
  { value: '差旅费', label: '差旅费' },
  { value: '培训费用', label: '培训费用' },
  { value: '其他', label: '其他' }
]

async function fetchLogs() {
  if (!props.recordId) return
  loading.value = true
  try {
    const resp = await getWorkRecordLogs(props.recordId)
    logs.value = resp.data
  } catch (e) {
    ElMessage.error(e?.message || '加载处理详情失败')
  } finally {
    loading.value = false
  }
}

async function handleAddLog() {
  if (!newLogContent.value.trim()) {
    ElMessage.warning('请输入处理详情内容')
    return
  }
  isAddingLog.value = true
  try {
    await addWorkRecordLog(props.recordId, newLogContent.value)
    ElMessage.success('添加成功')
    newLogContent.value = ''
    await fetchLogs()
  } catch (e) {
    ElMessage.error(e?.message || '添加失败')
  } finally {
    isAddingLog.value = false
  }
}

async function handleEditLog(log) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新的处理详情', '编辑详情', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: log.logContent,
      inputType: 'textarea',
      inputAttrs: {
        rows: 10
      }
    })
    await updateWorkRecordLog(props.recordId, log.id, value)
    ElMessage.success('修改成功')
    await fetchLogs()
  } catch (e) {
    if (e && e !== 'cancel') {
        ElMessage.error(e.message || '修改失败');
    }
  }
}

async function handleDeleteLog(log) {
  try {
    await ElMessageBox.confirm('确认删除这条处理详情吗？', '提示', { type: 'warning' })
    await deleteWorkRecordLog(props.recordId, log.id)
    ElMessage.success('删除成功')
    await fetchLogs()
  } catch (e) {
    if (e && e !== 'cancel') {
        ElMessage.error(e.message || '删除失败');
    }
  }
}

async function fetchExpenses() {
  if (!props.recordId) return
  loading.value = true
  try {
    const resp = await getWorkRecordExpenses(props.recordId)
    expenses.value = resp.data
  } catch (e) {
    ElMessage.error(e?.message || '加载费用记录失败')
  } finally {
    loading.value = false
  }
}

function openAddExpense() {
  expenseEditMode.value = 'create'
  currentExpenseId.value = null
  expenseForm.value = {
    expenseType: '配件',
    amount: 0,
    description: '',
    expenseTime: ''
  }
  expenseDialogVisible.value = true
}

function openEditExpense(row) {
  expenseEditMode.value = 'edit'
  currentExpenseId.value = row.id
  expenseForm.value = {
    expenseType: row.expenseType,
    amount: row.amount,
    description: row.description,
    // 回填时先把后端可能返回的 ISO 时间统一成 yyyy-MM-dd HH:mm:ss
    expenseTime: normalizeDateTimeString(row.expenseTime)
  }
  expenseDialogVisible.value = true
}

function formatDateTime(value) {
  const d = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function normalizeDateTimeString(value) {
  if (!value) return ''
  const text = String(value).trim()
  // 统一成后端 LocalDateTime 可解析格式：yyyy-MM-dd HH:mm:ss
  // 兼容 "2026-03-22T18:11:21" / "2026-03-22 18:11:21.000" / "...Z"
  return text.replace('T', ' ').replace(/\.\d+$/, '').replace(/Z$/, '')
}

async function handleSubmitExpense() {
  await expenseFormRef.value?.validate?.(async (valid) => {
    if (!valid) return
    isSubmittingExpense.value = true
    try {
      const payload = {
        expenseType: expenseForm.value.expenseType,
        amount: expenseForm.value.amount,
        description: expenseForm.value.description,
        expenseTime: undefined
      }

      const rawExpenseTime = expenseForm.value.expenseTime
      if (rawExpenseTime) {
        // 后端 LocalDateTime 需要 yyyy-MM-dd HH:mm:ss
        payload.expenseTime = rawExpenseTime instanceof Date
          ? formatDateTime(rawExpenseTime)
          : normalizeDateTimeString(rawExpenseTime)
      }

      if (expenseEditMode.value === 'create') {
        await addWorkRecordExpense(props.recordId, payload)
        ElMessage.success('添加成功')
      } else {
        await updateWorkRecordExpense(props.recordId, currentExpenseId.value, payload)
        ElMessage.success('修改成功')
      }
      expenseDialogVisible.value = false
      await fetchExpenses()
    } catch (e) {
      ElMessage.error(e?.message || '提交失败')
    } finally {
      isSubmittingExpense.value = false
    }
  })
}

async function handleDeleteExpense(row) {
  try {
    await ElMessageBox.confirm('确认删除这笔费用记录吗？', '提示', { type: 'warning' })
    await deleteWorkRecordExpense(props.recordId, row.id)
    ElMessage.success('删除成功')
    await fetchExpenses()
  } catch (e) {
    if (e && e !== 'cancel') {
        ElMessage.error(e.message || '删除失败');
    }
  }
}

function handleClose() {
  emit('update:visible', false)
}

watch(
  () => props.visible,
  (newVal) => {
    if (newVal) {
      fetchRecordDetail()
      if (activeTab.value === 'logs') {
        fetchLogs()
      } else {
        fetchExpenses()
      }
    } else {
      recordDetail.value = null
      // Reset state when drawer closes
      logs.value = []
      expenses.value = []
      newLogContent.value = ''
      activeTab.value = 'logs'
    }
  }
)

watch(activeTab, (newTab) => {
  if (props.visible) {
    if (newTab === 'logs') {
      fetchLogs()
    } else {
      fetchExpenses()
    }
  }
})
</script>

<template>
  <el-drawer :model-value="visible" direction="rtl" size="610px" @close="handleClose">
    <template #header>
      <div class="drawer-header">
        <div class="drawer-header__main">
          <div class="drawer-header__title" :title="recordTitleDisplay">{{ recordTitleDisplay }}</div>
          <div v-if="recordSubTitle" class="drawer-header__sub">{{ recordSubTitle }}</div>
        </div>
      </div>
    </template>
    <el-tabs v-model="activeTab">
      <el-tab-pane label="处理详情" name="logs">
        <div v-loading="loading" class="tab-content">
          <el-timeline v-if="logs.length > 0" class="log-timeline">
            <el-timeline-item v-for="log in logs" :key="log.id" :timestamp="log.logTime">
              <div class="log-item">
                <p>{{ log.logContent }}</p>
                <div class="log-actions">
                  <el-button link type="primary" size="small" @click="handleEditLog(log)">编辑</el-button>
                  <el-button link type="danger" size="small" @click="handleDeleteLog(log)">删除</el-button>
                </div>
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无处理详情" />

          <div class="add-log-form">
            <el-input
              v-model="newLogContent"
              type="textarea"
              :rows="6"
              placeholder="输入处理详情..."
            />
            <el-button type="primary" :loading="isAddingLog" @click="handleAddLog">添加详情</el-button>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="费用记录" name="expenses">
        <div v-loading="loading" class="tab-content">
          <div class="expense-toolbar">
            <el-button type="primary" @click="openAddExpense">新增费用</el-button>
          </div>
          <el-table :data="expenses" border stripe>
            <el-table-column prop="expenseType" label="费用类型" />
            <el-table-column prop="amount" label="金额">
              <template #default="{ row }">￥{{ row.amount }}</template>
            </el-table-column>
            <el-table-column prop="description" label="说明" />
            <el-table-column prop="expenseTime" label="费用时间" />
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEditExpense(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="handleDeleteExpense(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="expenses.length === 0" description="暂无费用记录" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="expenseDialogVisible" :title="expenseEditMode === 'create' ? '新增费用' : '编辑费用'" width="480px" append-to-body>
      <el-form ref="expenseFormRef" :model="expenseForm" :rules="expenseRules" label-position="top">
        <el-form-item label="费用类型" prop="expenseType">
          <el-select v-model="expenseForm.expenseType" style="width: 100%">
            <el-option v-for="opt in expenseTypeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额" prop="amount">
          <el-input-number v-model="expenseForm.amount" :precision="2" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="费用时间（不填默认当前时间）">
          <el-date-picker
            v-model="expenseForm.expenseTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="expenseForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="expenseDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="isSubmittingExpense" @click="handleSubmitExpense">确定</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
.drawer-header {
  display: flex;
  align-items: center;
  width: 100%;
}
.drawer-header__main {
  flex: 1;
  min-width: 0;
}
.drawer-header__title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.4;
}
.drawer-header__sub {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}
.tab-content {
  padding: 0 10px;
}
.add-log-form {
  margin-top: 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.log-timeline {
  margin-top: 20px;
}
.expense-toolbar {
  margin-bottom: 10px;
}
.log-item {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.log-actions {
  margin-left: 10px;
  white-space: nowrap;
}
</style>
