<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter, RouterView } from 'vue-router'
import { HomeFilled, Document, OfficeBuilding, UserFilled, TrendCharts, Tickets, Postcard, CollectionTag, FolderOpened, Bell, Setting, DocumentCopy, PieChart, EditPen, Fold, Expand, Close, Microphone, Mute, Key, Avatar, View as ViewIcon } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { can, clearProfile, ensurePermissions, ensureProfile, roleLabel } from '../utils/auth'
import { workRecordTransferAccept, workRecordTransferPending, workRecordTransferReject } from '../api/work'
import { ticketPendingCount } from '../api/ticket'
import {
  announceText,
  bindSpeechKeepAlive,
  isVoiceAlertEnabled,
  REPEAT_MS,
  setVoiceAlertEnabled,
  shouldAnnounceIncrease,
  speakTicketAlert,
  stopTicketAlert,
  unlockSpeech
} from '../utils/ticketVoiceAlert'

const router = useRouter()
const route = useRoute()

const loading = ref(true)
const errorMsg = ref('')
const user = ref(null)
const isSiderCollapsed = ref(localStorage.getItem('siderCollapsed') === 'true')

// Tab 管理系统
const tabs = ref([
  { path: '/home', label: '首页', closable: false }
])
const activeTab = ref('/home')

function toggleSider() {
  isSiderCollapsed.value = !isSiderCollapsed.value
  localStorage.setItem('siderCollapsed', isSiderCollapsed.value)
}

const displayName = computed(() => user.value?.realName || user.value?.username || '用户')

// 菜单项映射，用于获取标签名称
const menuLabelMap = {}
// roles 是代码内置下限（权限配置读不到时兜底），permission 是「权限设置」里可调的那道门槛。
// 两者都写是因为二者语义一致（最低角色），但 permission 的值由管理员配置，可能高于内置下限。
const menuItems = [
  { path: '/home', label: '首页', icon: HomeFilled },
  { path: '/board', label: '看板', icon: ViewIcon },
  { path: '/work-records', label: '工作记录', icon: EditPen },
  { path: '/tickets', label: '问题受理', icon: Tickets },
  {
    label: '合同管理',
    icon: DocumentCopy,
    children: [
      { path: '/contract/list', label: '合同列表', icon: Document },
      { path: '/supplier/list', label: '供应商管理', icon: OfficeBuilding }
    ]
  },
  {
    label: '报表统计',
    icon: TrendCharts,
    children: [
      { path: '/reports/workload-category', label: '工作量分类统计', icon: PieChart }
    ]
  },
  {
    label: '系统管理',
    icon: Setting,
    children: [
      { path: '/depts', label: '科室管理', icon: FolderOpened, roles: ['ADMIN'], permission: 'dept.manage' },
      { path: '/users', label: '员工管理', icon: UserFilled, roles: ['ADMIN'], permission: 'user.manage' },
      { path: '/user-roles', label: '人员角色设置', icon: Avatar, roles: ['ADMIN'], permission: 'user.manage' },
      { path: '/work-categories', label: '工作分类维护', icon: CollectionTag, roles: ['DEPT_ADMIN'], permission: 'workCategory.manage' },
      { path: '/ticket-channels', label: '登记渠道维护', icon: Postcard, roles: ['DEPT_ADMIN'], permission: 'ticketChannel.manage' },
      { path: '/sys-configs', label: '参数配置', icon: Setting, roles: ['ADMIN'], permission: 'sysConfig.manage' },
      { path: '/permissions', label: '权限设置', icon: Key, roles: ['ADMIN'], permission: 'permission.manage' }
    ]
  }
]

// 按权限点过滤菜单：子项全被过滤掉的分组一起隐藏，避免出现空的「系统管理」壳子
function filterMenuByPermission(items) {
  return items
    .map((item) => (item.children ? { ...item, children: filterMenuByPermission(item.children) } : item))
    .filter((item) => (item.children ? item.children.length > 0 : can(item.permission, item.roles)))
}

const visibleMenuItems = computed(() => filterMenuByPermission(menuItems))

// 扁平化菜单并构建映射（用未过滤的全量，已打开的 Tab 才能拿到标签）
function flattenMenu(items, parentLabel = '') {
  items.forEach(item => {
    if (item.children) {
      flattenMenu(item.children, item.label)
    } else {
      menuLabelMap[item.path] = item.label
    }
  })
}
flattenMenu(menuItems)

function switchTab(path) {
  activeTab.value = path
  router.push(path)
}

