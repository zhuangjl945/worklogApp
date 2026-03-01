<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { OfficeBuilding } from '@element-plus/icons-vue'
import { deptCreate, deptDelete, deptTree, deptUpdate, deptUpdateStatus } from '../api/dept'

const loading = ref(false)
const treeData = ref([])
const filterText = ref('')
const treeRef = ref()

const currentNode = ref(null)

const dialogVisible = ref(false)
const dialogMode = ref('create')
const currentId = ref(null)

const formRef = ref()
const form = reactive({
  deptCode: '',
  deptName: '',
  parentId: null,
  sortOrder: 0,
  status: 1
})

const rules = {
  deptCode: [{ required: true, message: '请输入科室编码', trigger: 'blur' }],
  deptName: [{ required: true, message: '请输入科室名称', trigger: 'blur' }]
}

const flatOptions = computed(() => {
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
  walk(treeData.value, 0)
  return out
})

function normalizeTree(list) {
  const walk = (nodes) =>
    (nodes || []).map((n) => ({
      ...n,
      label: `${n.deptName}${n.deptCode ? ` (${n.deptCode})` : ''}`,
      children: walk(n.children)
    }))
  return walk(list)
}

async function load() {
  loading.value = true
  currentNode.value = null
  try {
    const resp = await deptTree()
    treeData.value = normalizeTree(resp.data)
    treeRef.value?.filter?.(filterText.value)
  } catch (e) {
    ElMessage.error(e?.message || '加载科室树失败')
  } finally {
    loading.value = false
  }
}

function onFilterInput() {
  treeRef.value?.filter?.(filterText.value)
}

function handleNodeClick(data) {
  currentNode.value = data
}

function openCreate(parent) {
  dialogMode.value = 'create'
  currentId.value = null
  form.deptCode = ''
  form.deptName = ''
  form.parentId = parent?.id ?? null
  form.sortOrder = 0
  form.status = 1
  dialogVisible.value = true
}

function openEdit(node) {
  dialogMode.value = 'edit'
  currentId.value = node.id
  form.deptCode = node.deptCode
  form.deptName = node.deptName
  form.parentId = node.parentId ?? null
  form.sortOrder = node.sortOrder ?? 0
  form.status = node.status ?? 1
  dialogVisible.value = true
}

async function submit() {
  await formRef.value?.validate?.(async (valid) => {
    if (!valid) return

    try {
      if (dialogMode.value === 'create') {
        await deptCreate({
          deptCode: form.deptCode,
          deptName: form.deptName,
          parentId: form.parentId,
          sortOrder: form.sortOrder,
          status: form.status
        })
        ElMessage.success('创建成功')
      } else {
        await deptUpdate(currentId.value, {
          deptName: form.deptName,
          parentId: form.parentId,
          sortOrder: form.sortOrder,
          status: form.status
        })
        ElMessage.success('更新成功')
      }

      dialogVisible.value = false
      await load()
    } catch (e) {
      ElMessage.error(e?.message || '提交失败')
    }
  })
}

async function toggleStatus(node) {
  const next = node.status === 1 ? 0 : 1
  const text = next === 1 ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(`确认要${text}该科室吗？`, '提示', { type: 'warning' })
    await deptUpdateStatus(node.id, next)
    ElMessage.success(`${text}成功`)
    await load()
  } catch (e) {
    if (e && typeof e === 'object' && e.message) {
      ElMessage.error(e.message)
    }
  }
}

