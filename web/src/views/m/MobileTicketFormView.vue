<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  fetchFormToken,
  fetchMeta,
  submitTicket,
  uploadTicketImage
} from '../../api/ticketPublic'
import { compressImage } from '../../utils/imageCompress'
import { saveTicket } from '../../utils/ticketVault'
import './mobile.css'

const route = useRoute()
const channelCode = String(route.params.channelCode || '').toUpperCase()

const DRAFT_KEY = `ticket_draft_${channelCode}`

const booting = ref(true)
const bootError = ref('')
const submitting = ref(false)
const errorMsg = ref('')

const formToken = ref('')
const meta = ref(null)

const form = reactive({
  title: '',
  content: '',
  location: '',
  contactName: '',
  contactPhone: '',
  categoryId: null,
  urgency: 2,
  imageKeys: [],
  website: ''            // 蜜罐：页面上这个输入框对人是隐藏的，人不会填它
})

// 本地预览用（objectURL），与真正上传到 OSS 的 key 一一对应
const shots = ref([])

const maxImages = computed(() => meta.value?.maxImages ?? 9)
const needPhone = computed(() => !!meta.value?.needPhone)
const canSubmit = computed(() =>
  !submitting.value &&
  form.title.trim().length >= 4 &&
  form.content.trim().length >= 5 &&
  (!needPhone.value || /^1[3-9]\d{9}$/.test(form.contactPhone.trim())) &&
  shots.value.every((s) => !s.uploading)
)

const result = ref(null)

onMounted(async () => {
  try {
    const tokenResp = await fetchFormToken(channelCode)
    formToken.value = tokenResp.data.formToken
    const metaResp = await fetchMeta(tokenResp.data.formToken)
    meta.value = metaResp.data
    restoreDraft()
  } catch (e) {
    bootError.value = e?.message || '登记入口无效，请重新扫码'
  } finally {
    booting.value = false
  }
})

function restoreDraft() {
  try {
    const raw = sessionStorage.getItem(DRAFT_KEY)
    if (!raw) return
    const d = JSON.parse(raw)
    // 只回灌文本，图片不恢复（objectURL 已失效，重传更可靠）
    ;['title', 'content', 'location', 'contactName', 'contactPhone'].forEach((k) => {
      if (typeof d[k] === 'string') form[k] = d[k]
    })
    if (d.categoryId) form.categoryId = d.categoryId
    if (d.urgency) form.urgency = d.urgency
  } catch {
    /* 草稿坏了不影响填表 */
  }
}

function keepDraft() {
  try {
    sessionStorage.setItem(DRAFT_KEY, JSON.stringify({
      title: form.title,
      content: form.content,
      location: form.location,
      contactName: form.contactName,
      contactPhone: form.contactPhone,
      categoryId: form.categoryId,
      urgency: form.urgency
    }))
  } catch {
    /* ignore */
  }
}

async function onPickFiles(event) {
  const files = Array.from(event.target?.files || [])
  event.target.value = ''            // 允许连续选同一张文件
  for (const file of files) {
    if (shots.value.length >= maxImages.value) {
      errorMsg.value = `最多 ${maxImages.value} 张图片`
      break
    }
    const item = { key: null, preview: '', uploading: true, name: file.name }
    shots.value.push(item)
    try {
      const compressed = await compressImage(file)
      item.preview = URL.createObjectURL(compressed)
      item.key = await uploadTicketImage(formToken.value, compressed)
      form.imageKeys.push(item.key)
    } catch (e) {
      errorMsg.value = e?.message || '图片上传失败'
      const idx = shots.value.indexOf(item)
      if (idx >= 0) shots.value.splice(idx, 1)
    } finally {
      item.uploading = false
    }
  }
}

function removeShot(index) {
  const item = shots.value[index]
  if (!item) return
  if (item.preview) URL.revokeObjectURL(item.preview)
  if (item.key) {
    const at = form.imageKeys.indexOf(item.key)
    if (at >= 0) form.imageKeys.splice(at, 1)
  }
  shots.value.splice(index, 1)
}

