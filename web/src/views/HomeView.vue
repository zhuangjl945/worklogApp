<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter, RouterView } from 'vue-router'
import { HomeFilled, Document, OfficeBuilding, UserFilled, TrendCharts, Tickets, CollectionTag, FolderOpened, Bell, Setting, DocumentCopy, PieChart, EditPen } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { me } from '../api/auth'
import { workRecordTransferAccept, workRecordTransferPending, workRecordTransferReject } from '../api/work'

const router = useRouter()
const route = useRoute()

const loading = ref(true)
const errorMsg = ref('')
const user = ref(null)

const displayName = computed(() => user.value?.realName || user.value?.username || '用户')

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

    <div class="body">
      <aside class="sider">
        <div class="siderHeader">
          <div class="siderTitle">功能导航</div>
        </div>

        <el-menu
          :default-active="route.path"
          class="menu"
          router
          background-color="transparent"
          text-color="rgba(255, 255, 255, 0.88)"
          active-text-color="#fff"
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
  align-items: baseline;
  gap: 10px;
  min-width: 0;
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
}

.sider {
  display: flex;
  flex-direction: column;
  border-right: 1px solid rgba(17, 24, 39, 0.25);
  background: linear-gradient(180deg, #1f2937, #334155);
  padding: 14px 0;
  overflow-y: auto;
  overflow-x: hidden;
  color: rgba(255, 255, 255, 0.90);
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
  background-color: rgba(255, 255, 255, 0.07) !important;
}

.menu :deep(.el-menu-item.is-active) {
  background-color: rgba(59, 130, 246, 0.18) !important;
  border-right: 3px solid #60a5fa;
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
}

.siderFooter {
  margin-top: 14px;
  padding: 10px 24px 0;
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
  max-width: 1600px;
  margin: 0 auto;
  padding: 5px;
  width: 100%;
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
</style>
