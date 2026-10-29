<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dbDocList, dbDocDelete, dbDocDetail } from '../api/dbDoc'

const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const searchKey = ref('')

const filters = reactive({
  page: 1,
  size: 10
})

/** 加载列表 */
async function load() {
  loading.value = true
  try {
    const resp = await dbDocList({
      page: filters.page,
      size: filters.size,
      keyword: searchKey.value || undefined
    })
    tableData.value = resp.data?.records || []
    total.value = resp.data?.total || 0
  } catch (e) {
    ElMessage.error(e?.message || '加载文档列表失败')
  } finally {
    loading.value = false
  }
}

/** 搜索 */
function handleSearch() {
  filters.page = 1
  load()
}

/** 分页切换 */
function handlePageChange(page) {
  filters.page = page
  load()
}

/** 删除 */
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除文档「${row.title || row.id}」吗？`, '确认删除', {
      type: 'warning'
    })
    await dbDocDelete(row.id)
    ElMessage.success('删除成功')
    load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e?.message || '删除失败')
    }
  }
}

/** 查看详情弹窗 */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailData = ref(null)

async function showDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detailData.value = null
  try {
    const resp = await dbDocDetail(row.id)
    detailData.value = resp.data
  } catch (e) {
    ElMessage.error(e?.message || '加载详情失败')
  } finally {
    detailLoading.value = false
  }
}

/** 格式化时间 */
function formatTime(t) {
  if (!t) return '-'
  return t.replace('T', ' ').substring(0, 19)
}

onMounted(() => {
  load()
})
</script>

<template>
  <div class="db-doc-list">
    <h2 class="pageTitle">文档管理</h2>

    <!-- 搜索栏 -->
    <div class="toolbar">
      <el-input
        v-model="searchKey"
        placeholder="搜索标题关键字"
        clearable
        class="searchInput"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #append>
          <el-button @click="handleSearch">搜索</el-button>
        </template>
      </el-input>
    </div>

    <!-- 数据表格 -->
    <el-table :data="tableData" v-loading="loading" border stripe class="dataTable">
      <el-table-column prop="id" label="ID" width="70" align="center" />
      <el-table-column prop="title" label="标题" min-width="200">
        <template #default="{ row }">
          {{ row.title || '(未命名)' }}
        </template>
      </el-table-column>
      <el-table-column prop="tableCount" label="表数量" width="90" align="center">
        <template #default="{ row }">
          <el-tag size="small">{{ row.tableCount ?? '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180">
        <template #default="{ row }">
          {{ formatTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" align="center">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="showDetail(row)">查看详情</el-button>
          <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="paginationWrap">
      <el-pagination
        v-model:current-page="filters.page"
        :page-size="filters.size"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="handlePageChange"
      />
    </div>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="文档详情" width="800px" destroy-on-close>
      <div v-if="detailLoading" class="detailLoading">
        <el-skeleton :rows="6" animated />
      </div>
      <div v-else-if="detailData" class="detailContent">
        <el-descriptions :column="2" border size="small" class="detailDesc">
          <el-descriptions-item label="标题">{{ detailData.title || '(未命名)' }}</el-descriptions-item>
          <el-descriptions-item label="表数量">{{ detailData.tableCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="2">{{ formatTime(detailData.createdAt) }}</el-descriptions-item>
        </el-descriptions>

        <!-- 各表结构 -->
        <div v-for="table in (detailData.tables || [])" :key="table.tableName" class="detailTable">
          <h4 class="detailTableName">{{ table.tableName }}</h4>
          <el-table :data="table.columns" size="small" border stripe>
            <el-table-column prop="name" label="字段名" min-width="120" />
            <el-table-column prop="type" label="类型" width="90" />
            <el-table-column prop="length" label="长度" width="60" align="center">
              <template #default="{ row }">{{ row.length || '-' }}</template>
            </el-table-column>
            <el-table-column prop="nullable" label="可空" width="60" align="center">
              <template #default="{ row }">
                <el-tag :type="row.nullable ? 'info' : 'danger'" size="small">
                  {{ row.nullable ? '是' : '否' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="primaryKey" label="主键" width="60" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.primaryKey" type="warning" size="small">PK</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="comment" label="注释" min-width="120">
              <template #default="{ row }">{{ row.comment || '-' }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.db-doc-list {
  max-width: 1100px;
}

.pageTitle {
  font-size: 20px;
  font-weight: 700;
  margin-bottom: 20px;
  color: var(--g-text);
}

.toolbar {
  display: flex;
  margin-bottom: 16px;
}

.searchInput {
  max-width: 360px;
}

.dataTable {
  margin-bottom: 16px;
}

.paginationWrap {
  display: flex;
  justify-content: flex-end;
}

.detailLoading {
  padding: 20px;
}

.detailContent {
  max-height: 60vh;
  overflow-y: auto;
}

.detailDesc {
  margin-bottom: 16px;
}

.detailTable {
  margin-top: 16px;
}

.detailTableName {
  font-size: 14px;
  font-weight: 700;
  font-family: 'Consolas', 'Monaco', monospace;
  margin-bottom: 8px;
  color: var(--g-text);
}
</style>