async function onSubmit() {
  if (!canSubmit.value) return
  errorMsg.value = ''
  submitting.value = true
  keepDraft()
  try {
    const resp = await submitTicket({
      formToken: formToken.value,
      title: form.title.trim(),
      content: form.content.trim(),
      location: form.location.trim() || undefined,
      contactName: form.contactName.trim() || undefined,
      contactPhone: form.contactPhone.trim() || undefined,
      categoryId: form.categoryId || undefined,
      urgency: form.urgency,
      imageKeys: form.imageKeys,
      website: form.website
    })
    const { ticketNo, accessToken, saved } = resp.data
    if (saved !== false) saveTicket(ticketNo, accessToken, form.title.trim())
    try {
      sessionStorage.removeItem(DRAFT_KEY)
    } catch { /* ignore */ }
    result.value = { ticketNo, accessToken }
    window.scrollTo(0, 0)
  } catch (e) {
    const msg = e?.message || '提交失败'
    // 令牌 30 分钟过期（用户去接了个电话回来再提交）：自动换一张再试一次
    if (/过期|重新扫码/.test(msg) && !errorMsg.value) {
      errorMsg.value = '填表凭证已过期，正在重新获取，请再点一次提交'
      try {
        const resp = await fetchFormToken(channelCode)
        formToken.value = resp.data.formToken
        return
      } catch (inner) {
        errorMsg.value = inner?.message || msg
        return
      }
    }
    errorMsg.value = msg
  } finally {
    submitting.value = false
  }
}

function copy(text) {
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).catch(() => {})
  }
}

async function again() {
  // 提交成功时服务端已作废这张 form token，再登记必须重新换一张
  try {
    const resp = await fetchFormToken(channelCode)
    formToken.value = resp.data.formToken
  } catch (e) {
    bootError.value = e?.message || '重新获取登记凭证失败，请刷新页面'
  }
  result.value = null
  form.title = ''
  form.content = ''
  form.imageKeys = []
  shots.value.forEach((s) => s.preview && URL.revokeObjectURL(s.preview))
  shots.value = []
}
</script>