function closeTab(path, event) {
  event.stopPropagation()
  const index = tabs.value.findIndex(t => t.path === path)
  if (index > -1) {
    tabs.value.splice(index, 1)
    if (activeTab.value === path) {
      const newActive = tabs.value[Math.max(0, index - 1)]
      if (newActive) {
        activeTab.value = newActive.path
        router.push(newActive.path)
      }
    }
  }
}

// 监听路由变化，打开新 Tab
router.afterEach((to) => {
  if (!tabs.value.find(t => t.path === to.path)) {
    const label = menuLabelMap[to.path] || to.meta?.label || to.path
    tabs.value.push({ path: to.path, label, closable: to.path !== '/home' })
  }
  activeTab.value = to.path
})

async function loadMe() {
  loading.value = true
  errorMsg.value = ''
  // 走 utils/auth 的共享档案：路由守卫、各页面的按钮收口读的是同一份，
  // 免得「守卫以为他是管理员、菜单以为他不是」这种两边不一致
  const profile = await ensureProfile(true)
  // 权限点门槛和档案一起刷新：管理员刚调严某项，刷新页面就能看到菜单收掉
  await ensurePermissions(true)
  user.value = profile
  if (!profile) {
    errorMsg.value = '加载用户信息失败，请重新登录'
  } else if (profile.roleStale) {
    // 角色在登录后被管理员调整过：本次仍按旧角色生效，必须说清楚，否则用户只觉得入口莫名少了
    ElMessage.warning('账号角色已由管理员调整，退出重新登录后生效')
  }
  loading.value = false
}

const transferPopoverVisible = ref(false)
const pendingTransferCount = ref(0)
const pendingTransferRecords = ref([])
let transferPollTimer = null

async function loadPendingTransfers() {
  try {
    const resp = await workRecordTransferPending({ limit: 5 })
    pendingTransferCount.value = resp?.data?.count ?? 0
    pendingTransferRecords.value = resp?.data?.records ?? []
  } catch (e) {
    // 首页提醒不阻断主流程
  }
}

// 手机端登记上来的待受理问题
const pendingTicketCount = ref(0)
// 播报明细：哪个科室的哪类问题，由 pending-count 顺带下发，没有就退回笼统播报
const pendingTicketBriefs = ref([])
const voiceAlertOn = ref(isVoiceAlertEnabled())
let lastPendingTicketCount = null
let speechUnlocked = false
let lastSpokenAt = 0
let unbindSpeechKeepAlive = null
let voiceRepeatTimer = null

function speakPendingNow() {
  const n = pendingTicketCount.value
  if (!voiceAlertOn.value || n <= 0) return false
  lastSpokenAt = Date.now()
  return speakTicketAlert(announceText(n, pendingTicketBriefs.value))
}

function speakPendingIfDue() {
  if (!voiceAlertOn.value || pendingTicketCount.value <= 0) return
  if (lastSpokenAt && Date.now() - lastSpokenAt < REPEAT_MS) return
  speakPendingNow()
}

async function loadPendingTickets() {
  try {
    const resp = await ticketPendingCount()
    const n = resp?.data?.deptPending ?? 0
    const prev = lastPendingTicketCount
    lastPendingTicketCount = n
    pendingTicketCount.value = n
    pendingTicketBriefs.value = Array.isArray(resp?.data?.pendingBriefs) ? resp.data.pendingBriefs : []
    if (n <= 0) {
      lastSpokenAt = 0
      return
    }
    if (voiceAlertOn.value && shouldAnnounceIncrease(prev, n)) {
      speakPendingNow()
    }
  } catch (e) {
    // 账号没绑科室时后端会直接拒绝，静默处理
  }
}

function unlockSpeechOnce(e) {
  if (speechUnlocked) return
  if (e?.target?.closest?.('[data-voice-toggle]')) return
  speechUnlocked = true
  if (!speakPendingNow()) {
    unlockSpeech()
  }
}

function toggleVoiceAlert() {
  voiceAlertOn.value = !voiceAlertOn.value
  setVoiceAlertEnabled(voiceAlertOn.value)
  if (voiceAlertOn.value) {
    speechUnlocked = true
    if (!speakPendingNow()) {
      speakTicketAlert('语音提醒已开启，有待接收任务时每分钟播报')
    }
  } else {
    stopTicketAlert()
    ElMessage.info('已关闭语音提醒')
  }
}

function refreshNotices() {
  loadPendingTransfers()
  loadPendingTickets()
}

