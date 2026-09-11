<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ticketAccept,
  ticketAssign,
  ticketClose,
  ticketDetail,
  ticketDone,
  ticketReject,
  ticketReply,
  ticketToRecord
} from '../api/ticket'
import { userRoster } from '../api/user'
import { uploadToOss } from '../utils/oss'
import { fetchTicketImage } from '../utils/ticketThumb'

const route = useRoute()
const router = useRouter()
const id = route.params.id

const loading = ref(false)
const busy = ref(false)
const data = ref(null)

const replyText = ref('')
const replyVisible = ref(true)
const replyShots = ref([])

const assignOpen = ref(false)
const assignUser = ref(null)
const assignRemark = ref('')
const deptUsers = ref([])

const images = computed(() => data.value?.images || [])

/**
 * 工单附图渲染：后端只存 objectKey，它不是可访问 URL，
 * 必须换成 /api/tickets/{id}/image 代理取回的二进制地址，否则一律是裂图。
 * 时间线里的补充附图一并预取，避免滚动到才加载造成闪烁。
 */
const urlMap = ref({})

function shotSrc(key) {
  return urlMap.value[key] || ''
}

/** 预览列表必须与缩略图同序同长，不能 filter 空项，否则 :initial-index 会对错张 */
function previewList(keys) {
  return (keys || []).map((k) => shotSrc(k))
}

const allImageKeys = computed(() => {
  const out = []
  for (const k of data.value?.images || []) out.push(k)
  for (const l of data.value?.logs || []) {
    for (const k of l.images || []) out.push(k)
  }
  return out
})

watch(allImageKeys, async (keys) => {
  await Promise.all(keys.map(async (k) => {
    if (!k || urlMap.value[k]) return
    const url = await fetchTicketImage(id, k)
    if (url) urlMap.value[k] = url
  }))
}, { immediate: true })

const status = computed(() => data.value?.status ?? -1)
const canAccept = computed(() => status.value === 0)
const canAssign = computed(() => status.value === 0 || status.value === 10)
const canWork = computed(() => status.value === 10)
const canClose = computed(() => status.value === 30 || status.value === 50)

// 紧急程度 tag 颜色：code 越小越紧急
function urgencyTagType(code) {
  if (code <= 1) return 'danger'
  if (code === 2) return 'warning'
  if (code === 3) return ''
  return 'info'
}

function tagType(code) {
  if (code === 0) return 'danger'
  if (code === 10 || code === 20) return 'warning'
  if (code === 30 || code === 40) return 'success'
  return 'info'
}

function slaText() {
  const m = data.value?.slaRemainMinutes
  if (m == null) return '未设定受理时限'
  // 时限只约束「多久内必须受理」：待受理才倒计时，受理后停表
  if (data.value?.status === 0) {
    if (m < 0) return `已超时未受理 ${formatSpan(-m)}`
    return `剩余 ${formatSpan(m)} 需受理`
  }
  if (m < 0) return `超时受理（晚 ${formatSpan(-m)}）`
  return '已按时受理'
}

function slaOverdue() {
  return data.value?.status === 0
    && data.value?.slaRemainMinutes != null
    && data.value.slaRemainMinutes < 0
}

function formatSpan(minutes) {
  if (minutes < 60) return `${minutes} 分钟`
  const h = Math.floor(minutes / 60)
  const mm = minutes % 60
  return mm ? `${h} 小时 ${mm} 分` : `${h} 小时`
}

/**
 * 「待报修人确认」阶段的自动确认提示。
 * 后端只在开关打开且状态为 20 时才下发 autoConfirmRemainMinutes，
 * 所以下面不需要再判断参数是否启用，null 就等于「不提示」。
 */
