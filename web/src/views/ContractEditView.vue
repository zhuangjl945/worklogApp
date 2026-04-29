<script setup>
import { onMounted, reactive, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Tickets, Plus, Delete } from '@element-plus/icons-vue'
import { contractCreate, contractDetail, contractUpdate } from '../api/contract'
import { me } from '../api/auth'
import { supplierPage, supplierCreate, supplierNextCode } from '../api/supplier'
import { deptTree, deptMyRootChildren } from '../api/dept'
import { userPage } from '../api/user'
import { ossPolicy, ossDeleteObject } from '../api/work'

const route = useRoute()
const router = useRouter()
const id = route.params.id
const isEdit = !!id

const loading = ref(false)
const formRef = ref()

const myDeptId = ref(null)
const myRootDeptIds = ref([])

const form = reactive({
  contractNo: '',
  contractName: '',
  contractType: 1,
  contractDirection: 1,
  supplierId: null,
  supplierName: '',
  customerId: null,
  customerName: '',
  contractAmount: 0,
  startDate: '',
  endDate: '',
  signDate: '',
  paymentTerms: 1,
  deptId: null,
  managerId: null,
  paymentSchedule: [],
  contractFileUrl: ''
})

// 添加供应商弹窗
const supplierDialogVisible = ref(false)
const supplierFormRef = ref()
const supplierFormLoading = ref(false)
const supplierForm = reactive({
  supplierCode: '',
  supplierName: '',
  supplierShortName: '',
  creditCode: '',
  contactPhone: '',
  bankName: '',
  bankAccount: '',
  accountName: ''
})

const supplierRules = {
  supplierCode: [{ required: true, message: '请输入供应商编码', trigger: 'blur' }],
  supplierName: [{ required: true, message: '请输入供应商全称', trigger: 'blur' }]
}

const rules = {
  contractNo: [{ required: true, message: '请输入合同编号', trigger: 'blur' }],
  contractName: [{ required: true, message: '请输入合同名称', trigger: 'blur' }],
  contractType: [{ required: true, message: '请选择合同类型', trigger: 'change' }],
  contractDirection: [{ required: true, message: '请选择合同方向', trigger: 'change' }],
  contractAmount: [{ required: true, message: '请输入合同总额', trigger: 'blur' }],
  startDate: [{ required: true, message: '请选择开始日期', trigger: 'change' }],
  endDate: [{ required: true, message: '请选择结束日期', trigger: 'change' }]
}

const suppliers = ref([])
const depts = ref([])
const managers = ref([])

async function loadSuppliers(query) {
  try {
    const resp = await supplierPage({ supplierName: query, size: 20, deleted: 0 })
    suppliers.value = resp.data.records
  } catch (e) {}
}

// 打开添加供应商弹窗
async function openAddSupplier() {
  supplierForm.supplierName = ''
  supplierForm.supplierShortName = ''
  supplierForm.creditCode = ''
  supplierForm.contactPhone = ''
  supplierForm.bankName = ''
  supplierForm.bankAccount = ''
  supplierForm.accountName = ''
  supplierDialogVisible.value = true
  // 获取自动编码
  await loadSupplierNextCode()
}

// 获取供应商自动编码
async function loadSupplierNextCode() {
  try {
    const resp = await supplierNextCode()
    supplierForm.supplierCode = resp.data.supplierCode
  } catch (e) {}
}

// 提交新增供应商
async function submitSupplier() {
  await supplierFormRef.value?.validate?.(async (valid) => {
    if (!valid) return
    supplierFormLoading.value = true
    try {
      const resp = await supplierCreate(supplierForm)
      ElMessage.success('供应商创建成功')
      supplierDialogVisible.value = false
      // 自动选中新创建的供应商
      form.supplierId = resp.data.id
      form.supplierName = supplierForm.supplierName
      // 刷新供应商列表
      loadSuppliers(supplierForm.supplierName)
    } catch (e) {
      ElMessage.error(e?.message || '创建供应商失败')
    } finally {
      supplierFormLoading.value = false
    }
  })
}

async function loadDepts() {
  try {
    const resp = await deptTree()
    const flat = []
    const walk = (nodes, depth = 0) => {
      for (const n of nodes) {
        flat.push({ id: n.id, label: '—'.repeat(depth) + ' ' + n.deptName })
        if (n.children) walk(n.children, depth + 1)
      }
    }
    walk(resp.data)
    depts.value = flat
  } catch (e) {}
}

