<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CollectionTag } from '@element-plus/icons-vue'
import {
  workCategoryPage,
  workCategoryCreate,
  workCategoryUpdate,
  workCategoryDelete
} from '../api/work'
import { CATEGORY_TEMPLATE_PRESETS, parseCategoryTemplate, stringifyCategoryTemplate } from '../utils/categoryTemplate.js'

const loading = ref(false)
const saving = ref(false)

const query = reactive({
  page: 1,
  size: 10,
  status: null
})

const pageData = reactive({
  total: 0,
  records: []
})

const dialogVisible = ref(false)
const dialogMode = ref('create')
const formRef = ref()

const form = reactive({
  id: null,
  categoryCode: '',
  categoryName: '',
  description: '',
  status: 1,
  titlePattern: '',
  contentTemplate: '',
  requireContent: false,
  requireEndTime: false,
  requireImage: false
})

const rules = {
  categoryCode: [{ required: true, message: '请输入分类编码', trigger: 'blur' }],
  categoryName: [{ required: true, message: '请输入分类名称', trigger: 'blur' }]
}

const dialogTitle = computed(() => (dialogMode.value === 'create' ? '新增工作分类' : '编辑工作分类'))

function statusText(val) {
  return val === 1 ? '启用' : '禁用'
}

async function fetchPage() {
  loading.value = true
  try {
    const params = {
      page: query.page,
      size: query.size
    }
    if (query.status !== null && query.status !== undefined && query.status !== '') {
      params.status = query.status
    }

    const resp = await workCategoryPage(params)
    const data = resp.data
    pageData.total = data?.total || 0
    pageData.records = data?.records || []
  } catch (e) {
    ElMessage.error(e?.message || '加载工作分类失败')
  } finally {
    loading.value = false
  }
}

function onSearch() {
  query.page = 1
  fetchPage()
}

function onReset() {
  query.page = 1
  query.size = 10
  query.status = null
  fetchPage()
}

function resetTemplateFields() {
  form.titlePattern = ''
  form.contentTemplate = ''
  form.requireContent = false
  form.requireEndTime = false
  form.requireImage = false
}

function applyTemplateToForm(json) {
  const tpl = parseCategoryTemplate(json)
  if (!tpl) {
    resetTemplateFields()
    return
  }
  form.titlePattern = tpl.titlePattern
  form.contentTemplate = tpl.contentTemplate
  form.requireContent = tpl.requireContent
  form.requireEndTime = tpl.requireEndTime
  form.requireImage = tpl.requireImage
}

function applyPreset(preset) {
  form.titlePattern = preset.titlePattern
  form.contentTemplate = preset.contentTemplate
  form.requireContent = preset.requireContent
  form.requireEndTime = preset.requireEndTime
  form.requireImage = preset.requireImage
}

function templatePayload() {
  return stringifyCategoryTemplate({
    titlePattern: form.titlePattern,
    contentTemplate: form.contentTemplate,
    requireContent: form.requireContent,
    requireEndTime: form.requireEndTime,
    requireImage: form.requireImage
  })
}

function hasTemplate(row) {
  return !!parseCategoryTemplate(row?.templateJson)
}

function openCreate() {
  dialogMode.value = 'create'
  form.id = null
  form.categoryCode = ''
  form.categoryName = ''
  form.description = ''
  form.status = 1
  resetTemplateFields()
  dialogVisible.value = true
}

function openEdit(row) {
  dialogMode.value = 'edit'
  form.id = row.id
  form.categoryCode = row.categoryCode
  form.categoryName = row.categoryName
  form.description = row.description
  form.status = row.status
  applyTemplateToForm(row.templateJson)
  dialogVisible.value = true
}

