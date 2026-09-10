<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Setting, Monitor, Upload, Lock, Cloudy, Timer, Tickets, Promotion, Plus, Delete, RefreshRight, InfoFilled, Document } from '@element-plus/icons-vue'
import { sysConfigList, sysConfigBatchSave, sysConfigCreate, sysConfigDelete } from '../api/sysConfig'

// ── 状态 ─────────────────────────────────────────────────────
const loading = ref(false)
const saving = ref(false)
const groups = ref([])
const activeGroup = ref('')

// 运行期真正生效的参数键清单（由后端下发，格式 group|key，支持 组|* 整组）：
// 清单外的参数只读，因为它们的实际取值来自服务端配置文件，在本页改了不会有任何效果。
const runtimeEffective = ref([])
const editableKeys = computed(() => new Set(runtimeEffective.value))

/** 这个参数改了是否真生效；后端没回清单时一律按可编辑处理，避免整页被锁死 */
function editable(item) {
  const keys = editableKeys.value
  if (!keys.size) return true
  return keys.has(item.configGroup + '|*') || keys.has(item.configGroup + '|' + item.configKey)
}

// 编辑态：以 id 为键暂存用户输入，保存时与原始值比对
const editValues = reactive({})
// 标记哪些参数被修改过（用于高亮提示）
const dirtyIds = computed(() => {
  const set = new Set()
  for (const g of groups.value) {
    for (const item of g.items) {
      if (editValues[item.id] !== undefined && editValues[item.id] !== item.configValue) {
        set.add(item.id)
      }
    }
  }
  return set
})

// ── 分组图标映射 ─────────────────────────────────────────────
const groupIcons = {
  system: Monitor,
  upload: Upload,
  rate_limit: Lock,
  oss: Cloudy,
  urgency: Timer,
  // 工单流转：标记完成之后的自动确认等规则，用「推进」图标区别于登记表单
  ticket_flow: Promotion,
  ticket_form: Tickets,
  logging: Document
}

const LOG_LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'OFF']

// ── 当前激活分组的参数列表 ──────────────────────────────────────
const activeItems = computed(() => {
  const g = groups.value.find(x => x.group === activeGroup.value)
  return g ? g.items : []
})

// ── 加载 ─────────────────────────────────────────────────────
async function load() {
  loading.value = true
  try {
    const resp = await sysConfigList()
    groups.value = resp.data?.groups || []
    runtimeEffective.value = resp.data?.runtimeEffective || []
    // 初始化编辑态
    for (const g of groups.value) {
      for (const item of g.items) {
        if (editValues[item.id] === undefined) {
          editValues[item.id] = item.configValue ?? ''
        }
      }
    }
    if (!activeGroup.value && groups.value.length > 0) {
      activeGroup.value = groups.value[0].group
    }
  } catch (e) {
    ElMessage.error(e?.message || '加载参数配置失败')
  } finally {
    loading.value = false
  }
}

