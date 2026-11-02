<script setup>
import { computed, ref, watch } from 'vue'
import { indexTagType, indexTypeText } from '../utils/dbDocView'

/**
 * 表结构清单组件。
 *
 * 一次分析可能带进几十张表，全摊开会把弹窗撑成滚不动的长条，所以用折叠面板，
 * 默认只展开前 expandCount 张。列表详情弹窗和文档详情页共用这一份，
 * 免得两处各写一套字段口径、改一处漏一处。
 */
const props = defineProps({
  tables: { type: Array, default: () => [] },
  expandCount: { type: Number, default: 3 }
})

const active = ref([])

watch(
  () => props.tables,
  (list) => {
    active.value = (list || []).slice(0, props.expandCount).map(tableKey)
  },
  { immediate: true }
)

/** 同名表挂在不同库用户下时不能串面板，唯一 key 由归一化层算好 */
function tableKey(t) {
  return t.key || t.tableName
}

function expandAll() {
  active.value = (props.tables || []).map(tableKey)
}

function collapseAll() {
  active.value = []
}

const columnTotal = computed(() => (props.tables || []).reduce((sum, t) => sum + (t.columns ? t.columns.length : 0), 0))
const indexTotal = computed(() => (props.tables || []).reduce((sum, t) => sum + (t.indexes ? t.indexes.length : 0), 0))
</script>

<template>
  <div class="db-ts">
    <el-empty v-if="!tables.length" description="这份文档里没有解析出任何表结构" :image-size="70" />
    <template v-else>
      <div class="db-ts-bar">
        <span>共 {{ tables.length }} 张表 · {{ columnTotal }} 个字段 · {{ indexTotal }} 个索引</span>
        <span class="db-ts-barOps">
          <el-link type="primary" :underline="false" @click="expandAll">全部展开</el-link>
          <el-link type="primary" :underline="false" @click="collapseAll">全部收起</el-link>
        </span>
      </div>

      <el-collapse v-model="active">
        <el-collapse-item v-for="t in tables" :key="tableKey(t)" :name="tableKey(t)">
          <template #title>
            <div class="db-ts-head">
              <span class="db-ts-name">{{ t.tableName }}</span>
              <span v-if="t.dbUser" class="db-ts-user">{{ t.dbUser }}</span>
              <span v-if="t.tableComment" class="db-ts-comment">{{ t.tableComment }}</span>
              <span class="db-ts-meta">{{ t.columns.length }} 字段 / {{ t.indexes.length }} 索引</span>
            </div>
          </template>

          <el-table :data="t.columns" size="small" border stripe max-height="360">
            <el-table-column type="index" label="#" width="48" align="center" />
            <el-table-column prop="name" label="字段名" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono">{{ row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="type" label="类型" width="110" />
            <el-table-column prop="length" label="长度" width="70" align="center">
              <template #default="{ row }">{{ row.length || '-' }}</template>
            </el-table-column>
            <el-table-column label="可空" width="70" align="center">
              <template #default="{ row }">
                <el-tag :type="row.nullable ? 'info' : 'danger'" size="small">
                  {{ row.nullable ? '是' : '否' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="主键" width="70" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.primaryKey" type="warning" size="small">PK</el-tag>
                <span v-else class="faint">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="defaultValue" label="默认值" width="110" show-overflow-tooltip>
              <template #default="{ row }">{{ row.defaultValue || '-' }}</template>
            </el-table-column>
            <el-table-column prop="comment" label="注释" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ row.comment || '-' }}</template>
            </el-table-column>
          </el-table>

          <template v-if="t.indexes.length">
            <div class="db-ts-sub">索引</div>
            <el-table :data="t.indexes" size="small" border stripe>
              <el-table-column prop="name" label="索引名" min-width="160" show-overflow-tooltip>
                <template #default="{ row }">
                  <span class="mono">{{ row.name }}</span>
                </template>
              </el-table-column>
              <el-table-column label="包含字段" min-width="220">
                <template #default="{ row }">{{ row.columns.join(', ') }}</template>
              </el-table-column>
              <el-table-column label="类型" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="indexTagType(row.type)" size="small">{{ indexTypeText(row.type) }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-collapse-item>
      </el-collapse>
    </template>
  </div>
</template>

<style scoped>
.db-ts-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: var(--g-text-muted);
  margin-bottom: 8px;
}

.db-ts-barOps {
  display: flex;
  gap: 12px;
}

.db-ts-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
}

.db-ts-name {
  font-family: var(--g-font-mono, 'Consolas', monospace);
  font-size: 14px;
  font-weight: 700;
  color: var(--g-text);
}

.db-ts-user {
  font-size: 12px;
  color: var(--g-text-muted);
  border: 1px solid var(--g-border-strong);
  border-radius: var(--g-radius-sm, 4px);
  padding: 0 6px;
}

.db-ts-comment {
  font-size: 13px;
  color: var(--g-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.db-ts-meta {
  margin-left: auto;
  padding-right: 8px;
  font-size: 12px;
  color: var(--g-text-faint);
}

.db-ts-sub {
  font-size: 13px;
  font-weight: 600;
  color: var(--g-text-secondary);
  margin: 14px 0 8px;
}

.mono {
  font-family: var(--g-font-mono, 'Consolas', monospace);
}

.faint {
  color: var(--g-text-faint);
}
</style>