<script setup>
import { onMounted, onBeforeUnmount, ref, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { dbDocDetail } from '../api/dbDoc'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const detailData = ref(null)

// ECharts 实例
let chartInstance = null
const chartRef = ref(null)

/** 加载详情 */
async function loadDetail() {
  const id = route.params.id
  if (!id) return
  loading.value = true
  try {
    const resp = await dbDocDetail(id)
    detailData.value = resp.data
    await nextTick()
    renderERChart()
  } catch (e) {
    ElMessage.error(e?.message || '加载文档详情失败')
  } finally {
    loading.value = false
  }
}

/**
 * 推断表间关系：
 * 如果表 A 中有字段名以 _id 结尾（如 department_id），
 * 且该字段名去掉 _id 后与另一张表名匹配（如 department），
 * 则建立一条关系边。
 */
function inferRelations(tables) {
  const relations = []
  const tableNames = new Set(tables.map(t => t.tableName.toLowerCase()))
  const tableNameMap = {}
  tables.forEach(t => {
    tableNameMap[t.tableName.toLowerCase()] = t.tableName
  })

  tables.forEach(table => {
    (table.columns || []).forEach(col => {
      const colName = (col.name || '').toLowerCase()
      // 匹配以 _id 结尾的列名
      if (colName.endsWith('_id')) {
        const refTableName = colName.replace(/_id$/, '')
        if (tableNames.has(refTableName) && refTableName !== table.tableName.toLowerCase()) {
          relations.push({
            source: table.tableName,
            target: tableNameMap[refTableName],
            column: col.name
          })
        }
      }
    })
  })
  return relations
}

/** 渲染 ER 关系图 */
function renderERChart() {
  if (!chartRef.value || !detailData.value?.tables?.length) return

  if (chartInstance) {
    chartInstance.dispose()
  }
  chartInstance = echarts.init(chartRef.value)

  const tables = detailData.value.tables

  // 构建节点
  const nodes = tables.map((table, idx) => {
    // 节点标签：表名 + 字段列表（主键标 🔑）
    const fieldLines = (table.columns || []).slice(0, 8).map(col => {
      const prefix = col.primaryKey ? '🔑 ' : '  '
      return `${prefix}${col.name} (${col.type || ''})`
    })
    if ((table.columns || []).length > 8) {
      fieldLines.push(`  ... 等 ${table.columns.length} 个字段`)
    }
    const label = `${table.tableName}\n─────────\n${fieldLines.join('\n')}`

    return {
      name: table.tableName,
      symbolSize: 60 + Math.min((table.columns?.length || 0) * 3, 40),
      value: table.columns?.length || 0,
      label: {
        show: true,
        formatter: label,
        fontSize: 11,
        fontFamily: 'Consolas, Monaco, monospace',
        lineHeight: 16,
        padding: [8, 12],
        backgroundColor: 'rgba(255,255,255,0.92)',
        borderColor: '#e2e8f0',
        borderWidth: 1,
        borderRadius: 4
      },
      itemStyle: {
        color: ['#6366f1', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899'][idx % 7]
      }
    }
  })

  // 构建边
  const relations = inferRelations(tables)
  const links = relations.map(r => ({
    source: r.source,
    target: r.target,
    label: {
      show: true,
      formatter: r.column,
      fontSize: 10,
      color: '#64748b'
    },
    lineStyle: {
      color: '#94a3b8',
      width: 1.5,
      curveness: 0.2
    }
  }))

  chartInstance.setOption({
    tooltip: {
      trigger: 'item',
      formatter: (params) => {
        if (params.dataType === 'node') {
          return `<b>${params.name}</b><br/>字段数: ${params.value}`
        }
        return `${params.data.source} → ${params.data.target}`
      }
    },
    animationDuration: 800,
    animationEasingUpdate: 'quinticInOut',
    series: [
      {
        type: 'graph',
        layout: 'force',
        data: nodes,
        links: links,
        roam: true,
        draggable: true,
        focusNodeAdjacency: true,
        force: {
          repulsion: 800,
          edgeLength: [200, 400],
          gravity: 0.1
        },
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 3 }
        }
      }
    ]
  })
}

/** 格式化时间 */
function formatTime(t) {
  if (!t) return '-'
  return t.replace('T', ' ').substring(0, 19)
}

/** 窗口 resize 适配 */
function handleResize() {
  chartInstance?.resize()
}

onMounted(() => {
  loadDetail()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})
</script>

<template>
  <div class="db-doc-detail" v-loading="loading">
    <div class="pageHeader">
      <el-button @click="router.back()" size="small">返回</el-button>
      <h2 class="pageTitle">文档详情</h2>
    </div>

    <template v-if="detailData">
      <!-- 基本信息 -->
      <el-card shadow="never" class="infoCard">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="标题">{{ detailData.title || '(未命名)' }}</el-descriptions-item>
          <el-descriptions-item label="表数量">{{ detailData.tableCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(detailData.createdAt) }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- ER 关系图 -->
      <el-card shadow="never" class="chartCard">
        <template #header>
          <span class="sectionTitle">ER 关系图</span>
        </template>
        <div ref="chartRef" class="chartContainer"></div>
        <div v-if="!detailData.tables?.length" class="chartEmpty">暂无表结构数据</div>
      </el-card>

      <!-- 各表结构 -->
      <h3 class="sectionTitle" style="margin-top: 24px;">表结构详情</h3>
      <el-card
        v-for="table in (detailData.tables || [])"
        :key="table.tableName"
        shadow="never"
        class="tableCard"
      >
        <template #header>
          <div class="cardHeader">
            <span class="tableName">{{ table.tableName }}</span>
            <span v-if="table.comment" class="tableComment">{{ table.comment }}</span>
          </div>
        </template>

        <el-table :data="table.columns" size="small" border stripe>
          <el-table-column prop="name" label="字段名" min-width="120" />
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column prop="length" label="长度" width="70" align="center">
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
          <el-table-column prop="defaultValue" label="默认值" width="100">
            <template #default="{ row }">{{ row.defaultValue || '-' }}</template>
          </el-table-column>
          <el-table-column prop="comment" label="注释" min-width="150">
            <template #default="{ row }">{{ row.comment || '-' }}</template>
          </el-table-column>
        </el-table>

        <!-- 索引 -->
        <template v-if="table.indexes && table.indexes.length > 0">
          <h4 class="subTitle">索引</h4>
          <el-table :data="table.indexes" size="small" border stripe>
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
    </template>
  </div>
</template>

<style scoped>
.db-doc-detail {
  max-width: 1200px;
}

.pageHeader {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.pageTitle {
  font-size: 20px;
  font-weight: 700;
  color: var(--g-text);
  margin: 0;
}

.infoCard {
  margin-bottom: 20px;
}

.chartCard {
  margin-bottom: 20px;
}

.chartContainer {
  width: 100%;
  height: 500px;
}

.chartEmpty {
  text-align: center;
  padding: 40px;
  color: var(--g-text-muted);
}

.sectionTitle {
  font-size: 16px;
  font-weight: 600;
  color: var(--g-text);
  margin-bottom: 12px;
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
</style>
