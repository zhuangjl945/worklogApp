<script setup>
import { onMounted, reactive, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Tickets, Plus } from '@element-plus/icons-vue'
import { contractDetail, contractStart, paymentPlanRecord, paymentPlanUpdate, contractUpdateAttachments, contractRenew, contractAddPaymentPlan } from '../api/contract'

const router = useRouter()

async function doStart() {
  try {
    await ElMessageBox.confirm('启动合同将生成正式付款计划，确认启动吗？', '提示')
    await contractStart(id)
    ElMessage.success('启动成功')
    await load()
  } catch (e) {}
}

async function doRenew() {
  try {
    await ElMessageBox.confirm(`确认基于当前合同续签一份新合同吗？`, '提示', { type: 'warning' })
    const resp = await contractRenew(id)
    const newId = resp?.id ?? resp?.data?.id
    if (!newId) {
      ElMessage.error('续签成功但未获取到新合同ID，请刷新后重试')
      return
    }
    ElMessage.success('续签成功，已生成新草稿')
    router.push(`/contract/edit/${newId}`)
  } catch (e) {
    if (e && typeof e === 'object' && e.message) ElMessage.error(e.message)
  }
}
import { me } from '../api/auth'
import { ossPolicy, ossDeleteObject } from '../api/work'

const route = useRoute()
const id = route.params.id

const loading = ref(false)
const contract = ref(null)
const paymentPlans = ref([])

const myDeptId = ref(null)

const statusOptions = [
  { value: 10, label: '草稿' },
  { value: 30, label: '执行中' },
  { value: 40, label: '已完成' },
  { value: 50, label: '已终止' },
  { value: 60, label: '已过期' }
]

function getStatusText(v) {
  return statusOptions.find(x => x.value === v)?.label || v
}

function getStatusType(v) {
  if (v === 10) return 'info'
  if (v === 30) return 'success'
  if (v === 40) return 'success'
  if (v === 50) return 'danger'
  if (v === 60) return 'warning'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const resp = await contractDetail(id)
    const c = resp.data.contract

    if (myDeptId.value != null && c?.deptId != null && String(myDeptId.value) !== String(c.deptId)) {
      ElMessage.error('无权限查看该合同')
      contract.value = null
      paymentPlans.value = []
      return
    }

    contract.value = c
    paymentPlans.value = resp.data.paymentPlans
  } catch (e) {
    ElMessage.error(e?.message || '加载详情失败')
  } finally {
    loading.value = false
  }
}

// 付款登记相关
const payDialogVisible = ref(false)
const isEditPay = ref(false)
const currentPlanId = ref(null)
const payForm = reactive({
  actualDate: '',
  actualAmount: 0,
  voucherNo: '',
  payMethod: '银行转账',
  remark: ''
})

// 添加付款计划相关
const addPlanDialogVisible = ref(false)
const addPlanFormRef = ref()
const addPlanFormLoading = ref(false)
const addPlanForm = reactive({
  planNo: '',
  planAmount: 0,
  planDate: ''
})

const addPlanRules = {
  planNo: [{ required: true, message: '请输入阶段名称', trigger: 'blur' }],
  planAmount: [{ required: true, message: '请输入计划金额', trigger: 'blur' }],
  planDate: [{ required: true, message: '请选择计划日期', trigger: 'change' }]
}

function openPayRecord(row, edit = false) {
  currentPlanId.value = row.id
  isEditPay.value = edit
  if (edit) {
    payForm.actualDate = row.actualDate
    payForm.actualAmount = row.actualAmount
    payForm.voucherNo = row.voucherNo
    payForm.payMethod = row.payMethod
    payForm.remark = row.remark
  } else {
    payForm.actualDate = new Date().toISOString().split('T')[0]
    payForm.actualAmount = row.planAmount
    payForm.voucherNo = ''
    payForm.payMethod = '银行转账'
    payForm.remark = ''
  }
  payDialogVisible.value = true
}

