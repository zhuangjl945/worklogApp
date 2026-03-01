<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { OfficeBuilding, Plus, Delete } from '@element-plus/icons-vue'
import { supplierCreate, supplierDetail, supplierUpdate, supplierContacts, supplierAddContact, supplierDeleteContact, supplierNextCode } from '../api/supplier'

const route = useRoute()
const router = useRouter()
const id = route.params.id
const isEdit = !!id

const loading = ref(false)
const activeTab = ref(route.query.tab || 'basic')

const formRef = ref()
const form = reactive({
  supplierCode: '',
  supplierName: '',
  supplierShortName: '',
  creditCode: '',
  legalRepresentative: '',
  contactAddress: '',
  contactPhone: '',
  bankName: '',
  bankAccount: '',
  accountName: ''
})

const rules = {
  supplierCode: [{ required: true, message: '请输入供应商编码', trigger: 'blur' }],
  supplierName: [{ required: true, message: '请输入供应商全称', trigger: 'blur' }]
}

const contacts = ref([])
const contactDialogVisible = ref(false)
const contactForm = reactive({
  contactName: '',
  contactPosition: '',
  contactPhone: '',
  contactEmail: '',
  isPrimary: 0
})

async function loadDetail() {
  if (!isEdit) {
    try {
      const resp = await supplierNextCode()
      form.supplierCode = resp.data.supplierCode
    } catch (e) {
      ElMessage.error('获取自动编码失败')
    }
    return
  }
  loading.value = true
  try {
    const resp = await supplierDetail(id)
    Object.assign(form, resp.data)
    await loadContacts()
  } catch (e) {
    ElMessage.error(e?.message || '加载详情失败')
  } finally {
    loading.value = false
  }
}

async function loadContacts() {
  if (!isEdit) return
  try {
    const resp = await supplierContacts(id)
    contacts.value = resp.data
  } catch (e) {
    ElMessage.error(e?.message || '加载联系人失败')
  }
}

async function submit() {
  await formRef.value?.validate?.(async (valid) => {
    if (!valid) return
    try {
      if (isEdit) {
        await supplierUpdate(id, form)
        ElMessage.success('更新成功')
        router.back()
      } else {
        await supplierCreate(form)
        ElMessage.success('创建成功')
        router.replace('/supplier/list')
      }
    } catch (e) {
      ElMessage.error(e?.message || '提交失败')
    }
  })
}

function openAddContact() {
  contactForm.contactName = ''
  contactForm.contactPosition = ''
  contactForm.contactPhone = ''
  contactForm.contactEmail = ''
  contactForm.isPrimary = 0
  contactDialogVisible.value = true
}

async function submitContact() {
  if (!contactForm.contactName) {
    ElMessage.warning('请输入姓名')
    return
  }
  try {
    await supplierAddContact(id, contactForm)
    ElMessage.success('添加成功')
    contactDialogVisible.value = false
    await loadContacts()
  } catch (e) {
    ElMessage.error(e?.message || '添加失败')
  }
}

async function removeContact(cid) {
  try {
    await ElMessageBox.confirm('确认删除该联系人吗？', '提示', { type: 'warning' })
    await supplierDeleteContact(id, cid)
    ElMessage.success('删除成功')
    await loadContacts()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e?.message || '删除失败')
  }
}

onMounted(loadDetail)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><OfficeBuilding /></el-icon>
        <div class="title">{{ isEdit ? '编辑供应商' : '新增供应商' }}</div>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="tabs-container">
      <el-tab-pane label="基本信息" name="basic">
        <el-card shadow="never" class="form-card">
          <el-form ref="formRef" :model="form" :rules="rules" label-width="120px" class="form">
            <el-row :gutter="20">
              <el-col :span="12">
                <el-form-item label="供应商编码" prop="supplierCode">
                  <el-input v-model="form.supplierCode" placeholder="SUPXXXXXX" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="统一信用代码" prop="creditCode">
                  <el-input v-model="form.creditCode" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="供应商全称" prop="supplierName">
              <el-input v-model="form.supplierName" />
            </el-form-item>
            <el-row :gutter="20">
              <el-col :span="12">
                <el-form-item label="简称" prop="supplierShortName">
                  <el-input v-model="form.supplierShortName" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="法定代表人" prop="legalRepresentative">
                  <el-input v-model="form.legalRepresentative" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="注册地址" prop="contactAddress">
              <el-input v-model="form.contactAddress" type="textarea" :rows="2" />
            </el-form-item>
            <el-form-item label="联系电话" prop="contactPhone">
              <el-input v-model="form.contactPhone" />
            </el-form-item>

            <el-divider content-position="left">银行账户信息</el-divider>
            <el-form-item label="开户银行" prop="bankName">
              <el-input v-model="form.bankName" />
            </el-form-item>
            <el-row :gutter="20">
              <el-col :span="12">
                <el-form-item label="银行账号" prop="bankAccount">
                  <el-input v-model="form.bankAccount" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="账户名称" prop="accountName">
                  <el-input v-model="form.accountName" />
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="联系人管理" name="contacts" :disabled="!isEdit">
        <el-card shadow="never">
          <div class="table-toolbar">
            <el-button type="primary" :icon="Plus" @click="openAddContact">新增联系人</el-button>
          </div>
          <el-table :data="contacts" border stripe>
            <el-table-column prop="contactName" label="姓名" width="120" />
            <el-table-column prop="contactPosition" label="职位" width="120" />
            <el-table-column prop="contactPhone" label="手机号" width="140" />
            <el-table-column prop="contactEmail" label="邮箱" />
            <el-table-column prop="isPrimary" label="主要联系人" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.isPrimary" type="success">是</el-tag>
                <span v-else>否</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button type="danger" :icon="Delete" circle @click="removeContact(row.id)" />
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <div class="bottomActions">
      <el-button @click="$router.back()">返回</el-button>
      <el-button type="primary" @click="submit">保存供应商</el-button>
    </div>

    <el-dialog v-model="contactDialogVisible" title="新增联系人" width="480px">
      <el-form :model="contactForm" label-width="80px">
        <el-form-item label="姓名" required>
          <el-input v-model="contactForm.contactName" />
        </el-form-item>
        <el-form-item label="职位">
          <el-input v-model="contactForm.contactPosition" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="contactForm.contactPhone" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="contactForm.contactEmail" />
        </el-form-item>
        <el-form-item label="主要联系人">
          <el-switch v-model="contactForm.isPrimary" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="contactDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitContact">确定</el-button>
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
.tabs-container { background: #fff; padding: 20px; border-radius: 14px; }
.form-card { border: none; }
.form { max-width: 800px; margin: 0 auto; }
.table-toolbar { margin-bottom: 16px; }
.bottomActions { margin-top: 18px; display: flex; justify-content: center; gap: 12px; }
</style>
