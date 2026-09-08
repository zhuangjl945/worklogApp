import { createRouter, createWebHistory } from 'vue-router'

import LoginView from '../views/LoginView.vue'
import HomeView from '../views/HomeView.vue'
import DashboardView from '../views/DashboardView.vue'
import DeptView from '../views/DeptView.vue'
import UsersView from '../views/UsersView.vue'
import WorkRecordsView from '../views/WorkRecordsView.vue'
import WorkloadCategoryReportView from '../views/WorkloadCategoryReportView.vue'
import WorkCategoryView from '../views/WorkCategoryView.vue'

import SupplierListView from '../views/SupplierListView.vue'
import SupplierEditView from '../views/SupplierEditView.vue'
import ContractListView from '../views/ContractListView.vue'
import ContractEditView from '../views/ContractEditView.vue'
import ContractDetailView from '../views/ContractDetailView.vue'
import ContractPaymentView from '../views/ContractPaymentView.vue'

// 手机端页面单独分包：报修人不该为了填一张表单下载管理端的组件库
const MobileTicketFormView = () => import('../views/m/MobileTicketFormView.vue')
const MobileTicketQueryView = () => import('../views/m/MobileTicketQueryView.vue')
const MobileTicketDetailView = () => import('../views/m/MobileTicketDetailView.vue')
// 管理端工单页同样按需加载：渠道维护页内联了二维码编码器，不该让首屏主包为它买单
const TicketInboxView = () => import('../views/TicketInboxView.vue')
const TicketDetailView = () => import('../views/TicketDetailView.vue')
const TicketChannelView = () => import('../views/TicketChannelView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/login', meta: { public: true } },
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    {
      path: '/',
      component: HomeView,
      children: [
        { path: 'home', name: 'home', component: DashboardView },
        { path: 'work-records', name: 'work-records', component: WorkRecordsView },
        // --- 手机端登记上来的问题：受理台 + 处理详情 ---
        { path: 'tickets', name: 'tickets', component: TicketInboxView },
        // 详情页不在侧边菜单里，标签名靠 meta.label 提供，否则顶部 Tab 会显示成一串路径
        { path: 'tickets/:id', name: 'ticket-detail', component: TicketDetailView, meta: { label: '工单处理' } },

        // --- supplier ---
        { path: 'supplier/list', name: 'supplier-list', component: SupplierListView },
        { path: 'supplier/edit', name: 'supplier-create', component: SupplierEditView },
        { path: 'supplier/edit/:id', name: 'supplier-edit', component: SupplierEditView },

        // --- contract ---
        { path: 'contract/list', name: 'contract-list', component: ContractListView },
        { path: 'contract/edit', name: 'contract-create', component: ContractEditView },
        { path: 'contract/edit/:id', name: 'contract-edit', component: ContractEditView },
        { path: 'contract/detail/:id', name: 'contract-detail', component: ContractDetailView },
        { path: 'contract/payment/:contractId', name: 'contract-payment', component: ContractPaymentView },

        { path: 'depts', name: 'depts', component: DeptView },
        { path: 'users', name: 'users', component: UsersView },
        { path: 'work-categories', name: 'work-categories', component: WorkCategoryView },
        { path: 'ticket-channels', name: 'ticket-channels', component: TicketChannelView },
        {
          path: 'reports',
          children: [
            { path: 'workload-category', name: 'report-workload-category', component: WorkloadCategoryReportView }
          ]
        }
      ]
    },
    // --- 手机端问题登记（报修人免登录，必须 meta.public，否则守卫会把人踢到管理端登录页） ---
    // /m/query 要排在 /m/:channelCode 之前：静态段优先，但顺序写清楚比依赖路由排序更可靠
    { path: '/m/query', name: 'm-query', component: MobileTicketQueryView, meta: { public: true } },
    { path: '/m/ticket/:ticketNo', name: 'm-ticket', component: MobileTicketDetailView, meta: { public: true } },
    { path: '/m', redirect: '/m/query' },
    { path: '/m/:channelCode', name: 'm-form', component: MobileTicketFormView, meta: { public: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

router.beforeEach((to) => {
  if (to.meta?.public) return true
  const token = localStorage.getItem('access_token')
  if (!token) return { path: '/login', query: { redirect: to.fullPath } }
  return true
})

export default router
