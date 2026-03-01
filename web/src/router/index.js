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
        {
          path: 'reports',
          children: [
            { path: 'workload-category', name: 'report-workload-category', component: WorkloadCategoryReportView }
          ]
        }
      ]
    },
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