async function acceptTransfer(row) {
  try {
    await workRecordTransferAccept(row.id)
    ElMessage.success('已接受')
    await loadPendingTransfers()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

async function rejectTransfer(row) {
  try {
    const res = await ElMessageBox.prompt('请输入拒绝理由（可选）', '拒绝转移', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: ''
    })
    await workRecordTransferReject(row.id, { reply: res.value })
    ElMessage.success('已拒绝')
    await loadPendingTransfers()
  } catch (e) {
    if (e && typeof e === 'object' && 'message' in e) {
      const msg = e?.message
      if (msg) {
        ElMessage.error(msg)
      }
    }
  }
}

function logout() {
  stopTicketAlert()
  localStorage.removeItem('access_token')
  // 清掉共享档案，否则换账号登录时可能短暂沿用上一个用户的角色菜单
  clearProfile()
  router.replace('/login')
}

// 被路由守卫弹回来的（直接敲 URL 进了无权页面）：提示一次后把 denied 参数清掉。
// 已经在首页时 HomeView 不会重新挂载，只靠 onMounted 会漏提示，所以再加一个 watch。
function notifyDenied() {
  if (typeof route.query.denied !== 'string') return
  ElMessage.warning('当前角色无权访问该页面')
  router.replace({ path: route.path })
}

watch(() => route.query.denied, notifyDenied)

onMounted(async () => {
  await loadMe()
  notifyDenied()
  await loadPendingTransfers()
  await loadPendingTickets()
  transferPollTimer = setInterval(refreshNotices, 30000)
  voiceRepeatTimer = setInterval(speakPendingIfDue, REPEAT_MS)
  unbindSpeechKeepAlive = bindSpeechKeepAlive()
  window.addEventListener('pointerdown', unlockSpeechOnce, { once: true })
})

onUnmounted(() => {
  stopTicketAlert()
  window.removeEventListener('pointerdown', unlockSpeechOnce)
  if (unbindSpeechKeepAlive) {
    unbindSpeechKeepAlive()
    unbindSpeechKeepAlive = null
  }
  if (voiceRepeatTimer) {
    clearInterval(voiceRepeatTimer)
    voiceRepeatTimer = null
  }
  if (transferPollTimer) {
    clearInterval(transferPollTimer)
    transferPollTimer = null
  }
})
</script>

<template>
  <div class="layout">
    <!-- 顶部栏：极简，只放用户信息和通知 -->
    <header class="topbar">
      <div class="topLeft">
        <div class="brandMark">W</div>
        <div class="brandName">Worklog</div>
      </div>

      <div class="topRight">
        <el-badge :value="pendingTicketCount" :hidden="pendingTicketCount === 0" class="noticeBadge">
          <el-button class="iconBtn" :icon="Tickets" circle title="待受理的问题登记" @click="switchTab('/tickets')" />
        </el-badge>
        <el-button
          data-voice-toggle="1"
          class="iconBtn"
          :class="{ voiceOn: voiceAlertOn }"
          circle
          :title="voiceAlertOn ? '关闭语音提醒' : '开启语音提醒'"
          @click="toggleVoiceAlert"
        >
          <el-icon><component :is="voiceAlertOn ? Microphone : Mute" /></el-icon>
        </el-button>

        <el-popover v-model:visible="transferPopoverVisible" placement="bottom-end" :width="360" trigger="click">
          <template #reference>
            <el-badge :value="pendingTransferCount" :hidden="pendingTransferCount === 0" class="noticeBadge">
              <el-button class="iconBtn" :icon="Bell" circle />
            </el-badge>
          </template>

          <div class="transferPanel">
            <div class="panelTitle">任务转移</div>
            <div v-if="pendingTransferRecords.length === 0" class="empty">暂无待处理</div>
            <div v-else class="list">
              <div v-for="row in pendingTransferRecords" :key="row.id" class="item">
                <div class="metaLine">
                  <div class="id">#{{ row.id }}</div>
                  <div class="time">{{ row.createTime || '' }}</div>
                </div>
                <div class="desc">{{ row.reason || '（无原因）' }}</div>
                <div class="actions">
                  <el-button size="small" type="primary" @click="acceptTransfer(row)">接受</el-button>
                  <el-button size="small" @click="rejectTransfer(row)">拒绝</el-button>
                </div>
              </div>
            </div>
          </div>
        </el-popover>

        <div class="status" v-if="loading">加载中…</div>
        <div class="status error" v-else-if="errorMsg">{{ errorMsg }}</div>
        <div class="userChip" v-else>
          <span class="avatar">{{ displayName[0] }}</span>
          <span class="name">{{ displayName }}</span>
          <!-- 角色标签：多人共用一套系统，看不出自己的身份最容易误判「功能坏了」 -->
          <span class="roleTag" :class="'role-' + (user && user.role ? user.role : 'USER')">{{ roleLabel }}</span>
        </div>
        <el-button class="logoutBtn" text @click="logout">退出</el-button>
      </div>
    </header>

    <div class="body" :class="{ collapsed: isSiderCollapsed }">
      <!-- 侧边栏 -->
      <aside class="sider" :class="{ collapsed: isSiderCollapsed }">
        <button class="siderToggle" type="button" @click="toggleSider">
          <el-icon :size="16"><component :is="isSiderCollapsed ? Expand : Fold" /></el-icon>
        </button>

        <el-menu
          :default-active="route.path"
          class="menu"
          :collapse="isSiderCollapsed"
          :collapse-transition="false"
          :router="false"
          background-color="transparent"
          text-color="#737373"
          active-text-color="#171717"
          @select="(path) => switchTab(path)"
        >
          <template v-for="item in visibleMenuItems" :key="item.path || item.label">
            <el-sub-menu v-if="item.children" :index="item.label">
              <template #title>
                <el-icon v-if="item.icon" class="menuIcon"><component :is="item.icon" /></el-icon>
                <span class="menuLabel">{{ item.label }}</span>
              </template>
              <el-menu-item v-for="child in item.children" :key="child.path" :index="child.path">
                <template #title>
                  <el-icon v-if="child.icon" class="menuIcon"><component :is="child.icon" /></el-icon>
                  <span class="menuLabel">{{ child.label }}</span>
                </template>
              </el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="item.path">
              <el-icon class="menuIcon"><component :is="item.icon" /></el-icon>
              <span class="menuLabel">{{ item.label }}</span>
            </el-menu-item>
          </template>
        </el-menu>

        <div class="siderFooter" v-show="!isSiderCollapsed">
          <div class="footDivider" />
          <div class="footUser">
            <span class="footAvatar">{{ displayName[0] }}</span>
            <div class="footMeta">
              <span class="footName">{{ displayName }}</span>
              <span class="footRole" :class="'role-' + (user && user.role ? user.role : 'USER')">{{ roleLabel }}</span>
            </div>
          </div>
        </div>
      </aside>

      <!-- 主内容区 -->
      <main class="main">
        <!-- Tab 标签栏 -->
        <div class="tabBar">
          <div class="tabs">
            <div
              v-for="tab in tabs"
              :key="tab.path"
              class="tab"
              :class="{ active: activeTab === tab.path }"
              @click="switchTab(tab.path)"
            >
              <span class="tabLabel">{{ tab.label }}</span>
              <span v-if="tab.closable" class="tabClose" @click="closeTab(tab.path, $event)">×</span>
            </div>
          </div>
        </div>

        <div class="mainContainer">
          <RouterView v-slot="{ Component }">
            <component :is="Component" :user="user" :loading="loading" :errorMsg="errorMsg" />
          </RouterView>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* ========================================
   顶部栏
   ======================================== */
.topbar {
  height: var(--g-topbar-height, 48px);
  flex-shrink: 0;
  background: var(--g-bg);
  border-bottom: 1px solid var(--g-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  z-index: 100;
}

.topLeft {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brandMark {
  width: 28px;
  height: 28px;
  background: var(--g-text);
  color: #fff;
  border-radius: 6px;
  display: grid;
  place-items: center;
  font-weight: 800;
  font-size: 14px;
  letter-spacing: -0.03em;
}

.brandName {
  font-size: 16px;
  font-weight: 800;
  color: var(--g-text);
  letter-spacing: -0.03em;
}

.topRight {
  display: flex;
  align-items: center;
  gap: 8px;
}

.iconBtn {
  border: 1px solid var(--g-border) !important;
  background: var(--g-bg) !important;
  color: var(--g-text-muted) !important;
  width: 32px !important;
  height: 32px !important;
}

.iconBtn:hover {
  background: var(--g-bg-muted) !important;
  color: var(--g-text) !important;
}

.iconBtn.voiceOn {
  background: var(--g-text) !important;
  color: #fff !important;
  border-color: var(--g-text) !important;
}

.noticeBadge :deep(.el-badge__content) {
  background: var(--g-danger) !important;
  font-size: 10px;
  font-weight: 700;
}

.userChip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 4px 4px;
  border-radius: var(--g-radius);
}

.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--g-bg-muted);
  color: var(--g-text-secondary);
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 700;
}