async function loadManagers(query) {
  try {
    const resp = await userPage({ realName: query, size: 2000, deptIds: myRootDeptIds.value?.join?.(',') })
    managers.value = resp.data.records
  } catch (e) {}
}

async function loadDetail() {
  if (!isEdit) return
  loading.value = true
  try {
    const resp = await contractDetail(id)
    const data = resp.data.contract

    if (myDeptId.value != null && data?.deptId != null && String(myDeptId.value) !== String(data.deptId)) {
      ElMessage.error('无权限编辑该合同')
      return
    }

    Object.assign(form, data)
    if (typeof data.paymentSchedule === 'string') {
      form.paymentSchedule = JSON.parse(data.paymentSchedule || '[]')
    }
  } catch (e) {
    ElMessage.error(e?.message || '加载详情失败')
  } finally {
    loading.value = false
  }
}

function addPlan() {
  form.paymentSchedule.push({ planNo: `第${form.paymentSchedule.length + 1}期`, planAmount: 0, planDate: '' })
}

function removePlan(index) {
  form.paymentSchedule.splice(index, 1)
}

function generateMonthlyPlans() {
  if (!form.startDate || !form.endDate) {
    ElMessage.warning('请先选择合同开始和结束日期')
    form.paymentTerms = 1
    return
  }

  const start = new Date(form.startDate)
  const end = new Date(form.endDate)
  const schedule = []

  let curr = new Date(start.getFullYear(), start.getMonth(), 1)
  while (curr <= end) {
    const monthLastDay = new Date(curr.getFullYear(), curr.getMonth() + 1, 0)
    if (monthLastDay >= start) {
      const displayDate = monthLastDay > end ? end : monthLastDay
      schedule.push({
        planNo: `${curr.getFullYear()}年${curr.getMonth() + 1}月月结款`,
        planAmount: 0,
        planDate: displayDate.toISOString().split('T')[0]
      })
    }
    curr.setMonth(curr.getMonth() + 1)
  }

  const n = schedule.length
  const total = Number(form.contractAmount || 0)
  if (n > 0) {
    const base = Math.floor((total / n) * 100) / 100
    let remain = Math.round((total - base * n) * 100) / 100
    for (let i = 0; i < n; i++) {
      const extra = remain > 0 ? 0.01 : 0
      if (extra > 0) remain = Math.round((remain - extra) * 100) / 100
      schedule[i].planAmount = Math.round((base + extra) * 100) / 100
    }
  }

  form.paymentSchedule = schedule
}

function onPaymentTermsChange(val) {
  if (val === 4) {
    generateMonthlyPlans()
  } else if (val === 1) {
    form.paymentSchedule = []
  }
}

function onSupplierChange(val) {
  const s = suppliers.value.find(x => x.id === val)
  if (s) form.supplierName = s.supplierName
}

const uploadLoading = ref(false)
const pendingFiles = ref([])

const existingFiles = computed(() => {
  if (!form.contractFileUrl) return []
  try {
    const parsed = JSON.parse(form.contractFileUrl)
    return Array.isArray(parsed) ? parsed : [form.contractFileUrl]
  } catch (e) {
    return [form.contractFileUrl]
  }
})

function handleFileChange(file) {
  const f = file?.raw || file
  if (!f) return

  if (f.size > 5 * 1024 * 1024) {
    ElMessage.error(`文件 ${f.name} 大小不能超过5MB`)
    return
  }

  pendingFiles.value.push(f)
}

function removePendingFile(index) {
  pendingFiles.value.splice(index, 1)
}

