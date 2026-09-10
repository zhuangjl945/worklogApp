<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  fetchFormToken,
  fetchMeta,
  submitTicket,
  uploadTicketImage
} from '../../api/ticketPublic'
import { compressImage } from '../../utils/imageCompress'
import { saveTicket } from '../../utils/ticketVault'
import {
  normalizePhone,
  normalizeQueryCode,
  randomQueryCode,
  resolveFormRules,
  submitBlockReason
} from '../../utils/ticketFormGuard'
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
  // 查询密码由报修人自己设（6 位数字），不是系统下发的长令牌：
  // 单号已经是可预测的短流水，能挡住遍历的只有这一项，所以它必填、也不允许全同或连号
  queryCode: '',
  categoryId: null,
  urgency: 3,  // 默认选普通级别，服务端 meta 返回的 urgencies 列表决定实际可选范围
  imageKeys: [],
  website: ''            // 蜜罐：页面上这个输入框对人是隐藏的，人不会填它
})

// 本地预览用（objectURL），与真正上传到 OSS 的 key 一一对应
const shots = ref([])

// 表单规则（哪些字段必填、最少几个字、照片至少几张）由服务端按「参数配置 → 登记表单」下发，
// 页面只负责照着渲染标记和自己先拦一道；真正的裁决仍在服务端，两边读的是同一份参数。
const rules = computed(() => resolveFormRules(meta.value?.formRules))
const maxImages = computed(() => meta.value?.maxImages ?? rules.value.maxImages)
// 服务端已把「渠道勾选 OR 全局参数」合并进 needPhone，这里再兜一层，防旧后端不下发参数
const needPhone = computed(() => !!meta.value?.needPhone || rules.value.contactPhoneRequired)
const titleMin = computed(() => (rules.value.titleRequired ? rules.value.titleMinLen : 0))
const contentMin = computed(() => (rules.value.contentRequired ? rules.value.contentMinLen : 0))
const categoryRequired = computed(() => rules.value.categoryRequired)
const minImages = computed(() => rules.value.minImages)

// 问题类型：标签一行一个太吃高度，超过 4 个就折成一行选择器，点开才铺全。
// 4 个以内直接摊开，省一次点击，也省得为「一眼看得全」的东西再折叠。
const categoryOptions = computed(() => meta.value?.categories || [])
const collapsibleCategory = computed(() => categoryOptions.value.length > 4)
const categoryOpen = ref(false)
const categoryName = computed(
  () => categoryOptions.value.find((o) => o.id === form.categoryId)?.name || ''
)

function pickCategory(id) {
  form.categoryId = id
  // 折叠态下选完即收，让「选类型」这件事在视觉上只有两下
  if (collapsibleCategory.value) categoryOpen.value = false
  keepDraft()
}

const result = ref(null)