.name {
  font-size: 13px;
  font-weight: 600;
  color: var(--g-text);
}

.status {
  font-size: 12px;
  color: var(--g-text-muted);
}

.status.error {
  color: var(--g-danger);
}

.logoutBtn {
  font-size: 13px;
  color: var(--g-text-muted) !important;
  font-weight: 500;
}

.logoutBtn:hover {
  color: var(--g-text) !important;
}

/* 角色标签：三级用三种强度，ADMIN 最重，一眼分辨当前身份 */
.roleTag {
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  padding: 0 7px;
  border-radius: 999px;
  border: 1px solid var(--g-border);
  background: var(--g-bg-muted);
  color: var(--g-text-muted);
  white-space: nowrap;
}

.roleTag.role-DEPT_ADMIN {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.roleTag.role-ADMIN {
  border-color: var(--g-text);
  background: var(--g-text);
  color: #fff;
}

/* ========================================
   主体：侧栏 + 内容
   ======================================== */
.body {
  flex: 1;
  display: grid;
  grid-template-columns: 220px 1fr;
  min-height: 0;
  transition: grid-template-columns 0.2s ease;
}

.body.collapsed {
  grid-template-columns: 56px 1fr;
}

/* ========================================
   侧边栏
   ======================================== */
.sider {
  background: var(--g-sidebar-bg, #FAFAFA);
  border-right: 1px solid var(--g-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
  transition: width 0.2s ease;
}

.siderToggle {
  position: absolute;
  top: 12px;
  right: -12px;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--g-bg);
  border: 1px solid var(--g-border);
  display: grid;
  place-items: center;
  cursor: pointer;
  z-index: 10;
  color: var(--g-text-muted);
  box-shadow: var(--g-shadow-sm);
  transition: all 0.15s;
}

.siderToggle:hover {
  background: var(--g-bg-muted);
  color: var(--g-text);
}

/* 菜单样式覆盖 */
.menu {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  border-right: none !important;
  padding: 8px 0;
}

.menu :deep(.el-menu-item) {
  height: 38px;
  line-height: 38px;
  margin: 1px 8px;
  border-radius: var(--g-radius);
  font-size: 13.5px;
  font-weight: 500;
  color: var(--g-text-muted) !important;
  border-left: 2px solid transparent;
  transition: all 0.12s ease;
}

.menu :deep(.el-menu-item:hover) {
  background-color: var(--g-bg-muted) !important;
  color: var(--g-text-secondary) !important;
}

.menu :deep(.el-menu-item.is-active) {
  background-color: var(--g-bg-muted) !important;
  color: var(--g-text) !important;
  font-weight: 600;
  border-left-color: var(--g-text);
}

.menu :deep(.el-menu-item.is-active .el-icon) {
  color: var(--g-text) !important;
}

.menu :deep(.el-menu-item.is-active span) {
  color: var(--g-text) !important;
}

.menu :deep(.el-sub-menu__title) {
  height: 38px;
  line-height: 38px;
  margin: 1px 8px;
  border-radius: var(--g-radius);
  font-size: 13.5px;
  font-weight: 500;
  color: var(--g-text-muted) !important;
}

.menu :deep(.el-sub-menu__title:hover) {
  background-color: var(--g-bg-muted) !important;
  color: var(--g-text-secondary) !important;
}

.menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: var(--g-text) !important;
  font-weight: 600;
}

.menu :deep(.el-sub-menu__arrow) {
  color: var(--g-text-faint);
}

.menu :deep(.el-menu--inline) {
  background-color: transparent !important;
}

.menu :deep(.el-sub-menu__content) {
  background-color: transparent !important;
}

.menu :deep(.el-sub-menu .el-menu) {
  background-color: transparent !important;
}

.menu :deep(.el-menu--popup) {
  background: var(--g-bg) !important;
  border: 1px solid var(--g-border) !important;
  box-shadow: var(--g-shadow-md) !important;
}

.menu :deep(.el-menu--popup .el-menu-item) {
  color: var(--g-text-secondary) !important;
  background-color: transparent !important;
  min-width: 160px;
}

.menu :deep(.el-menu--popup .el-menu-item:hover) {
  background-color: var(--g-bg-muted) !important;
  color: var(--g-text) !important;
}

.menu :deep(.el-menu--popup .el-menu-item.is-active) {
  background-color: var(--g-bg-muted) !important;
  color: var(--g-text) !important;
  font-weight: 600;
}

.menu :deep(.el-menu--inline .el-menu-item) {
  padding-left: 48px !important;
  color: var(--g-text-muted) !important;
}

.menu :deep(.el-menu--inline .el-menu-item:hover) {
  background-color: var(--g-bg-muted) !important;
  color: var(--g-text-secondary) !important;
}

.menu :deep(.el-menu--inline .el-menu-item.is-active) {
  background-color: var(--g-bg-muted) !important;
  border-left-color: var(--g-text);
  color: var(--g-text) !important;
  font-weight: 600;
}

.menuIcon {
  font-size: 16px;
  margin-right: 8px;
  color: var(--g-text-faint);
  transition: color 0.15s;
}

.menu :deep(.el-menu-item:hover) .menuIcon,
.menu :deep(.el-menu-item.is-active) .menuIcon {
  color: var(--g-text);
}

.menuLabel {
  display: inline-block;
  opacity: 1;
  transition: opacity 0.15s ease;
}

.sider.collapsed .menuLabel {
  opacity: 0;
}

.siderFooter {
  padding: 12px 16px;
  margin-top: auto;
}

.footDivider {
  height: 1px;
  background: var(--g-border);
  margin-bottom: 12px;
}

.footUser {
  display: flex;
  align-items: center;
  gap: 8px;
}

.footAvatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--g-bg-muted);
  color: var(--g-text-muted);
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
}

