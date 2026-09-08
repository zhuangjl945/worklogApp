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
  if (no.length < 8) {
    errorMsg.value = '请输入完整单号，例如 ST20260908000123'
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
    errorMsg.value = '请输入查询密码（提交成功时那串，或当时截图保存的内容）'
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
      <div class="m-card">
        <div class="m-field">
          <label class="m-label" for="q-no">单号</label>
          <input id="q-no" class="m-input" v-model="ticketNo" maxlength="24" placeholder="ST20260908000123"
                 autocapitalize="characters" autocomplete="off">
        </div>
        <div v-if="asking" class="m-field">
          <label class="m-label" for="q-auth">查询密码</label>
          <input id="q-auth" class="m-input" v-model="accessToken" maxlength="64" placeholder="提交成功时显示的那串字符"
                 autocomplete="off">
        </div>
        <div v-if="errorMsg" class="m-error">{{ errorMsg }}</div>
        <button class="m-btn" type="button" :disabled="checking" @click="lookup">
          {{ checking ? '查询中…' : '查询' }}
        </button>
        <div class="m-hint">单号不含个人敏感信息，可以抄在纸上；查询密码只在你自己手机上。</div>
      </div>

      <div v-if="tickets.length" class="m-card">
        <span class="m-label">本机记录</span>
        <dl class="m-kv" v-for="item in tickets" :key="item.ticketNo"
            style="border-top: 1px solid var(--line); padding: 10px 0; margin: 0">
          <dd style="grid-column: 1 / -1">
            <div style="display:flex;align-items:center;gap:8px">
              <button class="m-btn ghost" style="flex:1;text-align:left" type="button" @click="openSaved(item)">
                {{ item.ticketNo }}
                <span v-if="item.title" style="color:var(--ink-soft);font-weight:400"> {{ item.title }}</span>
              </button>
              <button class="m-btn ghost danger" style="flex:0 0 auto;padding:10px 14px" type="button"
                      @click="removeSaved(item)">删除
              </button>
            </div>
          </dd>
        </dl>
        <div class="m-hint">这里只是手机上的快捷入口，删除不影响科室里的登记记录。</div>
      </div>
    </main>
  </div>
</template>