onMounted(async () => {
  document.documentElement.classList.add('m-ticket-page')
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

onUnmounted(() => {
  document.documentElement.classList.remove('m-ticket-page')
})

function restoreDraft() {
  try {
    const raw = sessionStorage.getItem(DRAFT_KEY)
    if (!raw) return
    const d = JSON.parse(raw)
    // 只回灌文本，图片不恢复（objectURL 已失效，重传更可靠）
    ;['title', 'content', 'location', 'contactName', 'contactPhone', 'queryCode'].forEach((k) => {
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
      queryCode: form.queryCode,
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

function onPhoneInput() {
  form.contactPhone = normalizePhone(form.contactPhone).slice(0, 20)
  keepDraft()
}

/** 查询密码只留数字并截到 6 位：多敲的字符当场消失，别等提交时才弹错误 */
function onCodeInput() {
  form.queryCode = normalizeQueryCode(form.queryCode)
  keepDraft()
}

function rollCode() {
  form.queryCode = randomQueryCode()
  keepDraft()
}

function focusField(id) {
  if (!id) return
  // 类型折起来时先展开再定位，否则报错文案指着一条看不见的选项
  if (id === 'f-category' && collapsibleCategory.value) categoryOpen.value = true
  const el = document.getElementById(id)
  if (!el) return
  el.scrollIntoView({ block: 'center', behavior: 'smooth' })
  if (typeof el.focus === 'function') {
    try { el.focus({ preventScroll: true }) } catch { el.focus() }
  }
}

async function onSubmit() {
  if (submitting.value) return
  const blocked = submitBlockReason({
    title: form.title,
    content: form.content,
    contactPhone: form.contactPhone,
    contactName: form.contactName,
    location: form.location,
    queryCode: form.queryCode,
    categoryId: form.categoryId,
    categoryOptions: meta.value?.categories || [],
    imageCount: form.imageKeys.length,
    needPhone: needPhone.value,
    uploading: shots.value.some((s) => s.uploading),
    rules: rules.value
  })
  if (blocked) {
    errorMsg.value = blocked.msg
    focusField(blocked.field)
    return
  }
  errorMsg.value = ''
  submitting.value = true
  keepDraft()
  try {
    const phone = normalizePhone(form.contactPhone)
    const resp = await submitTicket({
      formToken: formToken.value,
      title: form.title.trim(),
      content: form.content.trim(),
      location: form.location.trim() || undefined,
      contactName: form.contactName.trim() || undefined,
      contactPhone: phone || undefined,
      queryCode: form.queryCode,
      categoryId: form.categoryId || undefined,
      urgency: form.urgency,
      imageKeys: form.imageKeys,
      website: form.website
    })
    // 后端同时下发 queryCode 与历史名 accessToken（值相同）：取前者，后者只用于旧缓存页面
    const { ticketNo, saved } = resp.data
    const code = resp.data.queryCode || resp.data.accessToken
    if (saved !== false) saveTicket(ticketNo, code, form.title.trim())
    try {
      sessionStorage.removeItem(DRAFT_KEY)
    } catch { /* ignore */ }
    result.value = { ticketNo, queryCode: code }
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

/**
 * 分段按钮一行四档，每格只有 60 多 px，「约 24 小时内响应」会被拆成三行竖排。
 * 这里只留时长；完整说法在工单详情里再看。
 */
function formatSlaHint(minutes) {
  const m = Number(minutes)
  if (!Number.isFinite(m) || m <= 0) return ''
  if (m < 60) return `${m}分内`
  if (m < 1440) {
    const h = m / 60
    return `${Number.isInteger(h) ? h : h.toFixed(1)}小时内`
  }
  const d = m / 1440
  return `${Number.isInteger(d) ? d : d.toFixed(1)}天内`
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
      <span class="m-sub">{{ meta?.bizDeptName || meta?.channelName || '' }}</span>
      <RouterLink class="m-link" to="/m/query">查进度</RouterLink>
    </header>

    <main class="m-body">
      <div v-if="booting" class="m-center">
        <div class="m-center-ic">⏳</div>
        <div>正在打开登记入口…</div>
      </div>

      <div v-else-if="bootError" class="m-center">
        <div class="m-center-ic">⚠️</div>
        <div>{{ bootError }}</div>
        <p>请检查二维码是否过期或联系管理员。</p>
      </div>

      <!-- 提交成功：单号与查询密码只出现这一次 -->
      <template v-else-if="result">
        <div class="m-card m-card--status-done">
          <span class="m-status s-done">提交成功</span>
          <p class="m-ticket-no">{{ result.ticketNo }}</p>
          <div class="m-note">
            下面这 6 位是你自己设的查询密码，本机已经自动记住；换手机或清过浏览器数据后要重新输入它，
            所以请记牢。忘了也能找受理科室按单号代查。
          </div>
          <div class="m-secret">
            查询密码 <b>{{ result.queryCode }}</b>
          </div>
          <div class="m-inline-actions" style="margin-top: 16px">
            <button class="m-btn ghost" type="button" @click="copy(result.ticketNo + '\n' + result.queryCode)">
              复制全部
            </button>
            <RouterLink class="m-btn ghost" style="text-align:center;text-decoration:none"
                       :to="`/m/ticket/${result.ticketNo}?auth=${encodeURIComponent(result.queryCode)}`">
              查看进度
            </RouterLink>
          </div>
        </div>
        <button class="m-btn ghost" type="button" @click="again">再登记一个问题</button>
      </template>

      <template v-else>
        <div v-if="errorMsg" class="m-error">{{ errorMsg }}</div>

        <!-- 问题描述区块 -->
        <div class="m-card">
          <span class="m-section-title">问题信息</span>
          <div class="m-field">
            <label class="m-label" for="f-title">问题标题
              <span v-if="rules.titleRequired" class="m-req">必填</span>
              <span v-else class="m-opt">选填</span>
            </label>
            <input id="f-title" class="m-input" v-model="form.title" maxlength="200"
                   placeholder="一句话说明，例如「3 楼打印机卡纸」" @input="keepDraft">
            <div class="m-counter">
              {{ form.title.trim().length }}/200<small v-if="titleMin > 1">（至少 {{ titleMin }} 字）</small>
            </div>
          </div>

          <div class="m-field">
            <label class="m-label" for="f-content">问题描述
              <span v-if="rules.contentRequired" class="m-req">必填</span>
              <span v-else class="m-opt">选填</span>
            </label>
            <textarea id="f-content" class="m-textarea" v-model="form.content" maxlength="5000"
                      :placeholder="rules.contentRequired
                        ? '什么时间开始、做了什么操作、屏幕上提示什么、影响几个人使用'
                        : '简单说清楚问题即可'"
                      @input="keepDraft"></textarea>
            <div class="m-counter">
              {{ form.content.trim().length }}/5000<small v-if="contentMin > 1">（至少 {{ contentMin }} 字）</small>
            </div>
          </div>

          <!--
            现场照片紧跟着问题描述：拍的就是上面刚说的那件事。
            原来它单独一张卡排在表单末尾，经常填到那儿才想起来要配图。
          -->
          <div id="shots" class="m-field">
            <span class="m-label">现场照片 <span class="m-count">{{ shots.length }}/{{ maxImages }}</span>
              <span v-if="minImages > 0" class="m-req">至少 {{ minImages }} 张</span>
              <span v-else class="m-opt">选填</span>
            </span>
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

          <!-- 蜜罐：对读屏软件和键盘都隐藏，只有按表单规律自动填字段的脚本会填它 -->
          <div class="m-field hp-fax" aria-hidden="true">
            <label for="f-fax">传真号码（请勿填写）</label>
            <input id="f-fax" class="m-input" v-model="form.website"
                   autocomplete="off" tabindex="-1" name="hp_fax_code" readonly
                   @focus="(e) => e.target.removeAttribute('readonly')">
          </div>
        </div>

        <!-- 分类与紧急程度 -->
        <div class="m-card">
          <span class="m-section-title">分类与优先级</span>
          <div class="m-field">
            <span class="m-label">紧急程度</span>
            <div class="m-seg">
              <button v-for="u in (meta?.urgencies || [])" :key="u.code" type="button"
                      :class="{ on: form.urgency === u.code }" @click="form.urgency = u.code">
                {{ u.name }}<small>{{ formatSlaHint(u.slaMinutes) }}</small>
              </button>
            </div>
          </div>

          <div v-if="meta?.categories?.length" id="f-category" class="m-field">
            <span class="m-label">问题类型
              <span v-if="categoryRequired" class="m-req">必填</span>
              <span v-else class="m-opt">选填</span>
            </span>
            <!-- 收起只占一行 48px；选中后这一行本身就是结果，不展开也能核对 -->
            <button v-if="collapsibleCategory" class="m-pick" type="button"
                    :class="{ filled: !!form.categoryId }"
                    :aria-expanded="categoryOpen ? 'true' : 'false'"
                    aria-controls="f-category-list"
                    @click="categoryOpen = !categoryOpen">
              <span class="m-pick-val">{{ categoryName || '点这里选一类' }}</span>
              <span class="m-pick-meta">{{ categoryName ? '换一类' : '共 ' + categoryOptions.length + ' 类' }}</span>
              <span class="m-pick-arrow" aria-hidden="true"></span>
            </button>

            <div v-show="!collapsibleCategory || categoryOpen" id="f-category-list" class="m-chips"
                 :class="{ 'm-chips--pick': collapsibleCategory }">
              <!-- 参数要求必选类型时收起「不指定」：留着一个必然被服务端拒掉的选项没有意义 -->
              <button v-if="!categoryRequired" type="button" :class="{ on: !form.categoryId }"
                      @click="pickCategory(null)">不指定</button>
              <button v-for="c in categoryOptions" :key="c.id" type="button"
                      :class="{ on: form.categoryId === c.id }" @click="pickCategory(c.id)">{{ c.name }}</button>
            </div>
          </div>

          <div class="m-field">
            <label class="m-label" for="f-loc">发生地点
              <span v-if="rules.locationRequired" class="m-req">必填</span>
              <span v-else class="m-opt">选填</span>
            </label>
            <input id="f-loc" class="m-input" v-model="form.location" maxlength="200"
                   placeholder="具体地点、设备名称" @input="keepDraft">
            <div v-if="meta?.bizDeptName" class="m-hint">本入口归属：{{ meta.bizDeptName }}，无需再选科室</div>
          </div>
        </div>

        <!-- 联系方式 -->
        <div class="m-card">
          <span class="m-section-title">联系方式</span>
          <div class="m-field">
            <label class="m-label" for="f-name">你的姓名或工号
              <span v-if="rules.contactNameRequired" class="m-req">必填</span>
              <span v-else class="m-opt">选填</span>
            </label>
            <input id="f-name" class="m-input" v-model="form.contactName" maxlength="50"
                   placeholder="方便同事上门时确认找你" @input="keepDraft">
          </div>
          <div class="m-field">
            <label class="m-label" for="f-phone">联系电话 <span v-if="needPhone" class="m-req">必填</span></label>
            <input id="f-phone" class="m-input" v-model="form.contactPhone" maxlength="20" inputmode="tel"
                   :placeholder="needPhone ? '手机、短号或内线均可' : '选填，手机/短号/内线'" @input="onPhoneInput">
          </div>
        </div>

        <!--
          查询凭证：单号是 ST100001 这种可预测的短流水，任何人抄到号就能凑出相邻的号，
          所以「别人读不到我这条」完全靠下面这 6 位数字。它必填、每单不同，也不做成后台可关的参数。
        -->
        <div class="m-card">
          <span class="m-section-title">查询密码 <span class="m-req">必填</span></span>
          <div class="m-field">
            <label class="m-label" for="f-code">自己设一个 6 位数字</label>
            <div class="m-code-row">
              <input id="f-code" class="m-input m-code-input" v-model="form.queryCode" maxlength="6"
                     inputmode="numeric" autocomplete="off" placeholder="6 位数字" @input="onCodeInput">
              <button class="m-btn ghost m-code-dice" type="button" @click="rollCode">帮我生成</button>
            </div>
            <div class="m-hint">
              之后凭「单号 + 这个密码」查进度；只有你自己知道密码，别人才读不到你报的内容。
              别照抄提示里的样例，也别用 111111、123456 这类一眼猜中的号。
            </div>
          </div>
        </div>

      </template>
    </main>

    <footer v-if="!booting && !bootError && !result" class="m-bar">
      <button class="m-btn" type="button" :disabled="submitting" @click="onSubmit">
        {{ submitting ? '提交中…' : '提交登记' }}
      </button>
    </footer>
  </div>
</template>

<style>
/* 查询密码：数字居中并拉开字距，方便看清自己设的是哪 6 位；生成按钮跟在同一行 */
.m-code-row {
  display: flex;
  /* stretch：按钮高度跟着输入框走，不用手对 min-height，两边永远齐平 */
  align-items: stretch;
  gap: 8px;
}
.m-code-input {
  /* 输入框吃掉剩余宽度，6 位数字仍然居中；不再给按钮留出「整行宽」的位置 */
  flex: 1 1 auto;
  min-width: 0;
  text-align: center;
  letter-spacing: 0.35em;
  font-variant-numeric: tabular-nums;
}
.m-code-dice {
  /* .m-btn 自带 width:100%，在 flex 行里会被算成整行宽，直接把卡片顶穿 */
  flex: 0 0 auto;
  width: auto;
  min-height: 48px;
  padding: 0 14px;
  line-height: 1.2;
  font-size: 13px;
  white-space: nowrap;
}

/* 蜜罐：裁切隐藏，禁止接收点击，避免盖住底部提交按钮 */
.hp-fax {
  position: absolute !important;
  width: 1px !important;
  height: 1px !important;
  padding: 0 !important;
  margin: -1px !important;
  overflow: hidden !important;
  clip: rect(0, 0, 0, 0);
  clip-path: inset(50%);
  border: 0 !important;
  pointer-events: none !important;
}
.hp-fax .m-input {
  min-height: 0 !important;
  width: 1px !important;
  pointer-events: none !important;
}
</style>