// ── 保存当前分组 ──────────────────────────────────────────────
async function saveGroup() {
  const items = activeItems.value
  const changed = []
  for (const item of items) {
    // 只读项即使被改过也不提交：后端会整批拒绝（先校验后落库），带上它只会让合法的那几条也保存不了
    if (!editable(item)) continue
    const val = editValues[item.id]
    if (val !== undefined && val !== item.configValue) {
      changed.push({ id: item.id, configValue: val })
    }
  }
  if (changed.length === 0) {
    ElMessage.info('没有需要保存的修改')
    return
  }

  saving.value = true
  try {
    await sysConfigBatchSave(changed)
    // 回写原始值，清除脏标记
    for (const c of changed) {
      const target = items.find(i => i.id === c.id)
      if (target) target.configValue = c.configValue
    }
    ElMessage.success('保存成功')
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

// ── 重置当前分组 ──────────────────────────────────────────────
function resetGroup() {
  for (const item of activeItems.value) {
    editValues[item.id] = item.configValue ?? ''
  }
}

// ── 新增参数对话框 ────────────────────────────────────────────
const createDialogVisible = ref(false)
const createFormRef = ref()
const createForm = reactive({
  configGroup: 'system',
  configKey: '',
  configValue: '',
  configLabel: '',
  configDesc: '',
  sortOrder: 0
})
const createRules = {
  configGroup: [{ required: true, message: '请选择分组', trigger: 'change' }],
  configKey: [{ required: true, message: '请输入参数键名', trigger: 'blur' }],
  configLabel: [{ required: true, message: '请输入参数标签', trigger: 'blur' }]
}

function openCreateDialog() {
  createForm.configGroup = activeGroup.value || 'system'
  createForm.configKey = ''
  createForm.configValue = ''
  createForm.configLabel = ''
  createForm.configDesc = ''
  createForm.sortOrder = activeItems.value.length
  createDialogVisible.value = true
}

async function submitCreate() {
  await createFormRef.value?.validate?.(async (valid) => {
    if (!valid) return
    try {
      await sysConfigCreate(createForm)
      ElMessage.success('新增成功')
      createDialogVisible.value = false
      await load()
    } catch (e) {
      ElMessage.error(e?.message || '新增失败')
    }
  })
}

// ── 删除参数 ──────────────────────────────────────────────────
async function removeItem(item) {
  try {
    await ElMessageBox.confirm(
      `确认要删除参数「${item.configLabel}」(${item.configKey}) 吗？`,
      '确认删除', { type: 'warning' }
    )
    await sysConfigDelete(item.id)
    ElMessage.success('删除成功')
    await load()
  } catch (e) {
    if (e && typeof e === 'object' && e.message) {
      ElMessage.error(e.message)
    }
  }
}

// ── 输入类型推断：纯数字键用 InputNumber，其余用 Input ────────
function inputType(item) {
  const label = item.configLabel || ''
  const key = item.configKey || ''
  if (item.configGroup === 'logging' || /_level$/.test(key)) {
    return 'log-level'
  }
  // *_required / *_enabled 一律走开关：值就是约定的 1/0，让管理员手写数字迟早会填出 2 来
  // 文案由 switchLabels() 按键名分别给「必填/选填」和「开启/关闭」
  if (/(_required|_enabled)$/.test(key)) {
    return 'switch'
  }
  // 包含 "秒" / "次数" / "上限" / "MB" / "字数" / "张数" 的标签倾向于数字输入
  if (/秒|次数|上限|MB|分钟|小时|字数|张数|expire|count|qpm|size|min_|max_/i.test(label + key)) {
    return 'number'
  }
  return 'text'
}

/**
 * 开关两端的文案。
 * 同样是存 1/0，但 `*_required` 说的是「这个字段必填吗」，`*_enabled` 说的是「这个功能开吗」，
 * 用一套写死的「必填/选填」会让工单流转那个开关读起来莫名其妙。
 */
function switchLabels(item) {
  return /_enabled$/.test(item.configKey || '')
    ? { active: '开启', inactive: '关闭' }
    : { active: '必填', inactive: '选填' }
}

/** 开关只认 '1'/'true'，其余（空值、脏数据）一律当成关闭 */
function isSwitchOn(item) {
  const v = editValues[item.id] ?? item.configValue
  return String(v).trim() === '1' || String(v).trim().toLowerCase() === 'true'
}

function setSwitchValue(item, on) {
  editValues[item.id] = on ? '1' : '0'
}


function hasDirtyInGroup(g) {
  return g.items.some(item => dirtyIds.value.has(item.id))
}

function countDirtyInActive() {
  let count = 0
  for (const item of activeItems.value) {
    if (dirtyIds.value.has(item.id)) count++
  }
  return count
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <!-- 顶部工具栏 -->
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Setting /></el-icon>
        <div class="title">参数配置</div>
        <el-tag size="small" type="info" class="subtitle">管理系统运行参数，修改后即时生效</el-tag>
      </div>
      <div class="toolbar-actions">
        <el-button @click="load" :icon="RefreshRight">刷新</el-button>
        <el-button type="primary" plain @click="openCreateDialog" :icon="Plus">新增参数</el-button>
      </div>
    </div>

    <!-- 主体：侧边分组 Tab + 右侧参数卡片 -->
    <div class="layout-grid">
      <!-- 左侧分组导航 -->
      <div class="group-nav">
        <div
          v-for="g in groups"
          :key="g.group"
          class="group-tab"
          :class="{ active: activeGroup === g.group }"
          @click="activeGroup = g.group"
        >
          <el-icon class="group-tab-icon">
            <component :is="groupIcons[g.group] || Setting" />
          </el-icon>
          <div class="group-tab-info">
            <span class="group-tab-label">{{ g.label }}</span>
            <span class="group-tab-count">{{ g.items.length }} 项</span>
          </div>
          <div v-if="hasDirtyInGroup(g)" class="group-tab-dot" />
        </div>
      </div>

      <!-- 右侧参数编辑区 -->
      <div class="config-panel">
        <template v-if="activeItems.length > 0">
          <div class="config-card" v-for="item in activeItems" :key="item.id">
            <div class="config-card-header">
              <div class="config-card-label">
                {{ item.configLabel }}
                <el-tooltip v-if="item.configDesc" :content="item.configDesc" placement="top">
                  <el-icon class="config-card-tip"><InfoFilled /></el-icon>
                </el-tooltip>
              </div>
              <div class="config-card-meta">
                <el-tooltip
                  v-if="!editable(item)"
                  content="实际取值来自服务端配置文件，本页仅登记，改它不会生效"
                  placement="top"
                >
                  <el-tag size="small" effect="plain" type="warning">仅登记值</el-tag>
                </el-tooltip>
                <el-tag size="small" effect="plain" type="info">{{ item.configKey }}</el-tag>
                <el-button
                  size="small"
                  text
                  type="danger"
                  :icon="Delete"
                  @click="removeItem(item)"
                />
              </div>
            </div>
            <div class="config-card-body">
              <el-select
                v-if="inputType(item) === 'log-level'"
                v-model="editValues[item.id]"
                :disabled="!editable(item)"
                style="width: 100%"
              >
                <el-option v-for="lv in LOG_LEVELS" :key="lv" :label="lv" :value="lv" />
              </el-select>
              <!-- 开关类型（1/0）：登记表单的 *_required 与工单流转的 *_enabled 走这里 -->
              <el-switch
                v-else-if="inputType(item) === 'switch'"
                :model-value="isSwitchOn(item)"
                :active-text="switchLabels(item).active"
                :inactive-text="switchLabels(item).inactive"
                inline-prompt
                :disabled="!editable(item)"
                @update:model-value="setSwitchValue(item, $event)"
              />
              <!-- 数字类型：必须是 v-else-if，否则会和上面的开关同时渲染 -->
              <el-input-number
                v-else-if="inputType(item) === 'number'"
                v-model="editValues[item.id]"
                :min="0"
                controls-position="right"
                :disabled="!editable(item)"
                style="width: 100%"
              />
              <!-- 文本类型 -->
              <el-input
                v-else
                v-model="editValues[item.id]"
                :placeholder="item.configDesc || '请输入参数值'"
                :disabled="!editable(item)"
              />
            </div>
            <div v-if="dirtyIds.has(item.id)" class="config-card-dirty">
              <span class="dirty-dot" /> 已修改，未保存
            </div>
          </div>
        </template>

        <el-empty v-else description="该分组下暂无参数" />

        <!-- 底部保存栏 -->
        <div v-if="activeItems.length > 0" class="save-bar">
          <div class="save-bar-info">
            <template v-if="dirtyIds.size > 0">
              <span class="dirty-dot large" />
              有 <strong>{{ countDirtyInActive() }}</strong> 项未保存的修改
            </template>
            <template v-else>
              所有参数均为最新状态
            </template>
          </div>
          <div class="save-bar-actions">
            <el-button @click="resetGroup" :disabled="dirtyIds.size === 0">重置修改</el-button>
            <el-button type="primary" @click="saveGroup" :loading="saving" :disabled="dirtyIds.size === 0">
              保存配置
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 新增参数对话框 -->
    <el-dialog v-model="createDialogVisible" title="新增参数" width="520px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-position="top">
        <el-form-item label="所属分组" prop="configGroup">
          <el-select v-model="createForm.configGroup" style="width: 100%">
            <el-option
              v-for="g in groups"
              :key="g.group"
              :value="g.group"
              :label="g.label"
            />
          </el-select>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="参数键名" prop="configKey">
              <el-input v-model="createForm.configKey" placeholder="例如：max_retry_count" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="排序号">
              <el-input-number v-model="createForm.sortOrder" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="参数标签（中文）" prop="configLabel">
          <el-input v-model="createForm.configLabel" placeholder="例如：最大重试次数" />
        </el-form-item>
        <el-form-item label="参数值">
          <el-input v-model="createForm.configValue" placeholder="参数默认值" />
        </el-form-item>
        <el-form-item label="参数说明">
          <el-input
            v-model="createForm.configDesc"
            type="textarea"
            :rows="2"
            placeholder="鼠标悬浮时显示的提示文字"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">确定</el-button>
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
  margin-bottom: 16px;
  flex: 0 0 auto;
}

.titleWrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.titleIcon {
  font-size: 22px;
  color: var(--g-text);
}

.title {
  font-size: 18px;
  font-weight: 900;
  color: var(--g-text);
}

.subtitle {
  margin-left: 4px;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}

/* 主体布局：左侧分组导航 + 右侧参数面板 */
.layout-grid {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 16px;
  overflow: hidden;
}

/* 左侧分组导航 */
.group-nav {
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: #ffffff;
  border-radius: var(--g-radius-md);
  padding: 12px;
  border: 1px solid var(--g-border);
  align-self: start;
}

.group-tab {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.18s ease;
  position: relative;
}

.group-tab:hover {
  background: #f0f4ff;
}

.group-tab.active {
  background: linear-gradient(135deg, var(--g-text), var(--g-text));
  color: #ffffff;
  box-shadow: 0 4px 14px rgba(59, 130, 246, 0.3);
}

.group-tab-icon {
  font-size: 20px;
  flex-shrink: 0;
}

.group-tab:not(.active) .group-tab-icon {
  color: var(--g-text-muted);
}

.group-tab-info {
  display: flex;
  flex-direction: column;
  gap: 1px;
  flex: 1;
  min-width: 0;
}

.group-tab-label {
  font-size: 14px;
  font-weight: 600;
}

.group-tab-count {
  font-size: 11px;
  opacity: 0.7;
}

.group-tab-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f59e0b;
  flex-shrink: 0;
}

.group-tab.active .group-tab-dot {
  background: #fbbf24;
}

/* 右侧参数面板 */
.config-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
  padding-right: 4px;
  max-height: calc(100vh - 180px);
}

