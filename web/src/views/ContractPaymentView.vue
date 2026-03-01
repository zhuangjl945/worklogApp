<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Money } from '@element-plus/icons-vue'
import { contractPaymentPlans } from '../api/contract'

const route = useRoute()
const contractId = route.params.contractId

const loading = ref(false)
const plans = ref([])

async function load() {
  loading.value = true
  try {
    const resp = await contractPaymentPlans(contractId)
    plans.value = resp.data
  } catch (e) {
    ElMessage.error(e?.message || '加载付款计划失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Money /></el-icon>
        <div class="title">付款登记</div>
      </div>
      <div class="actions">
        <el-button @click="$router.back()">返回</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <el-card shadow="never" class="card">
      <el-table v-loading="loading" :data="plans" border stripe>
        <el-table-column prop="planNo" label="阶段" width="140" />
        <el-table-column prop="planAmount" label="计划金额" width="120" />
        <el-table-column prop="planDate" label="计划日期" width="120" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 20 ? 'success' : (row.status === 30 ? 'danger' : 'info')">
              {{ row.status === 20 ? '已付款' : (row.status === 30 ? '已逾期' : '待付款') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="actualAmount" label="实付金额" width="120" />
        <el-table-column prop="actualDate" label="实付日期" width="120" />
        <el-table-column prop="voucherNo" label="凭证号" min-width="160" />
        <el-table-column prop="payMethod" label="方式" width="120" />
        <el-table-column prop="remark" label="备注" min-width="160" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button size="small" type="primary" :disabled="row.status === 20" @click="$router.push(`/contract/detail/${row.contractId}`)">去登记</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page { padding: 18px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.titleWrap { display: flex; align-items: center; gap: 8px; }
.titleIcon { font-size: 22px; color: #3b82f6; }
.title { font-size: 18px; font-weight: 900; color: #111827; }
.actions { display: flex; align-items: center; gap: 10px; }
.card { border-radius: 14px; }
</style>
