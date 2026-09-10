<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Lock, RefreshRight, Key } from '@element-plus/icons-vue'
import { permissionList, permissionReset, permissionUpdate } from '../api/permission'
import { ensurePermissions, setPermissions } from '../utils/auth'

/* ========== 数据 ========== */
const loading = ref(false)
const saving = ref(false)
const items = ref([])
const roles = ref([])
// 角色级别：与后端 Role 枚举顺序一致，用来算「这一行能选哪些」
const LEVEL = { USER: 0, DEPT_ADMIN: 1, ADMIN: 2 }
// 每行的草稿值；不在 draft 里的表示未改动
const draft = reactive({})

const grouped = computed(() => {
  const map = new Map()
  for (const it of items.value) {
    if (!map.has(it.group)) map.set(it.group, [])
    map.get(it.group).push(it)
  }
  return [...map.entries()].map(([group, list]) => ({ group, list }))
})

const changed = computed(() =>
  Object.entries(draft)
    .filter(([key, role]) => {
      const it = items.value.find((x) => x.key === key)
      return it && it.minRole !== role
    })
    .map(([key, role]) => ({ key, role }))
)

/** 服务端已自定义（门槛高于内置下限）的项数：决定「恢复默认」能不能点 */
const customizedCount = computed(() => items.value.filter((x) => x.customized && !x.locked).length)

/** 某行可选的门槛：只能选不低于内置下限的角色（配置只可收紧，不可放宽） */
function optionsFor(item) {
  return roles.value.filter((r) => LEVEL[r.value] >= LEVEL[item.floor])
}

function currentValue(item) {
  return draft[item.key] || item.minRole
}

async function load() {
  loading.value = true
  try {
    const resp = await permissionList()
    items.value = resp.data?.items || []
    roles.value = resp.data?.roles || []
    for (const key of Object.keys(draft)) delete draft[key]
    // 用当前生效值预填下拉：下拉里显示真实值而不是灰色占位符；
    // 预填值等于生效值，changed 判定会把它们排除，不会误报「待保存」
    for (const it of items.value) draft[it.key] = it.minRole
  } catch (e) {
    ElMessage.error(e?.message || '加载权限配置失败')
  } finally {
    loading.value = false
  }
}

async function onSave() {
  if (!changed.value.length) return
  saving.value = true
  try {
    const resp = await permissionUpdate(changed.value)
    const perms = resp.data?.permissions || {}
    // 直接把新门槛灌进全局 store：当前页面的菜单立刻跟上，不用刷新
    setPermissions(perms)
    await ensurePermissions(true)
    const adjusted = resp.data?.adjusted || []
    if (adjusted.length) {
      // 被内置下限抬回去的项要明说，否则操作人会觉得「我明明选了普通员工」
      const names = adjusted.map((a) => `${a.label}（已按「${a.appliedLabel}」执行）`).join('；')
      ElMessage.warning(`部分权限不能放宽，超出下限的部分未生效：${names}`)
    } else {
      ElMessage.success('权限已保存，其他在线用户刷新页面后生效')
    }
    for (const key of Object.keys(draft)) delete draft[key]
    await load()
  } catch (e) {
    // 403 一类提示由 http 层统一弹，这里只兜其它异常
    if (e?.code !== 403) ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onReset() {
  try {
    await ElMessageBox.confirm(
      '把所有权限点恢复到代码内置的最低门槛，当前自定义的设置会全部丢失。确认继续？',
      '恢复默认',
      { type: 'warning', confirmButtonText: '恢复默认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  saving.value = true
  try {
    const perms = await permissionReset()
    setPermissions(perms.data || {})
    await ensurePermissions(true)
    for (const key of Object.keys(draft)) delete draft[key]
    await load()
    ElMessage.success('已恢复默认门槛')
  } catch (e) {
    if (e?.code !== 403) ElMessage.error(e?.message || '恢复失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Key /></el-icon>
        <div>
          <div class="title">权限设置</div>
          <div class="sub">调整各功能与数据范围要求的最低角色</div>
        </div>
      </div>
      <div class="actions">
        <el-button :icon="RefreshRight" :disabled="saving" @click="load">重新加载</el-button>
        <el-button :disabled="saving || (!customizedCount && !changed.length)" @click="onReset">恢复默认</el-button>
        <el-button type="primary" :loading="saving" :disabled="!changed.length" @click="onSave">
          保存{{ changed.length ? `（${changed.length}）` : '' }}
        </el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" class="notice">
      <template #title>三条规则，先看清楚再改</template>
      <ul class="noticeList">
        <li><b>只能收紧，不能放宽。</b>每项都有代码内置的最低门槛（下表「内置下限」），配得比它更松会被自动抬回去——防止误操作把系统参数、员工账号开放给普通员工。</li>
        <li><b>改完服务端立即生效</b>，但其他人已打开的页面要刷新一次才会更新菜单与按钮；越权请求一律返回 403。</li>
        <li><b>「权限设置」本身固定只给系统管理员</b>，可调入口若开放出去，一次误操作就会没人能再改权限。</li>
      </ul>
    </el-alert>

    <div v-loading="loading">
      <el-card v-for="g in grouped" :key="g.group" class="card" shadow="never">
        <template #header>
          <div class="cardHead">{{ g.group }}</div>
        </template>
        <el-table :data="g.list" border>
          <el-table-column label="功能" min-width="230">
            <template #default="{ row }">
              <div class="fnName">{{ row.label }}</div>
              <div class="fnDesc">{{ row.description }}</div>
            </template>
          </el-table-column>
          <el-table-column label="内置下限" width="120">
            <template #default="{ row }">
              <span class="floor">{{ row.floorLabel }}</span>
            </template>
          </el-table-column>
          <el-table-column label="要求角色" width="200">
            <template #default="{ row }">
              <el-select v-if="!row.locked" v-model="draft[row.key]" :placeholder="row.minRoleLabel" style="width: 100%">
                <el-option v-for="opt in optionsFor(row)" :key="opt.value" :value="opt.value" :label="opt.label" />
              </el-select>
              <span v-else class="locked">
                <el-icon><Lock /></el-icon>
                {{ row.minRoleLabel }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.locked" type="info" size="small">固定</el-tag>
              <el-tag v-else-if="currentValue(row) !== row.minRole" type="warning" size="small">待保存</el-tag>
              <el-tag v-else-if="row.customized" type="success" size="small">已自定义</el-tag>
              <el-tag v-else size="small">默认</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.page {
  padding: 18px;
}

.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.titleWrap {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.titleIcon {
  font-size: 22px;
  color: var(--g-text);
  margin-top: 2px;
}

.title {
  font-size: 18px;
  font-weight: 900;
  color: var(--g-text);
}

.sub {
  font-size: 12px;
  color: var(--g-text-muted);
  margin-top: 2px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.notice {
  margin-bottom: 14px;
}

.noticeList {
  margin: 4px 0 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--g-text-secondary, #404040);
}

.card {
  margin-bottom: 14px;
  border-radius: var(--g-radius-md);
}

.cardHead {
  font-size: 13px;
  font-weight: 700;
  color: var(--g-text);
}

.fnName {
  font-size: 13px;
  font-weight: 600;
  color: var(--g-text);
}

.fnDesc {
  font-size: 12px;
  color: var(--g-text-muted);
  margin-top: 2px;
  line-height: 1.5;
}

.floor {
  font-size: 12px;
  color: var(--g-text-faint);
}

.locked {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--g-text-muted);
}
</style>