.config-card {
  background: #ffffff;
  border: 1px solid var(--g-border);
  border-radius: 12px;
  padding: 16px 20px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.config-card:hover {
  border-color: #c7d2fe;
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.06);
}

.config-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.config-card-label {
  font-size: 14px;
  font-weight: 700;
  color: var(--g-text-secondary);
  display: flex;
  align-items: center;
  gap: 6px;
}

.config-card-tip {
  font-size: 14px;
  color: var(--g-text-faint);
  cursor: help;
}

.config-card-meta {
  display: flex;
  align-items: center;
  gap: 6px;
}

.config-card-body {
  /* 输入框容器 */
}

.config-card-dirty {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  font-size: 12px;
  color: #f59e0b;
  font-weight: 500;
}

.dirty-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #f59e0b;
  display: inline-block;
}

.dirty-dot.large {
  width: 8px;
  height: 8px;
}

/* 底部保存栏 */
.save-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  background: var(--g-bg-muted);
  border: 1px solid var(--g-border);
  border-radius: 12px;
  backdrop-filter: blur(8px);
}

.save-bar-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--g-text-muted);
}

.save-bar-info strong {
  color: #f59e0b;
}

.save-bar-actions {
  display: flex;
  gap: 8px;
}

/* 响应式 */
@media (max-width: 900px) {
  .layout-grid {
    grid-template-columns: 1fr;
  }

  .group-nav {
    flex-direction: row;
    overflow-x: auto;
    padding: 8px;
  }

  .group-tab {
    flex-shrink: 0;
    padding: 8px 12px;
  }

  .group-tab-info {
    flex-direction: row;
    gap: 6px;
    align-items: center;
  }
}
</style>