async function removeExistingFile(url) {
  try {
    const u = new URL(url)
    const key = u.pathname.startsWith('/') ? u.pathname.substring(1) : u.pathname
    await ossDeleteObject({ key })

    const files = existingFiles.value.filter(x => x !== url)
    form.contractFileUrl = files.length > 0 ? JSON.stringify(files) : ''
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

async function uploadPendingFilesToOss() {
  if (pendingFiles.value.length === 0) return []

  uploadLoading.value = true
  const urls = []
  try {
    for (const f of pendingFiles.value) {
      const policyResp = await ossPolicy({ dir: 'contracts' })
      const p = policyResp.data

      const formData = new FormData()
      formData.append('key', p.key)
      formData.append('policy', p.policy)
      formData.append('OSSAccessKeyId', p.accessKeyId)
      formData.append('signature', p.signature)
      formData.append('file', f)

      const resp = await fetch(p.host, {
        method: 'POST',
        body: formData
      })

      if (!resp.ok) {
        throw new Error(`文件 ${f.name} 上传失败`)
      }
      urls.push(p.url)
    }
    pendingFiles.value = []
    return urls
  } finally {
    uploadLoading.value = false
  }
}


async function submit() {
  await formRef.value?.validate?.(async (valid) => {
    if (!valid) return

    // 校验付款计划总金额
    if (form.paymentTerms !== 1) {
      const totalPlanAmount = form.paymentSchedule.reduce((sum, item) => sum + (item.planAmount || 0), 0)
      if (Math.abs(totalPlanAmount - form.contractAmount) > 0.01) {
        ElMessage.error(`付款计划总额(¥${totalPlanAmount.toFixed(2)})与合同总金额(¥${form.contractAmount.toFixed(2)})不一致`)
        return
      }
    }

    try {
      if (pendingFiles.value.length > 0) {
        const newUrls = await uploadPendingFilesToOss()
        const allUrls = [...existingFiles.value, ...newUrls]
        form.contractFileUrl = allUrls.length > 0 ? JSON.stringify(allUrls) : ''
      }

      if (isEdit) {
        await contractUpdate(id, form)
        ElMessage.success('更新成功')
      } else {
        const resp = await contractCreate(form)
        ElMessage.success('创建成功')
        router.replace(`/contract/detail/${resp.data.id}`)
      }
    } catch (e) {
      ElMessage.error(e?.message || '提交失败')
    }
  })
}

onMounted(async () => {
  try {
    const resp = await me()
    myDeptId.value = resp?.data?.deptId ?? null
  } catch {
    myDeptId.value = null
  }

  await loadDetail()
  loadSuppliers()
  loadDepts()

  try {
    const deptResp = await deptMyRootChildren()
    myRootDeptIds.value = (deptResp?.data || []).map(d => d.id).filter(Boolean)
  } catch {
    myRootDeptIds.value = []
  }

  loadManagers()
  loadSupplierNextCode()

  if (!isEdit && myDeptId.value != null && form.deptId == null) {
    form.deptId = myDeptId.value
  }
})
</script>

<template>
  <div class="page">
    <el-card shadow="never" class="page-card">
      <div class="page-header">
        <div class="titleWrap">
          <el-icon class="titleIcon"><Tickets /></el-icon>
          <div class="title">{{ isEdit ? '编辑合同（草稿）' : '新增合同' }}</div>
        </div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px" class="form">
        <el-row :gutter="20">
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="合同编号" prop="contractNo">
              <el-input v-model="form.contractNo" placeholder="HT-YYYYMMDD-XXX" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="合同名称" prop="contractName">
              <el-input v-model="form.contractName" placeholder="请输入合同完整名称" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="合同类型" prop="contractType">
              <el-select v-model="form.contractType" style="width: 100%">
                <el-option :value="1" label="采购合同" />
                <el-option :value="2" label="销售合同" />
                <el-option :value="3" label="服务合同" />
                <el-option :value="4" label="其他合同" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="供应商" v-if="form.contractDirection === 1">
              <div style="display: flex; gap: 8px; align-items: center;">
                <el-select v-model="form.supplierId" filterable remote :remote-method="loadSuppliers" @change="onSupplierChange" style="flex: 1;" placeholder="搜素供应商">
                  <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
                </el-select>
                <el-button type="primary" plain size="small" @click="openAddSupplier" title="新增供应商">
                  <el-icon><Plus /></el-icon>
                </el-button>
              </div>
            </el-form-item>
            <el-form-item label="客户名称" v-else>
              <el-input v-model="form.customerName" placeholder="输入客户名称" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="负责部门">
              <el-select v-model="form.deptId" style="width: 100%" clearable placeholder="选择部门">
                <el-option v-for="d in depts" :key="d.id" :label="d.label" :value="d.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="方向" prop="contractDirection">
              <el-select v-model="form.contractDirection" style="width: 100%">
                <el-option :value="1" label="采购（我方付款）" />
                <el-option :value="2" label="销售（我方收款）" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="签订日期">
              <el-date-picker v-model="form.signDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="有效期（始）" prop="startDate">
              <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="有效期（止）" prop="endDate">
              <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="合同总额" prop="contractAmount">
              <el-input-number v-model="form.contractAmount" :precision="2" :step="1000" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <el-form-item label="负责人">
              <el-select v-model="form.managerId" filterable remote :remote-method="loadManagers" style="width: 100%" clearable placeholder="搜索负责人">
                <el-option v-for="u in managers" :key="u.id" :label="u.realName" :value="u.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider>
          <span class="divider-text">正式合同原件</span>
        </el-divider>

        <el-form-item label="上传附件" class="upload-item">
          <el-upload
            class="upload-contract"
            :show-file-list="false"
            :auto-upload="false"
            :disabled="uploadLoading"
            :on-change="handleFileChange"
            multiple
            accept=".pdf,.jpg,.jpeg,.png,.doc,.docx"
          >
            <el-button type="primary" plain :loading="uploadLoading">选择文件</el-button>
          </el-upload>

          <div v-if="pendingFiles.length" class="uploaded-file">
            <el-icon><Tickets /></el-icon>
            <div style="flex: 1">
              <div v-for="(f, idx) in pendingFiles" :key="idx" style="display: flex; align-items: center; gap: 8px; margin: 2px 0">
                <span>待上传：{{ f.name }}（点击保存后上传）</span>
                <el-button link type="danger" size="small" @click="removePendingFile(idx)">移除</el-button>
              </div>
            </div>
          </div>

          <div v-else-if="existingFiles.length" class="uploaded-file">
            <el-icon><Tickets /></el-icon>
            <div style="flex: 1">
              <div v-for="(u, idx) in existingFiles" :key="idx" style="display: flex; align-items: center; gap: 8px; margin: 2px 0">
                <el-link :href="u" target="_blank" rel="noopener" type="primary">已上传附件{{ existingFiles.length > 1 ? idx + 1 : '' }}</el-link>
                <el-button link type="danger" size="small" @click="removeExistingFile(u)">移除</el-button>
              </div>
            </div>
          </div>

          <div v-else class="upload-tip">支持 PDF、图片或 Word 文档，文件大小不超过 5MB</div>
        </el-form-item>

        <el-divider>
          <span class="divider-text">付款条款 / 计划配置</span>
        </el-divider>

        <el-form-item label="付款方式">
          <el-radio-group v-model="form.paymentTerms" @change="onPaymentTermsChange">
            <el-radio :label="1">一次性付清</el-radio>
            <el-radio :label="2">分期付款</el-radio>
            <el-radio :label="3">按里程碑</el-radio>
            <el-radio :label="4">按月结算</el-radio>
          </el-radio-group>
        </el-form-item>

        <div class="plans-section" v-if="form.paymentTerms !== 1">
          <div class="plan-header">
            <div class="plan-title">付款计划明细</div>
            <el-button type="primary" size="small" link :icon="Plus" @click="addPlan">添加计划行</el-button>
          </div>
          <el-table :data="form.paymentSchedule" border size="default" class="plan-table">
            <el-table-column label="阶段名称" min-width="150">
              <template #default="{ row }">
                <el-input v-model="row.planNo" placeholder="例如：首期/节点1" />
              </template>
            </el-table-column>
            <el-table-column label="计划金额 (¥)" width="180">
              <template #default="{ row }">
                <el-input-number v-model="row.planAmount" :precision="2" :controls="false" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="预计付款日期" width="180">
              <template #default="{ row }">
                <el-date-picker v-model="row.planDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" placeholder="选择日期" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80" align="center">
              <template #default="{ $index }">
                <el-button link type="danger" :icon="Delete" @click="removePlan($index)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="plan-footer" v-if="form.paymentSchedule.length > 0">
            付款计划总计：<span class="amount-tag">¥ {{ form.paymentSchedule.reduce((s, i) => s + (i.planAmount || 0), 0).toLocaleString(undefined, { minimumFractionDigits: 2 }) }}</span>
          </div>
        </div>
      </el-form>

      <div class="bottom-actions">
        <el-button size="large" @click="$router.back()">返回</el-button>
        <el-button size="large" type="primary" :loading="loading" @click="submit" class="submit-btn">
          {{ isEdit ? '保存更改' : '提交合同申请' }}
        </el-button>
      </div>
    </el-card>

    <!-- 添加供应商弹窗 -->
    <el-dialog v-model="supplierDialogVisible" title="新增供应商" width="560px" destroy-on-close>
      <el-form ref="supplierFormRef" :model="supplierForm" :rules="supplierRules" label-width="110px" style="max-width: 480px;">
        <el-form-item label="供应商编码" prop="supplierCode">
          <el-input v-model="supplierForm.supplierCode" placeholder="系统自动生成" />
        </el-form-item>
        <el-form-item label="供应商全称" prop="supplierName">
          <el-input v-model="supplierForm.supplierName" placeholder="请输入供应商全称" />
        </el-form-item>
        <el-form-item label="供应商简称">
          <el-input v-model="supplierForm.supplierShortName" placeholder="请输入供应商简称" />
        </el-form-item>
        <el-form-item label="统一信用代码">
          <el-input v-model="supplierForm.creditCode" placeholder="请输入统一信用代码" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="supplierForm.contactPhone" placeholder="请输入联系电话" />
        </el-form-item>
        <el-divider content-position="left" style="margin: 16px 0;">银行账户信息</el-divider>
        <el-form-item label="开户银行">
          <el-input v-model="supplierForm.bankName" placeholder="请输入开户银行" />
        </el-form-item>
        <el-form-item label="银行账号" label-width="110px">
          <el-input v-model="supplierForm.bankAccount" placeholder="请输入银行账号" />
        </el-form-item>
        <el-form-item label="账户名称" label-width="110px">
          <el-input v-model="supplierForm.accountName" placeholder="请输入账户名称" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="supplierDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="supplierFormLoading" @click="submitSupplier">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  padding: 0;
  background-color: #f5f7fa;
  min-height: calc(100vh - 60px);
}

.page-card {
  border-radius: 0;
  border: none;
  max-width: none;
  width: 100%;
  margin: 0;
  min-height: calc(100vh - 60px);
  box-shadow: none !important;
}

.page-header {
  margin-bottom: 32px;
  padding-bottom: 16px;
  border-bottom: 1px solid #f0f0f0;
}

.titleWrap {
  display: flex;
  align-items: center;
  gap: 12px;
}

.titleIcon {
  font-size: 24px;
  color: #409eff;
}

.title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.form {
  padding: 0 12px;
}

:deep(.el-form-item) {
  margin-bottom: 12px;
}

:deep(.el-form-item__label) {
  padding-right: 8px;
}

.divider-text {
  font-size: 15px;
  font-weight: 600;
  color: #606266;
}

.upload-item {
  margin-top: 10px;
}

.upload-tip {
  font-size: 13px;
  color: #909399;
  margin-top: 8px;
}

.uploaded-file {
  margin-top: 12px;
  padding: 10px 16px;
  background-color: #f0f7ff;
  border-radius: 4px;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid #d9ecff;
}

.uploaded-file a {
  color: #409eff;
  text-decoration: none;
  font-size: 14px;
  flex: 1;
}

.uploaded-file a:hover {
  text-decoration: underline;
}

.plans-section {
  margin: 20px 0 30px;
  background-color: #fafafa;
  border-radius: 8px;
  padding: 20px;
}

.plan-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.plan-title {
  font-size: 14px;
  font-weight: 600;
  color: #606266;
}

.plan-table {
  background-color: #fff;
}

.plan-footer {
  margin-top: 16px;
  text-align: right;
  font-size: 14px;
  color: #606266;
}

.amount-tag {
  color: #f56c6c;
  font-weight: 700;
  font-size: 16px;
}

.bottom-actions {
  margin-top: 40px;
  padding: 24px 0;
  border-top: 1px solid #f0f0f0;
  display: flex;
  justify-content: center;
  gap: 16px;
}

.submit-btn {
  min-width: 140px;
}

:deep(.el-form-item__label) {
  font-weight: 500;
}

:deep(.el-divider--horizontal) {
  margin: 20px 0 12px;
}
</style>
