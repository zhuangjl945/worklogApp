<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter, RouterView } from 'vue-router'
import { HomeFilled, Document, OfficeBuilding, UserFilled, TrendCharts, Tickets, CollectionTag, FolderOpened, Bell, Setting, DocumentCopy, PieChart, EditPen, Fold, Expand, Close } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { me } from '../api/auth'
import { workRecordTransferAccept, workRecordTransferPending, workRecordTransferReject } from '../api/work'

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
const menuItems = [
  { path: '/home', label: '首页', icon: HomeFilled },
  { path: '/work-records', label: '工作记录', icon: EditPen },
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
      { path: '/depts', label: '科室管理', icon: FolderOpened },
      { path: '/users', label: '员工管理', icon: UserFilled },
      { path: '/work-categories', label: '工作分类维护', icon: CollectionTag }
    ]
  }
]

// 扁平化菜单并构建映射
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
    const label = menuLabelMap[to.path] || to.path
    tabs.value.push({ path: to.path, label, closable: to.path !== '/home' })
  }
  activeTab.value = to.path
})

async function loadMe() {
  loading.value = true
  errorMsg.value = ''
  try {
    const resp = await me()
    user.value = resp.data
  } catch (e) {
    errorMsg.value = e?.message || '加载用户信息失败'
  } finally {
    loading.value = false
  }
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
    // 用户取消不提示
    if (e && typeof e === 'object' && 'message' in e) {
      const msg = e?.message
      if (msg) {
        ElMessage.error(msg)
      }
    }
  }
}

function logout() {
  localStorage.removeItem('access_token')
  router.replace('/login')
}

onMounted(async () => {
  await loadMe()
  await loadPendingTransfers()
  transferPollTimer = setInterval(loadPendingTransfers, 30000)
})

onUnmounted(() => {
  if (transferPollTimer) {
    clearInterval(transferPollTimer)
    transferPollTimer = null
  }
})
</script>

<template>
  <div class="layout">
    <header class="topbar">
      <div class="topLeft">
        <el-button text @click="toggleSider" class="collapseBtn">
          <el-icon size="20"><component :is="isSiderCollapsed ? Expand : Fold" /></el-icon>
        </el-button>
        <div class="brand">Worklog</div>
        <div class="subtitle">工作日志管理系统</div>
      </div>

      <div class="topRight">
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

        <div class="status" v-if="loading">加载用户信息中…</div>
        <div class="status error" v-else-if="errorMsg">{{ errorMsg }}</div>
        <div class="userChip" v-else>
          <span class="name">{{ displayName }}</span>
          <span class="meta">ID: {{ user?.id }}</span>
        </div>
        <el-button class="btn" @click="logout">退出登录</el-button>
      </div>
    </header>

    <div class="body">
      <aside class="sider" :class="{ collapsed: isSiderCollapsed }">
        <div class="siderHeader" :class="{ collapsed: isSiderCollapsed }">
          <div class="siderTitle">功能导航</div>
        </div>

        <el-menu
          :default-active="route.path"
          class="menu"
          :collapse="isSiderCollapsed"
          :collapse-transition="false"
          :router="false"
          background-color="transparent"
          text-color="rgba(255, 255, 255, 0.85)"
          active-text-color="#ffffff"
          @select="(path) => switchTab(path)"
        >
          <template v-for="item in menuItems" :key="item.path || item.label">
            <el-sub-menu v-if="item.children" :index="item.label">
              <template #title>
                <el-icon v-if="item.icon" class="menuIcon"><component :is="item.icon" /></el-icon>
                <span>{{ item.label }}</span>
              </template>
              <el-menu-item v-for="child in item.children" :key="child.path" :index="child.path">
                <template #title>
                  <el-icon v-if="child.icon" class="menuIcon"><component :is="child.icon" /></el-icon>
                  <span>{{ child.label }}</span>
                </template>
              </el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="item.path">
              <el-icon class="menuIcon"><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
            </el-menu-item>
          </template>
        </el-menu>

        <div class="siderFooter" :class="{ collapsed: isSiderCollapsed }">
          <div class="footLine" />
          <div class="footText" v-if="!isSiderCollapsed">登录用户：{{ displayName }}</div>
        </div>
      </aside>

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
              <el-icon v-if="tab.closable" class="tabClose" @click="closeTab(tab.path, $event)"><Close /></el-icon>
            </div>
          </div>
        </div>

        <div class="main-container">
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
  background: #f5f7fb;
  color: #334155;
}

