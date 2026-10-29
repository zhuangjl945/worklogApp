<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { dbDocParse, dbDocSave } from '../api/dbDoc'

// SQL 输入
const sql = ref('')
const title = ref('')
const loading = ref(false)
const saving = ref(false)

// 解析结果：表结构数组
const tables = ref([])

/** 解析预览 */
async function handleParse() {
  if (!sql.value.trim()) {
    ElMessage.warning('请输入 SQL 语句')
    return
  }
  loading.value = true
  tables.value = []
  try {
    const resp = await dbDocParse(sql.value)
    tables.value = resp.data?.tables || []
    if (tables.value.length === 0) {
      ElMessage.info('未解析到表结构')
    } else {
      ElMessage.success(`解析成功，共 ${tables.value.length} 张表`)
    }
  } catch (e) {
    ElMessage.error(e?.message || '解析失败')
  } finally {
    loading.value = false
  }
}

/** 保存文档 */
async function handleSave() {
  if (!sql.value.trim()) {
    ElMessage.warning('请输入 SQL 语句')
    return
  }
  saving.value = true
  try {
    await dbDocSave(sql.value, title.value || undefined)
    ElMessage.success('保存成功')
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 清空 */
function handleClear() {
  sql.value = ''
  title.value = ''
  tables.value = []
}
</script>

<template>
  <div class="db-doc-parse">
    <h2 class="pageTitle">SQL 分析</h2>

    <!-- 输入区 -->
    <el-card shadow="never" class="inputCard">
      <el-form label-width="60px">
        <el-form-item label="标题">
          <el-input v-model="title" placeholder="可选，给文档起个名字" clearable />
        </el-form-item>
        <el-form-item label="SQL">
          <el-input
            v-model="sql"
            type="textarea"
            :rows="10"
            placeholder="请输入 CREATE TABLE 语句，多条 SQL 以分号分隔"
            class="sqlTextarea"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleParse">解析预览</el-button>
          <el-button type="success" :loading="saving" @click="handleSave">保存文档</el-button>
          <el-button @click="handleClear">清空</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 解析结果 -->
    <div v-if="tables.length > 0" class="resultArea">
      <h3 class="sectionTitle">解析结果（{{ tables.length }} 张表）</h3>

      <el-card v-for="table in tables" :key="table.tableName" shadow="never" class="tableCard">
        <template #header>
          <div class="cardHeader">
            <span class="tableName">{{ table.tableName }}</span>
            <span v-if="table.comment" class="tableComment">{{ table.comment }}</span>
          </div>
        </template>

        <!-- 字段表格 -->
        <h4 class="subTitle">字段信息</h4>
        <el-table :data="table.columns" size="small" border stripe class="colTable">
          <el-table-column prop="name" label="字段名" min-width="120" />
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column prop="length" label="长度" width="70" align="center">
            <template #default="{ row }">
              {{ row.length || '-' }}
            </template>
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
          <el-table-column prop="defaultValue" label="默认值" width="100">
            <template #default="{ row }">
              {{ row.defaultValue || '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="comment" label="注释" min-width="150">
            <template #default="{ row }">
              {{ row.comment || '-' }}
            </template>
          </el-table-column>
        </el-table>

        <!-- 索引表格 -->
        <template v-if="table.indexes && table.indexes.length > 0">
          <h4 class="subTitle">索引信息</h4>
          <el-table :data="table.indexes" size="small" border stripe class="idxTable">
            <el-table-column prop="name" label="索引名" min-width="150" />
            <el-table-column prop="columns" label="包含字段" min-width="200">
              <template #default="{ row }">
                {{ Array.isArray(row.columns) ? row.columns.join(', ') : row.columns }}
              </template>
            </el-table-column>
            <el-table-column prop="unique" label="唯一" width="70" align="center">
              <template #default="{ row }">
                <el-tag :type="row.unique ? 'success' : 'info'" size="small">
                  {{ row.unique ? '是' : '否' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.db-doc-parse {
  max-width: 1100px;
}

.pageTitle {
  font-size: 20px;
  font-weight: 700;
  margin-bottom: 20px;
  color: var(--g-text);
}

.inputCard {
  margin-bottom: 24px;
}

.sqlTextarea :deep(textarea) {
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
}

.resultArea {
  margin-top: 8px;
}

.sectionTitle {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 16px;
  color: var(--g-text);
}

.tableCard {
  margin-bottom: 20px;
}

.cardHeader {
  display: flex;
  align-items: center;
  gap: 12px;
}

.tableName {
  font-size: 15px;
  font-weight: 700;
  font-family: 'Consolas', 'Monaco', monospace;
}

.tableComment {
  font-size: 13px;
  color: var(--g-text-muted);
}

.subTitle {
  font-size: 14px;
  font-weight: 600;
  margin: 16px 0 8px;
  color: var(--g-text-secondary);
}

.colTable, .idxTable {
  margin-bottom: 8px;
}
</style>