const autoConfirmText = computed(() => {
  const m = data.value?.autoConfirmRemainMinutes
  if (status.value !== 20 || m == null) return ''
  return m <= 0 ? '报修人已超时未确认，系统将在下一轮扫描（5 分钟内）自动确认'
    : `报修人未确认，${formatSpan(m)}后系统将自动确认已解决`
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const resp = await ticketDetail(id)
    data.value = resp.data
  } catch (e) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function act(fn, okMessage) {
  busy.value = true
  try {
    await fn()
    ElMessage.success(okMessage)
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  } finally {
    busy.value = false
  }
}

function onAccept() {
  act(async () => {
    const resp = await ticketAccept(id)
    data.value = { ...data.value, workRecordId: resp.data?.workRecordId }
  }, '已受理，并生成对应工作记录')
}

async function openAssign() {
  assignOpen.value = true
  assignUser.value = null
  assignRemark.value = ''
  if (!deptUsers.value.length && data.value?.deptId) {
    try {
      // 花名册接口只返回启用的账号，不用再去传 status
      const resp = await userRoster({ size: 200, deptId: data.value.deptId })
      deptUsers.value = resp.data || []
    } catch (e) {
      ElMessage.warning('同事列表加载失败，可直接联系科室管理员')
    }
  }
}

function onAssign() {
  if (!assignUser.value) {
    ElMessage.warning('请选择接收人')
    return
  }
  act(async () => {
    await ticketAssign(id, { toUserId: assignUser.value, remark: assignRemark.value || undefined })
    assignOpen.value = false
  }, '已派单')
}

async function onPickShots(event) {
  const files = Array.from(event.target?.files || [])
  event.target.value = ''
  // 必须传到本渠道目录下，后端会校验 key 前缀，防止把别的对象塞进这条工单
  const dir = `tickets/${data.value?.channelCode || ''}/`
  for (const file of files) {
    if (replyShots.value.length >= 9) break
    const item = { key: '', preview: URL.createObjectURL(file), name: file.name }
    replyShots.value.push(item)
    try {
      item.key = await uploadToOss(file, dir)
    } catch (e) {
      ElMessage.error(e?.message || '上传失败')
      const idx = replyShots.value.indexOf(item)
      if (idx >= 0) {
        URL.revokeObjectURL(item.preview)
        replyShots.value.splice(idx, 1)
      }
    }
  }
}

function removeShot(i) {
  const item = replyShots.value[i]
  if (item?.preview) URL.revokeObjectURL(item.preview)
  replyShots.value.splice(i, 1)
}

function onReply() {
  const text = replyText.value.trim()
  const keys = replyShots.value.map((s) => s.key).filter(Boolean)
  if (!text && !keys.length) {
    ElMessage.warning('请填写回复内容')
    return
  }
  act(async () => {
    await ticketReply(id, {
      remark: text || '（仅附图）',
      imageKeys: keys,
      visibleToReporter: replyVisible.value
    })
    replyText.value = ''
    replyShots.value = []
    replyVisible.value = true
  }, replyVisible.value ? '已回复报修人' : '已保存内部备注')
}

function onDone() {
  act(async () => {
    await ticketDone(id, doneRemark.value || undefined)
  }, '已标记完成，等待报修人确认')
}

const doneRemark = ref('')

function onReject() {
  ElMessageBox.prompt('退回理由会原样展示给报修人', '退回工单', {
    inputPlaceholder: '例如：非本科室职责，请联系总务处',
    inputValidator: (v) => (v && v.trim().length >= 4) || '至少填写 4 个字',
    confirmButtonText: '确认退回'
  }).then(({ value }) => {
    act(() => ticketReject(id, value.trim()), '已退回')
  }).catch(() => {})
}

function onClose() {
  ElMessageBox.confirm('归档后工单不可再变更，确认？', '归档', { type: 'warning' })
    .then(() => act(() => ticketClose(id), '已归档'))
    .catch(() => {})
}

function onToRecord() {
  act(async () => {
    await ticketToRecord(id)
  }, '已生成工作记录')
}

function goRecords() {
  router.push('/work-records')
}
</script>

<template>
  <div v-loading="loading" class="ticket-detail">
    <template v-if="data">
      <div class="head">
        <div>
          <el-tag :type="tagType(data.status)" disable-transitions>{{ data.statusName }}</el-tag>
          <span class="no">{{ data.ticketNo }}</span>
          <el-tag v-if="data.urgencyName" :type="urgencyTagType(data.urgency)" size="small" effect="plain">{{ data.urgencyName }}</el-tag>
        </div>
        <div class="sla" :class="{ ov: slaOverdue() }">
          {{ slaText() }}
        </div>
        <div v-if="autoConfirmText" class="auto-confirm">{{ autoConfirmText }}</div>
        <el-button link @click="$router.push('/tickets')">← 返回受理台</el-button>
      </div>

      <el-card shadow="never" class="block">
        <h3 class="title">{{ data.title }}</h3>
        <p class="content">{{ data.content }}</p>
        <el-descriptions :column="3" size="small" border>
          <el-descriptions-item label="报修人">{{ data.contactName || '匿名' }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ data.contactPhone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="地点">{{ data.location || '—' }}</el-descriptions-item>
          <el-descriptions-item label="问题科室">{{ data.bizDeptName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="问题类型">{{ data.categoryName || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="登记入口">{{ data.channelName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="处理人">{{ data.assigneeName || '尚未受理' }}</el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ data.createTime }}</el-descriptions-item>
          <el-descriptions-item label="受理时间">{{ data.acceptTime || '—' }}</el-descriptions-item>
          <el-descriptions-item label="来源 IP">{{ data.submitIp || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="data.rating" label="满意度" :span="3">
            {{ '★'.repeat(data.rating) }}{{ data.ratingComment ? ' ' + data.ratingComment : '' }}
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="images.length" class="shots">
          <el-image v-for="(u, i) in images" :key="i" :src="shotSrc(u)" fit="cover" class="shot"
                     :preview-src-list="shotSrc(u) ? previewList(images) : []"
                     :initial-index="i" preview-teleported>
            <template #error><div class="shot-empty">图片加载中…</div></template>
          </el-image>
        </div>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>处理动作</template>
        <div class="actions">
          <el-button v-if="canAccept" type="primary" :loading="busy" @click="onAccept">受理并生成工作记录</el-button>
          <el-button v-if="canAssign" :loading="busy" @click="openAssign">派单给同事</el-button>
          <template v-if="canWork">
            <el-input v-model="doneRemark" placeholder="完成说明（报修人可见）" style="width: 260px" maxlength="200" />
            <el-button type="success" :loading="busy" @click="onDone">标记完成</el-button>
          </template>
          <el-button v-if="canWork || canAssign" type="warning" plain :loading="busy" @click="onReject">退回</el-button>
          <el-button v-if="canClose" :loading="busy" @click="onClose">归档</el-button>
          <el-button v-if="!data.workRecordId && !canAccept" :loading="busy" @click="onToRecord">仅生成工作记录</el-button>
          <el-button v-if="data.workRecordId" @click="goRecords">查看工作记录 #{{ data.workRecordId }}</el-button>
        </div>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>回复与备注</template>
        <el-input v-model="replyText" type="textarea" :rows="3" maxlength="5000" show-word-limit
                  placeholder="写点处理进展，报修人在手机上能看到" />
        <div class="reply-tools">
          <div class="shots reply-shots">
            <div v-for="(s, i) in replyShots" :key="i" class="shot-wrap">
              <el-image :src="s.preview" fit="cover" class="shot" />
              <span class="del" @click="removeShot(i)">×</span>
            </div>
          </div>
          <label class="up">
            ＋ 附图
            <input type="file" accept="image/*" multiple hidden @change="onPickShots">
          </label>
          <el-checkbox v-model="replyVisible">对报修人可见</el-checkbox>
          <el-button type="primary" :loading="busy" @click="onReply">发送</el-button>
        </div>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>处理记录</template>
        <el-timeline>
          <el-timeline-item v-for="l in data.logs" :key="l.id" :timestamp="l.createTime" placement="top"
                            :type="l.visibleToReporter === 0 ? 'warning' : 'primary'">
            <div class="log-h">
              <b>{{ l.operatorName || '系统' }}</b>
              <span class="act">{{ l.action }}</span>
              <el-tag v-if="l.visibleToReporter === 0" type="warning" size="small" effect="plain">仅内部可见</el-tag>
            </div>
            <p v-if="l.remark" class="log-r">{{ l.remark }}</p>
            <div v-if="l.images && l.images.length" class="shots">
              <el-image v-for="(u, i) in l.images" :key="i" :src="shotSrc(u)" fit="cover" class="shot"
                     :preview-src-list="shotSrc(u) ? previewList(l.images) : []"
                     :initial-index="i" preview-teleported>
            <template #error><div class="shot-empty">图片加载中…</div></template>
          </el-image>
            </div>
          </el-timeline-item>
        </el-timeline>
      </el-card>
    </template>

    <el-dialog v-model="assignOpen" title="派单给同事" width="420px">
      <el-select v-model="assignUser" filterable placeholder="选择本科室同事" style="width: 100%">
        <el-option v-for="u in deptUsers" :key="u.id" :label="`${u.realName}（${u.username}）`" :value="u.id" />
      </el-select>
      <el-input v-model="assignRemark" style="margin-top: 10px" maxlength="255" placeholder="交接说明（可选）" />
      <template #footer>
        <el-button @click="assignOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="onAssign">确定</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<style scoped>
.ticket-detail {
  padding: 2px;
}

.head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.no {
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: .02em;
}

.sla {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.sla.ov {
  color: var(--el-color-danger);
  font-weight: 600;
}

/* 自动确认倒计时：比 SLA 弱一档，它是解释性信息不是待办压力 */
.auto-confirm {
  font-size: 12px;
  color: var(--el-color-warning-dark-2);
}

.block {
  margin-bottom: 12px;
}

.title {
  margin: 0 0 6px;
  font-size: 17px;
}

.content {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0 0 12px;
  line-height: 1.7;
}

.shots {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
}

/* 取图接口返回前的占位，避免出现整块空白 */
.shot-empty {
  width: 84px;
  height: 84px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.shot {
  width: 84px;
  height: 84px;
  border-radius: 6px;
  cursor: zoom-in;
  border: 1px solid var(--el-border-color-light);
}

.shot-wrap {
  position: relative;
}

.shot-wrap .del {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 20px;
  height: 20px;
  line-height: 18px;
  text-align: center;
  border-radius: 50%;
  background: var(--el-color-danger);
  color: #fff;
  cursor: pointer;
  font-size: 14px;
}

.reply-tools {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.reply-shots {
  margin: 0;
}

.up {
  font-size: 13px;
  color: var(--el-color-primary);
  cursor: pointer;
  border: 1px dashed var(--el-border-color);
  border-radius: 4px;
  padding: 5px 10px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.log-h {
  display: flex;
  align-items: center;
  gap: 8px;
}

.act {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-family: ui-monospace, Menlo, Consolas, monospace;
}

.log-r {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 4px 0 0;
  color: var(--el-text-color-regular);
}
</style>