.topbar {
  height: 56px;
  flex: 0 0 auto;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e9edf5;
  background: #ffffff;
}

.topLeft {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.collapseBtn {
  padding: 4px;
  margin-right: 4px;
}

.collapseBtn:hover {
  background: #f1f5f9;
}

.brand {
  font-weight: 900;
  color: #0f172a;
}

.subtitle {
  font-size: 12px;
  color: #64748b;
}

.topRight {
  display: flex;
  align-items: center;
  gap: 10px;
}

.noticeBadge {
  margin-right: 2px;
}

.iconBtn {
  height: 34px;
  width: 34px;
  border-radius: 10px;
  border: 1px solid #d0d5dd;
  background: #fff;
}

.iconBtn:hover {
  background: #f2f4f7;
}

.transferPanel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.panelTitle {
  font-weight: 900;
  color: #0f172a;
}

.empty {
  font-size: 12px;
  color: #64748b;
  padding: 6px 0;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.item {
  border: 1px solid #eef2f7;
  border-radius: 10px;
  padding: 10px;
  background: #fff;
}

.metaLine {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #64748b;
  margin-bottom: 6px;
}

.desc {
  font-size: 13px;
  color: #1e293b;
  margin-bottom: 8px;
  white-space: pre-wrap;
  word-break: break-word;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.status {
  font-size: 12px;
  color: #64748b;
}

.status.error {
  color: #ef4444;
}

.userChip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  border-radius: 10px;
  background: #f1f5f9;
  color: #334155;
  font-size: 12px;
}

.name {
  font-weight: 800;
}

.meta {
  color: #64748b;
}

.btn {
  height: 34px;
  border-radius: 10px;
  border: 1px solid #d0d5dd;
  padding: 0 12px;
  cursor: pointer;
  background: #fff;
}

.btn:hover {
  background: #f2f4f7;
}

.body {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: auto 1fr;
}

.sider {
  display: flex;
  flex-direction: column;
  border-right: 1px solid rgba(17, 24, 39, 0.25);
  background: linear-gradient(180deg, #1f2937, #334155);
  padding: 14px 0;
  overflow-y: auto;
  overflow-x: hidden;
  color: #ffffff;
  transition: width 0.3s ease;
  width: 200px;
}

.sider.collapsed {
  width: 64px;
}

.siderHeader {
  padding: 8px 24px 12px;
}

.siderHeader.collapsed {
  padding: 8px;
  text-align: center;
}

.siderTitle {
  font-weight: 900;
  color: #ffffff;
  transition: opacity 0.3s ease;
}

.sider.collapsed .siderTitle {
  opacity: 0;
  height: 0;
  overflow: hidden;
}

.menu {
  flex: 1;
  border-right: none;
}

.menu:not(.el-menu--collapse) {
  width: 200px;
}

.menu :deep(.el-menu-item),
.menu :deep(.el-sub-menu__title) {
  color: rgba(255, 255, 255, 0.85);
}

.menu :deep(.el-sub-menu__title:hover),
.menu :deep(.el-menu-item:hover) {
  background-color: rgba(255, 255, 255, 0.12) !important;
  color: #ffffff !important;
}

.menu :deep(.el-menu-item.is-active) {
  background-color: rgba(59, 130, 246, 0.25) !important;
  border-right: 3px solid #60a5fa;
  color: #ffffff !important;
  font-weight: 600;
}

.menu :deep(.el-menu-item.is-active .el-icon),
.menu :deep(.el-menu-item.is-active span) {
  color: #ffffff !important;
}

.menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #ffffff !important;
  background-color: rgba(255, 255, 255, 0.08);
}

.menu :deep(.el-sub-menu.is-active > .el-sub-menu__title .el-icon),
.menu :deep(.el-sub-menu.is-active > .el-sub-menu__title span) {
  color: #ffffff !important;
}

.menu :deep(.el-sub-menu__arrow) {
  color: rgba(255, 255, 255, 0.7);
}

.menu :deep(.el-menu--inline) {
  background-color: rgba(0, 0, 0, 0.4) !important;
}

.menu :deep(.el-menu--collapse .el-menu--inline) {
  background-color: rgba(0, 0, 0, 0.4) !important;
}

.menu :deep(.el-sub-menu__content) {
  background-color: rgba(0, 0, 0, 0.4) !important;
}

/* 弹出菜单样式（折叠状态下） */
.menu :deep(.el-sub-menu .el-menu) {
  background-color: rgba(0, 0, 0, 0.4) !important;
}

.menu :deep(.el-menu--popup) {
  background-color: rgba(0, 0, 0, 0.4) !important;
}

.menu :deep(.el-menu--popup .el-menu-item) {
  color: #ffffff !important;
  background-color: transparent !important;
  min-width: 160px;
}

.menu :deep(.el-menu--popup .el-menu-item:hover) {
  background-color: rgba(59, 130, 246, 0.25) !important;
  color: #ffffff !important;
}

.menu :deep(.el-menu--popup .el-menu-item.is-active) {
  background-color: rgba(59, 130, 246, 0.4) !important;
  color: #ffffff !important;
}

.menu :deep(.el-menu--inline .el-menu-item) {
  padding-left: 48px !important;
  color: #ffffff !important;
}

.menu :deep(.el-menu--inline .el-menu-item:hover) {
  background-color: rgba(59, 130, 246, 0.25) !important;
  color: #ffffff !important;
}

.menu :deep(.el-menu--inline .el-menu-item.is-active) {
  background-color: rgba(59, 130, 246, 0.4) !important;
  border-right: 3px solid #60a5fa;
  color: #ffffff !important;
  font-weight: 600;
}

.menuIcon {
  font-size: 18px;
  margin-right: 10px;
}

.siderFooter {
  margin-top: 14px;
  padding: 10px 24px 0;
  transition: all 0.3s ease;
}

.siderFooter.collapsed {
  padding: 10px 8px;
  text-align: center;
}

.footLine {
  height: 1px;
  background: rgba(255, 255, 255, 0.10);
  margin-bottom: 10px;
}

.footText {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.6);
  transition: opacity 0.3s ease;
  white-space: nowrap;
}

.sider.collapsed .footText {
  opacity: 0;
}

.main {
  overflow: hidden;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.tabBar {
  height: 44px;
  flex-shrink: 0;
  background: #ffffff;
  border-bottom: 1px solid #e9edf5;
  display: flex;
  align-items: center;
  padding: 0 16px;
}

.tabs {
  display: flex;
  gap: 4px;
  overflow-x: auto;
  flex: 1;
}

.tabs::-webkit-scrollbar {
  display: none;
}

.tab {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  background: #f5f7fb;
  border-radius: 8px 8px 0 0;
  cursor: pointer;
  font-size: 13px;
  color: #64748b;
  white-space: nowrap;
  transition: all 0.15s ease;
  border: 1px solid transparent;
  border-bottom: none;
}

.tab:hover {
  background: #eef2f7;
  color: #334155;
}

.tab.active {
  background: #f0f4ff;
  color: #1a73e8;
  font-weight: 500;
  border-color: #e0e7ff;
}

.tabClose {
  font-size: 12px;
  padding: 2px;
  border-radius: 4px;
  transition: all 0.15s ease;
}

.tabClose:hover {
  background: rgba(0, 0, 0, 0.1);
}

.main-container {
  flex: 1;
  overflow: auto;
  padding: 16px;
  width: 100%;
  max-width: 100%;
}

@media (max-width: 900px) {
  .body {
    grid-template-columns: 1fr;
  }

  .sider {
    display: none;
  }
}
</style>
