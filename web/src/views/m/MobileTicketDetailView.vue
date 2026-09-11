<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  fetchTicketDetail,
  loadTicketImage,
  reporterConfirm,
  reporterRate,
  reporterReopen,
  reporterReply,
  uploadTicketImage,
  fetchFormToken
} from '../../api/ticketPublic'
import { compressImage } from '../../utils/imageCompress'
import { forgetTicket, getAccessToken, saveTicket } from '../../utils/ticketVault'
import './mobile.css'

const route = useRoute()
const ticketNo = String(route.params.ticketNo || '').toUpperCase()

const loading = ref(true)
const errorMsg = ref('')
const detail = ref(null)
const accessToken = ref('')
const objectUrls = ref([])

const replyText = ref('')
const replyShots = ref([])
const busy = ref(false)
const notice = ref('')

/* 根据工单状态返回卡片修饰类，用左边框色块表达当前状态 */
const statusCardClass = computed(() => {
  const code = detail.value?.status
  if (code === 30 || code === 40) return 'm-card m-card--status-done'
  if (code === 10 || code === 20) return 'm-card m-card--status-active'
  return 'm-card m-card--status-pending'
})

const statusClass = computed(() => {
  const code = detail.value?.status
  if (code === 30 || code === 40) return 'm-status s-done'
  if (code === 50) return 'm-status'
  return 'm-status s-wait'
})

const canConfirm = computed(() => detail.value?.status === 20)
const canReopen = computed(() => detail.value?.status === 20)

const canRate = computed(() => (detail.value?.status === 30 || detail.value?.status === 40) && !detail.value?.rating)
const canReply = computed(() => detail.value?.status !== 40 && detail.value?.status !== 30)

/* 倒计时的「现在」：由下面的定时器每 30 秒推一次，computed 才能自己重算 */
let nowTick = ref(Date.now())
let autoConfirmTimer = null

/**
 * 自动确认倒计时。
 *
 * <p>后端只在「状态 20 + 参数开启」时才下发 autoConfirmAt，所以这里不用再判断开关；
 * 参数关闭时提示自动消失，报修人看到的就是「等你确认」而不是「限时确认」。
 */
const autoConfirmHint = computed(() => {
  const at = detail.value?.autoConfirmAt
  if (!at || detail.value?.status !== 20) return ''
  const left = Math.floor((parseServerTime(at) - nowTick.value) / 60000)
  if (left <= 0) return '已超过确认时限，系统即将自动确认已解决，稍后刷新即可'
  return `请在 ${spanText(left)}内确认；超时未确认，系统将自动确认已解决`
})

/** 分钟数转「X 小时 Y 分」，不足 1 小时只说分钟 */
function spanText(minutes) {
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return h ? (m ? `${h} 小时 ${m} 分` : `${h} 小时`) : `${m} 分钟`
}

/** "yyyy-MM-dd HH:mm:ss" → 时间戳；iOS Safari 不认横杠加空格的写法，先换成斜杠 */
function parseServerTime(text) {
  return new Date(String(text).replace(/-/g, '/')).getTime()
}

onMounted(async () => {
  // 查询密码优先取链接里带的（提交成功页跳转过来），否则取本机存过的
  accessToken.value = String(route.query.auth || '') || getAccessToken(ticketNo)
  if (!accessToken.value) {
    errorMsg.value = '缺少查询密码，请回到「查询进度」用单号重新查询'
    loading.value = false
    return
  }
  await refresh()
})

onUnmounted(() => {
  objectUrls.value.forEach((u) => URL.revokeObjectURL(u))
  if (autoConfirmTimer) clearInterval(autoConfirmTimer)
})

/**
 * 每 30 秒推一次「现在」，让倒计时数字自己动。
 *
 * <p>时间到了但服务端还没改状态（扫描间隔最多 5 分钟）时顺手拉一次详情：
 * 状态一变，这条提示自然就换成「已完成」，报修人不需要手动刷新。
 */
onMounted(() => {
  autoConfirmTimer = setInterval(() => {
    nowTick.value = Date.now()
    const at = detail.value?.autoConfirmAt
    if (at && detail.value?.status === 20 && Date.now() >= parseServerTime(at) && !busy.value && !loading.value) {
      refresh()
    }
  }, 30000)
})

