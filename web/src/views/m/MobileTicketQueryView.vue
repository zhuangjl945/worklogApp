<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchTicketDetail } from '../../api/ticketPublic'
import { forgetTicket, listTickets, saveTicket } from '../../utils/ticketVault'
import './mobile.css'

const router = useRouter()
const tickets = ref([])
const ticketNo = ref('')
const accessToken = ref('')
const asking = ref(false)
const errorMsg = ref('')
const checking = ref(false)

onMounted(() => {
  tickets.value = listTickets()
})

async function openSaved(item) {
  router.push(`/m/ticket/${item.ticketNo}?auth=${encodeURIComponent(item.accessToken)}`)
}

function removeSaved(item) {
  forgetTicket(item.ticketNo)
  tickets.value = listTickets()
}

async function lookup() {
  errorMsg.value = ''
  const no = ticketNo.value.trim().toUpperCase()
  // 新单号是 ST100001 这种短流水，老单号是 ST20260908000123：两种形状都放过去，
  // 长度下限卡到 8 只是为了不让「只输了个 ST」这种半成品打到后端去消耗失败配额
  if (!/^ST\d{6,14}$/.test(no)) {
    errorMsg.value = '请输入完整单号，例如 ST100001'
    return
  }
  // 本机存过这条工单就不用再输密码
  const local = listTickets().find((t) => t.ticketNo === no)
  if (local && !accessToken.value) {
    router.push(`/m/ticket/${no}?auth=${encodeURIComponent(local.accessToken)}`)
    return
  }
  if (!accessToken.value) {
    asking.value = true
    errorMsg.value = '请输入查询密码（提交时你自己设的那 6 位数字）'
    return
  }
  checking.value = true
  try {
    await fetchTicketDetail(no, accessToken.value.trim())
    saveTicket(no, accessToken.value.trim(), '')
    router.push(`/m/ticket/${no}?auth=${encodeURIComponent(accessToken.value.trim())}`)
  } catch (e) {
    errorMsg.value = e?.message || '单号或查询密码不正确'
  } finally {
    checking.value = false
  }
}
</script>

<template>
  <div class="m-shell">
    <header class="m-topbar">
      <h1>查询进度</h1>
      <RouterLink class="m-link" to="/">重新登记</RouterLink>
    </header>

    <main class="m-body">
      <!-- 查询表单卡片 -->
      <div class="m-card">
        <div class="m-field">
          <label class="m-label" for="q-no">工单号</label>
          <input id="q-no" class="m-input" v-model="ticketNo" maxlength="24" placeholder="ST100001"
                 autocapitalize="characters" autocomplete="off"
                 style="font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; letter-spacing: .02em">
        </div>
        <div v-if="asking" class="m-field">
          <label class="m-label" for="q-auth">查询密码</label>
          <input id="q-auth" class="m-input" v-model="accessToken" maxlength="64" placeholder="6 位数字，如 472815"
                 autocomplete="off">
          <div class="m-hint">
            就是提交时你自己设的那 6 位数字。输错太多次这个单号会被临时锁住（默认 15 分钟），
            以免别人猜你的号；实在想不起来就找受理科室按单号代查。
          </div>
        </div>
        <div v-if="errorMsg" class="m-error">{{ errorMsg }}</div>
        <button class="m-btn" type="button" :disabled="checking" @click="lookup">
          {{ checking ? '查询中…' : '查询' }}
        </button>
        <div class="m-hint" style="margin-top: 10px">
          单号可以抄在纸上，查询密码别写在一起——现在是「谁猜到号都能试」，全靠这 6 位数字挡住别人。
          老工单（单号形如 ST20260908000123）仍填当初那串长字符。
        </div>
      </div>

      <!-- 本机记录列表 -->
      <div v-if="tickets.length" class="m-card">
        <span class="m-section-title">本机记录</span>
        <div v-for="item in tickets" :key="item.ticketNo" class="m-saved-item">
          <button class="m-saved-item-main" type="button" @click="openSaved(item)">
            <span class="m-saved-item-no">{{ item.ticketNo }}</span>
            <span v-if="item.title" class="m-saved-item-title">{{ item.title }}</span>
          </button>
          <button class="m-saved-item-del" type="button" @click="removeSaved(item)">删除</button>
        </div>
        <div class="m-hint" style="margin-top: 12px">这里只是手机上的快捷入口，删除不影响科室里的登记记录。</div>
      </div>

      <!-- 没有本机记录时的友好提示 -->
      <div v-else class="m-center" style="padding: 32px 24px">
        <div class="m-center-ic">📋</div>
        <div>本机暂无工单记录</div>
        <p>提交过报修后，会自动出现在这里方便快速查看。</p>
      </div>
    </main>
  </div>
</template>
