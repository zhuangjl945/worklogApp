<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Download, Document, UploadFilled } from '@element-plus/icons-vue'
import { MAX_TABLES, downloadDictTemplate, parseDictWorkbook } from '../utils/dbDictExcel'

/**
 * Excel 数据字典导入。
 *
 * 只在浏览器里解析：文件不出机器，解析结果交给父页面，
 * 之后「预览差异 / 导入目录」用的还是那套后端接口，跟贴脚本完全同一条路。
 */
const props = defineProps({
  /** { tables, sourceRef, stats, warnings } —— 父页面持有，提交时直接带上 */
  modelValue: { type: Object, default: () => ({ tables: [], sourceRef: '', stats: null, warnings: [] }) }
})
const emit = defineEmits(['update:modelValue'])

const parsing = ref(false)
const fileName = ref('')

// 父页面「清空」或切换来源时把文件名一起清掉，残留的选中态会让人以为还挂着上一份结果
watch(
  () => props.modelValue?.tables?.length || 0,
  (count) => {
    if (!count) fileName.value = ''
  }
)

const parsed = computed(() => props.modelValue?.tables?.length > 0)
const stats = computed(() => props.modelValue?.stats || null)
const warnings = computed(() => props.modelValue?.warnings || [])

function push(payload) {
  emit('update:modelValue', payload)
}

/** 选完文件就地解析，解析失败要把状态清干净，免得留下一半的结果被当成成功 */
async function handleFile(uploadFile) {
  const file = uploadFile?.raw || uploadFile
  if (!file) return
  const lower = String(file.name || '').toLowerCase()
  if (!lower.endsWith('.xlsx') && !lower.endsWith('.xls')) {
    ElMessage.warning('只支持 .xlsx / .xls，其他格式请先另存为 Excel')
    return
  }
  parsing.value = true
  try {
    const buffer = await file.arrayBuffer()
    const result = parseDictWorkbook(buffer)
    fileName.value = file.name
    push({
      tables: result.tables,
      sourceRef: file.name + '（' + result.stats.tables + ' 张表 / ' + result.stats.columns + ' 个字段）',
      stats: result.stats,
      warnings: result.warnings
    })
    const warnTip = result.warnings.length ? '，' + result.warnings.length + ' 条提示请核对' : ''
    ElMessage.success('解析出 ' + result.stats.tables + ' 张表、' + result.stats.columns + ' 个字段' + warnTip)
  } catch (e) {
    fileName.value = ''
    push({ tables: [], sourceRef: '', stats: null, warnings: [] })
    ElMessage.error(e?.message || '解析失败，检查一下表头有没有「表名」「字段名」两列')
  } finally {
    parsing.value = false
  }
}

function handleClear() {
  fileName.value = ''
  push({ tables: [], sourceRef: '', stats: null, warnings: [] })
}

function handleTemplate() {
  try {
    downloadDictTemplate()
    ElMessage.success('模板已下载，照着「填写说明」那页写就行')
  } catch (e) {
    ElMessage.error(e?.message || '模板生成失败')
  }
}

/** 预览用：每张表一行，先让人确认解析得对不对再走差异比对 */
const rows = computed(() =>
  (props.modelValue?.tables || []).map((t) => ({
    tableName: t.tableName,
    dbUser: t.dbUser || '—',
    tableComment: t.tableComment || '',
    columnCount: (t.columns || []).length,
    pk: (t.indexes || []).find((i) => i.type === 'PRIMARY')?.columns?.join(', ') || '—',
    indexCount: (t.indexes || []).length
  }))
)
</script>

<template>
  <div class="dict-import">
    <div class="dictHead">
      <el-button :icon="Download" @click="handleTemplate">下载模板</el-button>
      <el-upload
        :auto-upload="false"
        :show-file-list="false"
        accept=".xlsx,.xls"
        :limit="1"
        @change="handleFile"
      >
        <el-button type="primary" :icon="UploadFilled" :loading="parsing">选择 Excel 文件</el-button>
      </el-upload>
      <el-button v-if="parsed" :icon="Delete" @click="handleClear">清空</el-button>
      <span class="dictFile">{{ fileName || '支持 .xlsx / .xls，一行写一个字段，只在浏览器解析，文件不出本机' }}</span>
    </div>

    <div v-if="stats" class="dictStats">
      <el-tag type="success" size="small">工作表 {{ stats.sheets }}</el-tag>
      <el-tag type="success" size="small">表 {{ stats.tables }} 张</el-tag>
      <el-tag type="success" size="small">字段 {{ stats.columns }} 个</el-tag>
      <el-tag v-if="stats.dbUsers?.length" size="small">
        库用户 {{ stats.dbUsers.join('、') }}
      </el-tag>
      <span class="dictLimit">单次上限 {{ MAX_TABLES }} 张表</span>
    </div>

    <el-alert
      v-if="warnings.length"
      class="dictWarn"
      type="warning"
      :closable="false"
      show-icon
      :title="`解析时有 ${warnings.length} 条提示，导入前请核对`"
    >
      <ul class="warnList">
        <li v-for="(w, i) in warnings" :key="i">{{ w }}</li>
      </ul>
    </el-alert>

    <el-table v-if="parsed" :data="rows" border stripe size="small" max-height="320" class="dictTable">
      <el-table-column prop="tableName" label="表名" min-width="150" show-overflow-tooltip />
      <el-table-column prop="dbUser" label="库用户" width="110" show-overflow-tooltip />
      <el-table-column prop="tableComment" label="表注释" min-width="150" show-overflow-tooltip />
      <el-table-column prop="columnCount" label="字段" width="70" align="center" />
      <el-table-column prop="pk" label="主键" min-width="120" show-overflow-tooltip />
      <el-table-column prop="indexCount" label="索引" width="70" align="center" />
    </el-table>

    <el-empty
      v-else
      :image-size="60"
      description="还没有解析结果。厂商给的表结构说明一般就是这种一行一个字段的表格，照着模板列头填即可"
    >
      <template #image>
        <el-icon :size="46" class="emptyIcon"><Document /></el-icon>
      </template>
    </el-empty>
  </div>
</template>

<style scoped>
.dict-import {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.dictHead {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.dictFile {
  font-size: 12px;
  color: var(--g-text-muted);
}

.dictStats {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.dictLimit {
  font-size: 12px;
  color: var(--g-text-faint);
}

.dictWarn :deep(.el-alert__title) {
  font-weight: 600;
}

.warnList {
  margin: 4px 0 0;
  padding-left: 18px;
  line-height: 1.7;
  font-size: 12px;
}

.dictTable {
  width: 100%;
}

.emptyIcon {
  color: var(--g-text-faint);
}
</style>