async function remove(node) {
  try {
    await ElMessageBox.confirm('确认要删除该科室吗？（有子科室或科室下有用户会被后端拒绝）', '提示', { type: 'warning' })
    await deptDelete(node.id)
    ElMessage.success('删除成功')
    await load()
  } catch (e) {
    if (e && typeof e === 'object' && e.message) {
      ElMessage.error(e.message)
    }
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><OfficeBuilding /></el-icon>
        <div class="title">科室管理</div>
      </div>
      <el-button type="primary" @click="openCreate(null)">新增一级科室</el-button>
    </div>

    <div class="layout-grid">
      <el-card class="tree-card" shadow="never">
        <div class="tree-toolbar">
          <el-input
            v-model="filterText"
            placeholder="搜索科室名称"
            clearable
            @input="onFilterInput"
            @clear="onFilterInput"
          />
          <el-button @click="load">刷新</el-button>
        </div>
        <el-tree
          ref="treeRef"
          v-loading="loading"
          class="dept-tree"
          :data="treeData"
          node-key="id"
          :props="{ label: 'label', children: 'children' }"
          :filter-node-method="(val, data) => !val || data.deptName?.includes(val)"
          :default-expand-all="true"
          :expand-on-click-node="false"
          :highlight-current="true"
          empty-text="暂无科室"
          @node-click="handleNodeClick"
        >
          <template #default="{ data }">
            <div class="node">
              <div class="node-label" :title="data.label">{{ data.label }}</div>
              <el-tag size="small" :type="data.status === 1 ? 'success' : 'info'" class="node-tag">
                {{ data.status === 1 ? '启用' : '禁用' }}
              </el-tag>
            </div>
          </template>
        </el-tree>
      </el-card>

      <el-card class="detail-card" shadow="never">
        <template v-if="currentNode">
          <div class="detail-header">
            <div class="detail-title">{{ currentNode.deptName }}</div>
            <div class="detail-actions">
              <el-button size="small" @click="openCreate(currentNode)">新增子科室</el-button>
              <el-button size="small" type="primary" @click="openEdit(currentNode)">编辑</el-button>
              <el-button size="small" type="warning" @click="toggleStatus(currentNode)">
                {{ currentNode.status === 1 ? '禁用' : '启用' }}
              </el-button>
              <el-button size="small" type="danger" @click="remove(currentNode)">删除</el-button>
            </div>
          </div>
          <div class="detail-body">
            <el-descriptions :column="1" border>
              <el-descriptions-item label="科室名称">{{ currentNode.deptName }}</el-descriptions-item>
              <el-descriptions-item label="科室编码">{{ currentNode.deptCode }}</el-descriptions-item>
              <el-descriptions-item label="排序号">{{ currentNode.sortOrder ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag size="small" :type="currentNode.status === 1 ? 'success' : 'info'">
                  {{ currentNode.status === 1 ? '启用' : '禁用' }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>
          </div>
        </template>
        <el-empty v-else description="请从左侧选择一个科室查看详情" />
      </el-card>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新增科室' : '编辑科室'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item v-if="dialogMode === 'create'" label="科室编码" prop="deptCode">
          <el-input v-model="form.deptCode" placeholder="例如：KS001" />
        </el-form-item>
        <el-form-item v-else label="科室编码">
          <el-input v-model="form.deptCode" disabled />
        </el-form-item>

        <el-form-item label="科室名称" prop="deptName">
          <el-input v-model="form.deptName" placeholder="例如：内科" />
        </el-form-item>

        <el-form-item label="上级科室">
          <el-select v-model="form.parentId" clearable placeholder="无（作为一级科室）" style="width: 100%">
            <el-option :value="null" label="无（作为一级科室）" />
            <el-option v-for="opt in flatOptions" :key="opt.id" :value="opt.id" :label="opt.label" />
          </el-select>
        </el-form-item>

        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" style="width: 100%" />
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
        <el-button type="primary" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  padding: 18px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  flex: 0 0 auto;
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

.layout-grid {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 14px;
}

.tree-card, .detail-card {
  border-radius: 14px;
  display: flex;
  flex-direction: column;
}

.tree-toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}

.dept-tree {
  flex: 1 1 auto;
  overflow: auto;
  max-height: calc(100vh - 220px);
}

:deep(.el-tree-node__content) {
  height: auto;
  padding: 6px 0;
}

:deep(.el-tree-node.is-current > .el-tree-node__content) {
  background-color: var(--el-color-primary-light-9);
}

.node {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.node-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-tag {
  flex: 0 0 auto;
}

.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.detail-title {
  font-size: 16px;
  font-weight: 800;
}

.detail-actions {
  display: flex;
  gap: 8px;
}

.detail-body {
  flex: 1;
}

@media (max-width: 900px) {
  .layout-grid {
    grid-template-columns: 1fr;
  }
}
</style>