.footMeta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.footName {
  font-size: 12px;
  color: var(--g-text-muted);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.footRole {
  font-size: 11px;
  font-weight: 600;
  color: var(--g-text-faint);
  white-space: nowrap;
}

.footRole.role-DEPT_ADMIN {
  color: #1d4ed8;
}

.footRole.role-ADMIN {
  color: var(--g-text);
}

/* ========================================
   主内容区
   ======================================== */
.main {
  overflow: hidden;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--g-bg);
}

/* Tab 栏 */
.tabBar {
  height: 40px;
  flex-shrink: 0;
  background: var(--g-bg-subtle);
  border-bottom: 1px solid var(--g-border);
  display: flex;
  align-items: center;
  padding: 0 12px;
}

.tabs {
  display: flex;
  gap: 2px;
  overflow-x: auto;
  flex: 1;
}

.tabs::-webkit-scrollbar {
  display: none;
}

.tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: var(--g-radius-sm);
  cursor: pointer;
  font-size: 13px;
  color: var(--g-text-muted);
  white-space: nowrap;
  transition: all 0.12s ease;
  font-weight: 500;
}

.tab:hover {
  background: var(--g-bg-muted);
  color: var(--g-text-secondary);
}

.tab.active {
  background: var(--g-bg);
  color: var(--g-text);
  font-weight: 600;
  box-shadow: var(--g-shadow-sm);
}