async function refresh() {
  loading.value = true
  errorMsg.value = ''
  try {
    const resp = await fetchTicketDetail(ticketNo, accessToken.value)
    detail.value = resp.data
    saveTicket(ticketNo, accessToken.value, resp.data?.title)
    await renderImages()
  } catch (e) {
    errorMsg.value = e?.message || '查询失败'
  } finally {
    loading.value = false
  }
}

/**
 * 图片一律用带令牌的请求取回再转 objectURL：
 * 让 <img src> 直连后端就得把令牌塞进 URL，那会落进浏览器历史和代理日志。
 */
async function renderImages() {
  objectUrls.value.forEach((u) => URL.revokeObjectURL(u))
  objectUrls.value = []
  const keys = detail.value?.images || []
  for (const key of keys) {
    try {
      objectUrls.value.push(await loadTicketImage(ticketNo, accessToken.value, key))
    } catch {
      /* 单张失败不阻断整页 */
    }
  }
}

async function run(action, okMessage) {
  busy.value = true
  errorMsg.value = ''
  notice.value = ''
  try {
    await action()
    notice.value = okMessage
    await refresh()
  } catch (e) {
    errorMsg.value = e?.message || '操作失败，请稍后再试'
  } finally {
    busy.value = false
  }
}

async function onPickFiles(event) {
  const files = Array.from(event.target?.files || [])
  event.target.value = ''
  for (const file of files) {
    if (replyShots.value.length >= 9) break
    const item = { key: null, preview: '', uploading: true }
    replyShots.value.push(item)
    try {
      const compressed = await compressImage(file)
      item.preview = URL.createObjectURL(compressed)
      // 补充说明也要先换一张填表凭证才能取上传签名
      if (!formTokenForReply.value) {
        const code = detail.value?.channelCode
        if (!code) {
          throw new Error('缺少登记入口信息，请只用文字补充')
        }
        const t = await fetchFormToken(code)
        formTokenForReply.value = t.data.formToken
      }
      item.key = await uploadTicketImage(formTokenForReply.value, compressed)
    } catch (e) {
      errorMsg.value = e?.message || '图片上传失败'
      const idx = replyShots.value.indexOf(item)
      if (idx >= 0) replyShots.value.splice(idx, 1)
    } finally {
      item.uploading = false
    }
  }
}

const formTokenForReply = ref('')

function onReply() {
  const text = replyText.value.trim()
  const keys = replyShots.value.filter((s) => s.key).map((s) => s.key)
  if (!text && !keys.length) {
    errorMsg.value = '请先写点什么或附上照片'
    return
  }
  run(async () => {
    await reporterReply(ticketNo, accessToken.value, { remark: text || '补充了照片', imageKeys: keys })
    replyText.value = ''
    replyShots.value = []
    formTokenForReply.value = ''
  }, '已补充，科室同事会在受理台看到')
}

function onConfirm() {
  run(() => reporterConfirm(ticketNo, accessToken.value), '已确认解决，感谢反馈')
}

function onReopen() {
  const reason = window.prompt('还没解决？简单说明一下情况', '')
  if (reason === null) return
  run(() => reporterReopen(ticketNo, accessToken.value, reason.trim() || '问题仍然存在'), '已退回，科室会重新处理')
}

function onRate(star) {
  run(() => reporterRate(ticketNo, accessToken.value, { rating: star }), '评价已提交')
}

function forget() {
  forgetTicket(ticketNo)
  notice.value = '已从本机记录中移除，工单本身仍在科室系统里'
}
</script>

