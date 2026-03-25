<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter, RouterView } from 'vue-router'
import { HomeFilled, Document, OfficeBuilding, UserFilled, TrendCharts, CollectionTag, FolderOpened, Bell, Setting, DocumentCopy, PieChart, EditPen, Fold, Expand } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { me } from '../api/auth'
import { workRecordTransferAccept, workRecordTransferPending, workRecordTransferReject } from '../api/work'

const router = useRouter()
const route = useRoute()

const loading = ref(true)
const errorMsg = ref('')
const user = ref(null)

const displayName = computed(() => user.value?.realName || user.value?.username || '用户')
const siderCollapsed = ref(false)

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

    <div class="body" :class="{ collapsed: siderCollapsed }">
      <aside class="sider" :class="{ collapsed: siderCollapsed }">
        <button class="siderFloatToggle" type="button" @click="siderCollapsed = !siderCollapsed">
          <el-icon><component :is="siderCollapsed ? Expand : Fold" /></el-icon>
        </button>

        <div class="siderHeader" v-show="!siderCollapsed">
          <div class="siderTitle">功能导航</div>
        </div>

        <el-menu
          :default-active="route.path"
          class="menu"
          router
          :collapse="siderCollapsed"
          :collapse-transition="true"
          background-color="transparent"
          text-color="rgba(255, 255, 255, 0.88)"
          active-text-color="#fff"
        >
          <template v-for="item in menuItems" :key="item.path || item.label">
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

        <div class="siderFooter">
          <div class="footLine" />
          <div class="footText">登录用户：{{ displayName }}</div>
        </div>
      </aside>

      <main class="main">
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
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.siderToggleBtn {
  height: 34px;
  border-radius: 10px;
  border: 1px solid #d7deea;
  background: linear-gradient(180deg, #ffffff, #f6f8fc);
  color: #334155;
  padding: 0 12px;
  font-weight: 600;
  transition: all 0.2s ease;
}

.siderToggleBtn:hover {
  border-color: #93c5fd;
  color: #1d4ed8;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.12);
  transform: translateY(-1px);
}

.siderToggleBtn:active {
  transform: translateY(0);
}

.siderToggleIcon {
  margin-right: 6px;
  font-size: 14px;
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
  grid-template-columns: 240px 1fr;
  transition: grid-template-columns 0.25s ease;
}

.body.collapsed {
  grid-template-columns: 72px 1fr;
}

.sider {
  position: relative;
  display: flex;
  flex-direction: column;
  border-right: 1px solid rgba(147, 197, 253, 0.18);
  background:
    radial-gradient(140% 120% at 0% 0%, rgba(59, 130, 246, 0.24) 0%, rgba(59, 130, 246, 0) 48%),
    radial-gradient(110% 80% at 100% 100%, rgba(125, 211, 252, 0.18) 0%, rgba(125, 211, 252, 0) 52%),
    linear-gradient(180deg, rgba(15, 23, 42, 0.94), rgba(30, 41, 59, 0.92));
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  box-shadow: inset -1px 0 0 rgba(148, 163, 184, 0.15), 8px 0 30px rgba(15, 23, 42, 0.25);
  padding: 14px 0;
  overflow-y: auto;
  overflow-x: visible;
  color: rgba(255, 255, 255, 0.90);
  transition: width 0.25s ease, box-shadow 0.25s ease;
}

.sider.collapsed {
  padding-top: 20px;
}

.siderFloatToggle {
  position: absolute;
  top: 18px;
  right: -14px;
  width: 28px;
  height: 28px;
  border: 1px solid #d7e9ff;
  border-radius: 999px;
  background: linear-gradient(180deg, #ffffff, #eef6ff);
  color: #2563eb;
  box-shadow: 0 6px 18px rgba(37, 99, 235, 0.2);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s ease;
  z-index: 10;
}

.siderFloatToggle:hover {
  border-color: #93c5fd;
  background: linear-gradient(180deg, #f8fbff, #e8f2ff);
  transform: scale(1.06);
}

.sider.collapsed .siderFloatToggle {
  right: -14px;
  transform: none;
}

.sider.collapsed .siderFloatToggle:hover {
  transform: scale(1.06);
}

.siderHeader {
  padding: 8px 24px 12px;
}

.siderTitle {
  font-weight: 900;
}

.menu {
  flex: 1;
  border-right: none;
}

.menu :deep(.el-sub-menu__title:hover),
.menu :deep(.el-menu-item:hover) {
  background-color: rgba(186, 230, 253, 0.12) !important;
}

.menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(90deg, rgba(56, 189, 248, 0.24), rgba(14, 165, 233, 0.08)) !important;
  border-right: 3px solid #7dd3fc;
  color: #ffffff !important;
  font-weight: 800;
}

.menu :deep(.el-menu-item.is-active .el-icon),
.menu :deep(.el-menu-item.is-active span) {
  color: #ffffff !important;
}

.menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #ffffff !important;
}

.menu :deep(.el-sub-menu.is-active > .el-sub-menu__title .el-icon),
.menu :deep(.el-sub-menu.is-active > .el-sub-menu__title span) {
  color: #ffffff !important;
}

.menuIcon {
  font-size: 18px;
  margin-right: 10px;
  transition: transform 0.2s ease;
}

.menuLabel {
  display: inline-block;
  opacity: 1;
  transform: translateX(0);
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.sider.collapsed .menuLabel {
  opacity: 0;
  transform: translateX(-6px);
}

.sider:not(.collapsed) .menuLabel {
  animation: menuLabelFadeIn 0.22s ease;
}

@keyframes menuLabelFadeIn {
  from {
    opacity: 0;
    transform: translateX(-8px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

.siderFooter {
  margin-top: 14px;
  padding: 10px 24px 0;
}

.sider.collapsed .siderFooter {
  display: none;
}

.footLine {
  height: 1px;
  background: rgba(255, 255, 255, 0.10);
  margin-bottom: 10px;
}

.footText {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.60);
}

.main {
  overflow: auto;
  min-width: 0;
}

.main-container {
  width: 100%;
  max-width: none;
  margin: 0;
  padding: 6px 10px;
  box-sizing: border-box;
}

@media (max-width: 900px) {
  .body {
    grid-template-columns: 1fr;
  }

  .sider {
    border-right: 0;
    border-bottom: 1px solid #e9edf5;
  }
}

:global(.el-menu--popup) {
  background: linear-gradient(180deg, #0f172a, #1e293b) !important;
  border: 1px solid rgba(148, 163, 184, 0.22) !important;
  box-shadow: 0 14px 36px rgba(2, 6, 23, 0.42) !important;
}

:global(.el-menu--popup .el-menu-item),
:global(.el-menu--popup .el-sub-menu__title) {
  color: rgba(255, 255, 255, 0.9) !important;
}

:global(.el-menu--popup .el-menu-item:hover),
:global(.el-menu--popup .el-sub-menu__title:hover) {
  background: rgba(125, 211, 252, 0.16) !important;
  color: #ffffff !important;
}

:global(.el-menu--popup .el-menu-item.is-active) {
  background: linear-gradient(90deg, rgba(59, 130, 246, 0.35), rgba(56, 189, 248, 0.16)) !important;
  color: #ffffff !important;
}

</style>