.tabClose {
  font-size: 14px;
  line-height: 1;
  padding: 0 2px;
  border-radius: 3px;
  color: var(--g-text-faint);
  transition: all 0.12s;
}

.tabClose:hover {
  background: var(--g-border);
  color: var(--g-text);
}

.mainContainer {
  flex: 1;
  overflow: auto;
  padding: 20px;
  width: 100%;
  max-width: 100%;
}

/* ========================================
   转移弹层
   ======================================== */
.transferPanel {
  padding: 4px 0;
}

.panelTitle {
  font-size: 14px;
  font-weight: 700;
  color: var(--g-text);
  margin-bottom: 12px;
}

.transferPanel .empty {
  padding: 20px 0;
  text-align: center;
  color: var(--g-text-faint);
  font-size: 13px;
}

.transferPanel .list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.transferPanel .item {
  padding: 10px 12px;
  background: var(--g-bg-subtle);
  border-radius: var(--g-radius);
  border: 1px solid var(--g-border);
}

.transferPanel .metaLine {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
}

.transferPanel .id {
  font-family: var(--g-font-mono);
  font-size: 12px;
  font-weight: 600;
  color: var(--g-text);
}

.transferPanel .time {
  font-size: 11px;
  color: var(--g-text-faint);
}

.transferPanel .desc {
  font-size: 13px;
  color: var(--g-text-secondary);
  margin-bottom: 8px;
}

.transferPanel .actions {
  display: flex;
  gap: 8px;
}

/* ========================================
   响应式
   ======================================== */
@media (max-width: 900px) {
  .body {
    grid-template-columns: 1fr;
  }
  .sider {
    display: none;
  }
}
</style>