<template>
  <div class="m-shell">
    <header class="m-topbar">
      <h1>问题登记</h1>
      <span class="m-sub">{{ meta?.channelName || '' }}</span>
      <RouterLink class="m-link" to="/m/query">查进度</RouterLink>
    </header>

    <main class="m-body">
      <div v-if="booting" class="m-center">正在打开登记入口…</div>

      <div v-else-if="bootError" class="m-center">
        <div class="m-center-ic">⚠️</div>
        <div>{{ bootError }}</div>
      </div>

      <!-- 提交成功：单号与查询密码只出现这一次 -->
      <template v-else-if="result">
        <div class="m-card">
          <div class="m-status s-done">已提交，等待科室受理</div>
          <div class="m-result-no">{{ result.ticketNo }}</div>
          <div class="m-note">
            请保存下面的查询密码，找回工单时要用；本机会自动记住，但清理浏览器数据后会丢失。
          </div>
          <div class="m-secret">
            查询密码 <b>{{ result.accessToken }}</b>
          </div>
          <div class="m-inline-actions" style="margin-top: 14px">
            <button class="m-btn ghost" type="button" @click="copy(result.ticketNo + '\n' + result.accessToken)">
              复制单号与密码
            </button>
            <RouterLink class="m-btn ghost" style="text-align:center;text-decoration:none"
                       :to="`/m/ticket/${result.ticketNo}?auth=${encodeURIComponent(result.accessToken)}`">
              查看进度
            </RouterLink>
          </div>
        </div>
        <button class="m-btn ghost" type="button" @click="again">再登记一个问题</button>
      </template>

      <template v-else>
        <div v-if="errorMsg" class="m-error">{{ errorMsg }}</div>

        <div class="m-card">
          <div class="m-field">
            <label class="m-label" for="f-title">问题标题 <span class="m-req">必填</span></label>
            <input id="f-title" class="m-input" v-model="form.title" maxlength="200"
                   placeholder="一句话说明，例如「3 楼打印机卡纸」" @input="keepDraft">
            <div class="m-counter">{{ form.title.trim().length }}/200</div>
          </div>

          <div class="m-field">
            <label class="m-label" for="f-content">问题描述 <span class="m-req">必填</span></label>
            <textarea id="f-content" class="m-textarea" v-model="form.content" maxlength="5000"
                      placeholder="什么时间开始、做了什么操作、屏幕上提示什么、影响几个人使用"
                      @input="keepDraft"></textarea>
            <div class="m-counter">{{ form.content.trim().length }}/5000</div>
          </div>

          <!-- 蜜罐：对读屏软件和键盘都隐藏，只有按表单规律自动填字段的脚本会填它 -->
          <div class="m-field hp-fax" aria-hidden="true">
            <label for="f-fax">传真号码（请勿填写）</label>
            <input id="f-fax" class="m-input" v-model="form.website" autocomplete="off" tabindex="-1" name="website">
          </div>
        </div>

        <div class="m-card">
          <div class="m-field">
            <span class="m-label">紧急程度</span>
            <div class="m-seg">
              <button v-for="u in (meta?.urgencies || [])" :key="u.code" type="button"
                      :class="{ on: form.urgency === u.code }" @click="form.urgency = u.code">
                {{ u.name }}<small>约 {{ u.slaHours }} 小时内响应</small>
              </button>
            </div>
          </div>

          <div v-if="meta?.categories?.length" class="m-field">
            <span class="m-label">问题类型</span>
            <div class="m-chips">
              <button type="button" :class="{ on: !form.categoryId }" @click="form.categoryId = null">不指定</button>
              <button v-for="c in meta.categories" :key="c.id" type="button"
                      :class="{ on: form.categoryId === c.id }" @click="form.categoryId = c.id">{{ c.name }}</button>
            </div>
          </div>

          <div class="m-field">
            <label class="m-label" for="f-loc">发生地点</label>
            <input id="f-loc" class="m-input" v-model="form.location" maxlength="200"
                   placeholder="楼层 / 房间号 / 设备编号" @input="keepDraft">
          </div>
        </div>

        <div class="m-card">
          <div class="m-field">
            <span class="m-label">现场照片（{{ shots.length }}/{{ maxImages }}）</span>
            <div class="m-shots">
              <div v-for="(s, i) in shots" :key="i" class="m-shot">
                <img v-if="s.preview" :src="s.preview" alt="现场照片">
                <div v-if="s.uploading" class="m-shot-state">上传中</div>
                <button class="m-shot-del" type="button" aria-label="删除这张照片" @click="removeShot(i)">×</button>
              </div>
              <label v-if="shots.length < maxImages" class="m-shot-add">
                <span>＋</span>
                加照片
                <input type="file" accept="image/*" capture="environment" multiple hidden @change="onPickFiles">
              </label>
            </div>
            <div class="m-hint">照片会自动压缩，只支持 jpg/png/gif/webp，单张 5MB 以内。</div>
          </div>
        </div>

        <div class="m-card">
          <div class="m-field">
            <label class="m-label" for="f-name">你的姓名或工号</label>
            <input id="f-name" class="m-input" v-model="form.contactName" maxlength="50"
                   placeholder="方便同事上门时确认找你" @input="keepDraft">
          </div>
          <div class="m-field">
            <label class="m-label" for="f-phone">手机号 <span v-if="needPhone" class="m-req">必填</span></label>
            <input id="f-phone" class="m-input" v-model="form.contactPhone" maxlength="11" inputmode="numeric"
                   :placeholder="needPhone ? '处理结果会电话联系你' : '选填'" @input="keepDraft">
          </div>
        </div>
      </template>
    </main>

    <footer v-if="!booting && !bootError && !result" class="m-bar">
      <button class="m-btn" type="button" :disabled="!canSubmit" @click="onSubmit">
        {{ submitting ? '提交中…' : '提交登记' }}
      </button>
    </footer>
  </div>
</template>

<style>
/* 蜜罐字段：留在表单里让脚本填，但对人完全不可见 */
.hp-fax {
  position: absolute !important;
  left: -9999px !important;
  width: 1px;
  height: 1px;
  overflow: hidden;
}
</style>