async function submitPay() {
  try {
    const total = Number(contract.value?.contractAmount ?? 0)
    const actual = Number(payForm.actualAmount ?? 0)

    if (Number.isFinite(total) && Number.isFinite(actual) && actual > total) {
      ElMessage.error(`单笔实付金额(¥${actual.toFixed(2)})不能大于合同总金额(¥${total.toFixed(2)})`)
      return
    }

    if (isEditPay.value) {
      await paymentPlanUpdate(currentPlanId.value, payForm)
      ElMessage.success('修改成功')
    } else {
      await paymentPlanRecord(currentPlanId.value, payForm)
      ElMessage.success('登记成功')
    }
    payDialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

// 打开添加付款计划弹窗
function openAddPlan() {
  addPlanForm.planNo = `第${paymentPlans.value.length + 1}期`
  addPlanForm.planAmount = 0
  addPlanForm.planDate = ''
  addPlanDialogVisible.value = true
}

// 提交添加付款计划
async function submitAddPlan() {
  await addPlanFormRef.value?.validate?.(async (valid) => {
    if (!valid) return
    addPlanFormLoading.value = true
    try {
      await contractAddPaymentPlan(id, addPlanForm)
      ElMessage.success('添加成功')
      addPlanDialogVisible.value = false
      await load()
    } catch (e) {
      ElMessage.error(e?.message || '添加失败')
    } finally {
      addPlanFormLoading.value = false
    }
  })
}

// 附件调整相关
const attachDialogVisible = ref(false)
const uploadLoading = ref(false)
const pendingFiles = ref([])

const existingFiles = computed(() => {
  if (!contract.value?.contractFileUrl) return []
  try {
    const parsed = JSON.parse(contract.value.contractFileUrl)
    return Array.isArray(parsed) ? parsed : [contract.value.contractFileUrl]
  } catch (e) {
    return [contract.value.contractFileUrl]
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
    await ElMessageBox.confirm('确定要删除这个附件吗？', '提示', { type: 'warning' })

    const files = existingFiles.value.filter(x => x !== url)
    // 如果删到最后一个附件，允许附件字段为空（null）
    const newUrlStr = files.length > 0 ? JSON.stringify(files) : null
    await contractUpdateAttachments(id, { contractFileUrl: newUrlStr })

    const u = new URL(url)
    const key = u.pathname.startsWith('/') ? u.pathname.substring(1) : u.pathname
    await ossDeleteObject({ key })

    contract.value.contractFileUrl = newUrlStr
    ElMessage.success('已删除')
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e?.message || '删除失败')
    }
  }
}

async function submitAttachments() {
  if (pendingFiles.value.length === 0) {
    ElMessage.warning('请先选择要上传的附件')
    return
  }

  uploadLoading.value = true
  try {
    const newUrls = []
    for (const f of pendingFiles.value) {
      const policyResp = await ossPolicy({ dir: 'contracts' })
      const p = policyResp.data
      const formData = new FormData()
      formData.append('key', p.key)
      formData.append('policy', p.policy)
      formData.append('OSSAccessKeyId', p.accessKeyId)
      formData.append('signature', p.signature)
      formData.append('file', f)

      const resp = await fetch(p.host, { method: 'POST', body: formData })
      if (!resp.ok) throw new Error(`文件 ${f.name} 上传失败`)
      newUrls.push(p.url)
    }

    const allUrls = [...existingFiles.value, ...newUrls]
    const newUrlStr = JSON.stringify(allUrls)
    await contractUpdateAttachments(id, { contractFileUrl: newUrlStr })
    
    contract.value.contractFileUrl = newUrlStr
    pendingFiles.value = []
    attachDialogVisible.value = false
    ElMessage.success('附件调整成功')
  } catch (e) {
    ElMessage.error(e?.message || '上传失败')
  } finally {
    uploadLoading.value = false
  }
}

onMounted(async () => {
  try {
    const resp = await me()
    myDeptId.value = resp?.data?.deptId ?? null
  } catch {
    myDeptId.value = null
  }
  await load()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Tickets /></el-icon>
        <div class="title">合同详情</div>
        <el-tag :type="getStatusType(contract?.status)" v-if="contract">{{ getStatusText(contract?.status) }}</el-tag>
      </div>
      <div class="actions">
        <el-button @click="$router.back()">返回</el-button>
        <el-button type="warning" plain @click="doRenew">续签</el-button>
        <el-button v-if="contract?.status === 10" type="success" @click="doStart">启动合同</el-button>
        <el-button v-if="contract?.status === 10" type="primary" @click="$router.push(`/contract/edit/${id}`)">编辑</el-button>
        <el-button v-if="contract?.status === 30" type="primary" plain @click="attachDialogVisible = true">调整附件</el-button>
      </div>
    </div>

    <div class="content-grid" v-if="contract">
      <el-card class="info-card" shadow="never">
        <template #header><div class="card-header"><span>基本信息</span></div></template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="合同编号">
            {{ contract.contractNo }}
            <el-tag v-if="contract.renewFromNo" size="small" type="info" style="margin-left: 8px">
              续签自: {{ contract.renewFromNo }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="合同名称">{{ contract.contractName }}</el-descriptions-item>
          <el-descriptions-item label="合同金额"><span class="amount">¥ {{ contract.contractAmount }}</span></el-descriptions-item>
          <el-descriptions-item label="签订日期">{{ contract.signDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="开始日期">{{ contract.startDate }}</el-descriptions-item>
          <el-descriptions-item label="结束日期">{{ contract.endDate }}</el-descriptions-item>
          <el-descriptions-item label="参与方">
            <span v-if="contract.contractDirection === 1">供应商：{{ contract.supplierName }}</span>
            <span v-else>客户：{{ contract.customerName }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="负责人ID">{{ contract.managerId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="合同附件">
            <template v-if="contract.contractFileUrl">
              <div v-for="(u, idx) in (() => { try { const p = JSON.parse(contract.contractFileUrl); return Array.isArray(p) ? p : [contract.contractFileUrl] } catch { return [contract.contractFileUrl] } })()" :key="idx" style="margin: 2px 0">
                <el-link :href="u" target="_blank" rel="noopener" type="primary">查看附件{{ (() => { try { const p = JSON.parse(contract.contractFileUrl); return Array.isArray(p) && p.length > 1 } catch { return false } })() ? idx + 1 : '' }}</el-link>
              </div>
            </template>
            <span v-else>-</span>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card class="plan-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span>付款计划与记录</span>
            <div style="display: flex; gap: 8px; align-items: center;">
              <el-tag v-if="contract.status === 10" type="info">启动后可登记付款</el-tag>
              <el-button v-if="contract.status === 30 || contract.status === 60" type="primary" size="small" link :icon="Plus" @click="openAddPlan">添加计划</el-button>
            </div>
          </div>
        </template>
        
        <el-table :data="paymentPlans" border stripe size="small">
          <el-table-column prop="planNo" label="阶段" width="120" />
          <el-table-column prop="planAmount" label="计划金额" width="120" />
          <el-table-column prop="planDate" label="计划日期" width="110" />
          <el-table-column prop="actualAmount" label="实付金额" width="120">
            <template #default="{ row }">
              <span v-if="row.status === 20" style="color: #67C23A; font-weight: bold">{{ row.actualAmount }}</span>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="actualDate" label="实付日期" width="110" />
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 20 ? 'success' : (row.status === 30 ? 'danger' : 'info')">
                {{ row.status === 20 ? '已结算' : (row.status === 30 ? '已逾期' : '待处理') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="150">
            <template #default="{ row }">
              <el-button v-if="row.status !== 20 && contract.status >= 30" type="primary" size="small" @click="openPayRecord(row)">登记付款</el-button>
              <el-button v-if="row.status === 20" size="small" @click="openPayRecord(row, true)">修改记录</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-dialog v-model="attachDialogVisible" title="调整合同附件" width="600px">
      <div class="attach-manage">
        <div class="attach-section">
          <div class="section-title">现有附件</div>
          <div v-if="existingFiles.length > 0" class="file-list">
            <div v-for="(u, idx) in existingFiles" :key="idx" class="file-item">
              <el-link :href="u" target="_blank" type="primary">附件 {{ idx + 1 }}</el-link>
              <el-button link type="danger" size="small" @click="removeExistingFile(u)">移除</el-button>
            </div>
          </div>
          <div v-else class="empty-tip">暂无附件</div>
        </div>

        <el-divider />

        <div class="attach-section">
          <div class="section-title">追加上传</div>
          <el-upload
            multiple
            :auto-upload="false"
            :show-file-list="false"
            :on-change="handleFileChange"
            accept=".pdf,.jpg,.jpeg,.png,.doc,.docx"
          >
            <el-button type="primary" plain>选择文件</el-button>
          </el-upload>
          <div v-if="pendingFiles.length > 0" class="file-list pending">
            <div v-for="(f, idx) in pendingFiles" :key="idx" class="file-item">
              <span>{{ f.name }}</span>
              <el-button link type="danger" size="small" @click="removePendingFile(idx)">撤销</el-button>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="attachDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="uploadLoading" @click="submitAttachments" :disabled="pendingFiles.length === 0">确认上传并保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="payDialogVisible" :title="isEditPay ? '修改付款记录' : '登记付款结果'" width="500px">
      <el-form :model="payForm" label-width="100px">
        <el-form-item label="实际金额" required>
          <el-input-number v-model="payForm.actualAmount" :precision="2" :max="contract.value?.contractAmount ?? undefined" style="width: 100%" />
        </el-form-item>
        <el-form-item label="实际日期" required>
          <el-date-picker v-model="payForm.actualDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="凭证号">
          <el-input v-model="payForm.voucherNo" placeholder="财务凭证号" />
        </el-form-item>
        <el-form-item label="支付方式">
          <el-select v-model="payForm.payMethod" style="width: 100%">
            <el-option label="银行转账" value="银行转账" />
            <el-option label="现金" value="现金" />
            <el-option label="支票" value="支票" />
            <el-option label="其他" value="其他" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="payForm.remark" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="payDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPay">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="addPlanDialogVisible" title="添加付款计划" width="480px" destroy-on-close>
      <el-form ref="addPlanFormRef" :model="addPlanForm" :rules="addPlanRules" label-width="100px">
        <el-form-item label="阶段名称" prop="planNo">
          <el-input v-model="addPlanForm.planNo" placeholder="例如：第X期、验收款" />
        </el-form-item>
        <el-form-item label="计划金额" prop="planAmount">
          <el-input-number v-model="addPlanForm.planAmount" :precision="2" :min="0" style="width: 100%" placeholder="请输入计划金额" />
        </el-form-item>
        <el-form-item label="计划日期" prop="planDate">
          <el-date-picker v-model="addPlanForm.planDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" placeholder="选择计划日期" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addPlanDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="addPlanFormLoading" @click="submitAddPlan">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 18px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 20px; }
.titleWrap { display: flex; align-items: center; gap: 8px; }
.titleIcon { font-size: 22px; color: #3b82f6; }
.title { font-size: 18px; font-weight: 900; color: #111827; }
.content-grid { display: flex; flex-direction: column; gap: 20px; }
.amount { font-weight: bold; color: #f56c6c; font-size: 16px; }
.card-header { font-weight: bold; display: flex; justify-content: space-between; align-items: center; }
</style>