async function onSubmit() {
  await formRef.value?.validate()
  saving.value = true
  try {
    if (dialogMode.value === 'create') {
      await workCategoryCreate({
        categoryCode: form.categoryCode,
        categoryName: form.categoryName,
        description: form.description,
        templateJson: templatePayload(),
        status: form.status
      })
      ElMessage.success('新增成功')
    } else {
      await workCategoryUpdate(form.id, {
        categoryName: form.categoryName,
        description: form.description,
        templateJson: templatePayload(),
        status: form.status
      })
      ElMessage.success('保存成功')
    }
    dialogVisible.value = false
    fetchPage()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除工作分类「${row.categoryName}」？删除后将变为禁用状态。`, '提示', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }

  try {
    await workCategoryDelete(row.id)
    ElMessage.success('删除成功')
    fetchPage()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

async function onToggleStatus(row, status) {
  try {
    await workCategoryUpdate(row.id, {
      categoryName: row.categoryName,
      description: row.description,
      status
    })
    ElMessage.success('操作成功')
    fetchPage()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

function onSizeChange(size) {
  query.size = size
  query.page = 1
  fetchPage()
}

function onCurrentChange(page) {
  query.page = page
  fetchPage()
}

onMounted(fetchPage)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><CollectionTag /></el-icon>
        <div class="title">工作分类</div>
      </div>
    </div>

    <div class="content">
      <el-card shadow="never" class="card">
        <div class="filter-bar">
          <el-form :model="query" inline class="filter-form">
            <el-form-item label="状态">
              <el-select v-model="query.status" clearable placeholder="选择状态" style="width: 180px">
                <el-option :value="null" label="全部" />
                <el-option :value="1" label="启用" />
                <el-option :value="0" label="禁用" />
              </el-select>
            </el-form-item>
            <el-form-item class="action-buttons">
              <el-button type="primary" @click="onSearch">查询</el-button>
              <el-button @click="onReset">重置</el-button>
            </el-form-item>
          </el-form>
        </div>

        <div class="table-actions" style="margin-bottom: 16px;">
          <el-button type="primary" @click="openCreate">新增分类</el-button>
          <el-button @click="fetchPage">刷新</el-button>
        </div>

        <el-table :data="pageData.records" v-loading="loading" border stripe row-key="id">
          <el-table-column prop="categoryCode" label="分类编码" min-width="140" />
          <el-table-column prop="categoryName" label="分类名称" min-width="180" />
          <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
          <el-table-column label="模板" width="90">
            <template #default="{ row }">
              <el-tag v-if="hasTemplate(row)" type="success" size="small">已配置</el-tag>
              <span v-else style="color: #94a3b8;">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-switch
                v-model="row.status"
                :active-value="1"
                :inactive-value="0"
                inline-prompt
                active-text="启"
                inactive-text="禁"
                @change="(val) => onToggleStatus(row, val)"
              />
            </template>
          </el-table-column>
          <el-table-column prop="updateTime" label="更新时间" min-width="170" />
          <el-table-column label="操作" width="260" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="openEdit(row)">编辑</el-button>

              <el-button size="small" type="danger" @click="onDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          class="pagination"
          background
          layout="prev, pager, next, sizes, total"
          :total="pageData.total"
          :current-page="query.page"
          :page-size="query.size"
          :page-sizes="[10, 20, 50, 100]"
          @update:current-page="onCurrentChange"
          @update:page-size="onSizeChange"
        />
      </el-card>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="640px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="分类编码" prop="categoryCode">
          <el-input v-model="form.categoryCode" placeholder="如：DEV_TASK" :disabled="dialogMode !== 'create'" />
        </el-form-item>
        <el-form-item label="分类名称" prop="categoryName">
          <el-input v-model="form.categoryName" placeholder="如：开发任务" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
        <el-form-item label="套用预设">
          <div class="preset-row">
            <el-button v-for="p in CATEGORY_TEMPLATE_PRESETS" :key="p.key" size="small" @click="applyPreset(p)">
              {{ p.label }}
            </el-button>
            <el-button size="small" text type="danger" @click="resetTemplateFields">清空模板</el-button>
          </div>
        </el-form-item>
        <el-form-item label="标题结构">
          <el-input v-model="form.titlePattern" placeholder="如：报修-{业务科室}-{现象}，可用 {日期} {业务科室} {分类}" />
        </el-form-item>
        <el-form-item label="内容骨架">
          <el-input v-model="form.contentTemplate" type="textarea" :rows="5" placeholder="新建时预填到内容框，引导把关键信息写全" />
        </el-form-item>
        <el-form-item label="必填项">
          <el-checkbox v-model="form.requireContent">必须填写内容</el-checkbox>
          <el-checkbox v-model="form.requireEndTime">必须填写截止日期</el-checkbox>
          <el-checkbox v-model="form.requireImage">必须上传图片</el-checkbox>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
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
  padding-bottom: 2px;
}

.pager {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
}

.preset-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
</style>