<template>
  <div class="m-shell">
    <header class="m-topbar">
      <h1>工单详情</h1>
      <RouterLink class="m-link" to="/m/query">查询其他</RouterLink>
    </header>

    <main class="m-body">
      <div v-if="loading" class="m-center">
        <div class="m-center-ic">⏳</div>
        <div>查询中…</div>
      </div>

      <template v-else-if="detail">
        <!-- 主信息卡片：带状态色块左边框 -->
        <div :class="statusCardClass">
          <span :class="statusClass">{{ detail.statusName }}</span>
          <p class="m-ticket-no">{{ ticketNo }}</p>
          <h2 class="m-ticket-title">{{ detail.title }}</h2>
          <dl class="m-kv">
            <dt>提交时间</dt>
            <dd>{{ detail.createTime }}</dd>
            <template v-if="detail.bizDeptName">
              <dt>问题科室</dt>
              <dd>{{ detail.bizDeptName }}</dd>
            </template>
            <template v-if="detail.location">
              <dt>地点</dt>
              <dd>{{ detail.location }}</dd>
            </template>
            <template v-if="detail.dueTime">
              <dt>期望受理</dt>
              <dd>{{ detail.dueTime }}</dd>
            </template>
            <template v-if="detail.assigneeName">
              <dt>处理人</dt>
              <dd>{{ detail.assigneeName }}</dd>
            </template>
            <template v-if="detail.acceptTime">
              <dt>响应耗时</dt>
              <dd>{{ detail.waitedMinutes }} 分钟</dd>
            </template>
          </dl>
          <!-- 报修人最容易「忘了点」的就是这一步，把时限和后果在卡片里一次说清 -->
          <p v-if="autoConfirmHint" class="m-autocount">{{ autoConfirmHint }}</p>
          <div v-if="(detail.images || []).length" class="m-thumbs">
            <img v-for="(url, i) in objectUrls" :key="i" :src="url" alt="问题照片">
          </div>
        </div>

        <div v-if="errorMsg" class="m-error">{{ errorMsg }}</div>
        <div v-if="notice" class="m-note">{{ notice }}</div>

        <!-- 处理进展时间线 -->
        <div class="m-card">
          <span class="m-section-title">处理进展</span>
          <ul class="m-flow">
            <li v-for="(l, i) in detail.logs" :key="i" :class="{ now: i === detail.logs.length - 1 }">
              <div class="m-flow-h">
                {{ l.operatorName || '系统' }}
                <span class="m-flow-t">{{ l.createTime }}</span>
              </div>
              <p v-if="l.remark">{{ l.remark }}</p>
            </li>
          </ul>
        </div>

        <!-- 满意度评价 -->
        <div v-if="canRate" class="m-card">
          <span class="m-section-title">处理结果满意吗</span>
          <div class="m-stars">
            <button v-for="n in 5" :key="n" type="button" :class="{ on: n <= (detail.rating || 0) }"
                    :disabled="busy" @click="onRate(n)">★</button>
          </div>
        </div>

        <!-- 补充说明 -->
        <div v-if="canReply" class="m-card">
          <label class="m-label" for="r-text">补充说明</label>
          <textarea id="r-text" class="m-textarea" v-model="replyText" maxlength="2000"
                    placeholder="有新情况、或者科室同事需要你配合什么，写在这里"></textarea>
          <div class="m-shots" style="margin-top:12px">
            <div v-for="(s, i) in replyShots" :key="i" class="m-shot">
              <img v-if="s.preview" :src="s.preview" alt="补充照片">
              <button class="m-shot-del" type="button" aria-label="删除" @click="replyShots.splice(i, 1)">×</button>
            </div>
            <label v-if="replyShots.length < 9" class="m-shot-add">
              <span>＋</span>加照片
              <input type="file" accept="image/*" capture="environment" multiple hidden @change="onPickFiles">
            </label>
          </div>
          <button class="m-btn ghost" style="margin-top: 14px" type="button" :disabled="busy" @click="onReply">发送补充</button>
        </div>

        <!-- 底部操作栏 -->
        <div v-if="canConfirm || canReopen" class="m-bar">
          <div class="m-inline-actions">
            <button v-if="canReopen" class="m-btn danger" type="button" :disabled="busy" @click="onReopen">还没解决</button>
            <button v-if="canConfirm" class="m-btn" type="button" :disabled="busy" @click="onConfirm">确认已解决</button>
          </div>
        </div>

        <button class="m-btn ghost" style="margin-top: 12px" type="button" @click="forget">从本机记录中移除</button>
      </template>

      <!-- 空状态 -->
      <div v-else class="m-center">
        <div class="m-center-ic">🔒</div>
        <div>{{ errorMsg || '没有找到这条登记' }}</div>
        <RouterLink class="m-btn ghost" style="margin-top:20px;text-align:center;text-decoration:none;display:inline-block;width:auto;padding:12px 24px" to="/m/query">
          返回查询
        </RouterLink>
      </div>
    </main>
  </div>
</template>
