<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Postcard } from '@element-plus/icons-vue'
import {
  ticketChannelCreate,
  ticketChannelLanOrigins,
  ticketChannelPage,
  ticketChannelToggle,
  ticketChannelUpdate
} from '../api/ticket'
import { workCategoryEnabled } from '../api/work'
import { deptTree } from '../api/dept'
import {
  fetchDevLanOrigins,
  getStoredQrOrigin,
  isLoopbackOrigin,
  mobileUrl,
  qrcodeModuleCount,
  qrcodeSvg,
  setStoredQrOrigin
} from '../utils/qrcodeSvg'
import { buildLabelDocument, openLabelPrint } from '../utils/labelPrint'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ page: 1, size: 20, status: null })

const categoryOptions = ref([])
const deptFlat = ref([])

const dialogOpen = ref(false)
const dialogMode = ref('create')
const editingId = ref(null)
const busy = ref(false)
const form = ref(emptyForm())

const qrOpen = ref(false)
const qrRow = ref(null)
const qrOrigin = ref(getStoredQrOrigin() || location.origin)
const lanOrigins = ref([])

const qrUsesLoopback = computed(() => isLoopbackOrigin(qrOrigin.value))
const qrOriginOptions = computed(() => {
  const set = new Set([qrOrigin.value, location.origin, ...lanOrigins.value].filter(Boolean))
  return [...set]
})

function emptyForm() {
  return {
    channelName: '',
    bizDeptId: null,
    defaultCategoryId: null,
    needPhone: 1,
    dailyLimit: 200,
    remark: '',
    status: 1
  }
}

const categoryName = computed(() => {
  const map = {}
  for (const c of categoryOptions.value) map[c.id] = c.categoryName
  return (id) => map[id] || '按分类选择'
})

async function load() {
  loading.value = true
  try {
    const resp = await ticketChannelPage(query.value)
    rows.value = resp.data?.records || []
    total.value = resp.data?.total || 0
  } catch (e) {
    ElMessage.error(e?.message || '加载登记渠道失败')
  } finally {
    loading.value = false
  }
}

async function loadDepts() {
  try {
    const resp = await deptTree()
    deptFlat.value = flattenDeptTree(resp.data || [])
  } catch {
    deptFlat.value = []
  }
}

function flattenDeptTree(nodes, depth = 0, out = []) {
  for (const n of nodes || []) {
    if (n.status !== 0) {
      out.push({
        id: n.id,
        name: n.deptName,
        label: `${'—'.repeat(depth)}${depth > 0 ? ' ' : ''}${n.deptName}`
      })
    }
    if (n.children?.length) flattenDeptTree(n.children, depth + 1, out)
  }
  return out
}

function onBizDeptChange(id) {
  const d = deptFlat.value.find((x) => x.id === id)
  if (d && !form.value.channelName.trim()) {
    form.value.channelName = d.name + '报修'
  }
}

async function loadCategories() {
  try {
    const resp = await workCategoryEnabled()
    categoryOptions.value = resp.data || []
  } catch (e) {
    // 分类拉不到不影响维护渠道本身，保持下拉为空即可
    categoryOptions.value = []
  }
}

onMounted(async () => {
  await Promise.all([loadCategories(), loadDepts()])
  await load()
  lanOrigins.value = await fetchDevLanOrigins()
  if (!lanOrigins.value.length) {
    try {
      const resp = await ticketChannelLanOrigins()
      lanOrigins.value = resp.data || []
    } catch {
      lanOrigins.value = []
    }
  }
  if (!getStoredQrOrigin() && isLoopbackOrigin(location.origin) && lanOrigins.value.length) {
    qrOrigin.value = lanOrigins.value[0]
  }
})

function onQrOriginChange(value) {
  qrOrigin.value = value
  setStoredQrOrigin(value)
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  form.value = emptyForm()
  dialogOpen.value = true
}

function openEdit(row) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  form.value = {
    channelName: row.channelName,
    bizDeptId: row.bizDeptId ?? null,
    defaultCategoryId: row.defaultCategoryId ?? null,
    needPhone: row.needPhone ?? 1,
    dailyLimit: row.dailyLimit ?? 200,
    remark: row.remark || '',
    status: row.status ?? 1
  }
  dialogOpen.value = true
}

async function submit() {
  if (!form.value.channelName.trim()) {
    ElMessage.warning('请填写入口名称')
    return
  }
  if (!form.value.bizDeptId) {
    ElMessage.warning('请选择问题所在科室')
    return
  }
  busy.value = true
  try {
    if (dialogMode.value === 'create') {
      const resp = await ticketChannelCreate(form.value)
      ElMessage.success('已创建，渠道码 ' + (resp.data?.channelCode || ''))
    } else {
      await ticketChannelUpdate(editingId.value, form.value)
      ElMessage.success('已保存')
    }
    dialogOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '提交失败')
  } finally {
    busy.value = false
  }
}

async function toggle(row) {
  const next = row.status === 1 ? 0 : 1
  try {
    await ticketChannelToggle(row.id, next)
    ElMessage.success(next === 1 ? '已启用' : '已停用，该二维码将不能再提交')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

function openQr(row) {
  qrRow.value = row
  qrOpen.value = true
}

const qrUrl = computed(() => (qrRow.value ? mobileUrl(qrRow.value.mobilePath, qrOrigin.value) : ''))
const qrSvg = computed(() => (qrUrl.value ? qrcodeSvg(qrUrl.value, { cellSize: 4, margin: 16 }) : ''))
const qrDensity = computed(() => {
  const n = qrcodeModuleCount(qrUrl.value)
  if (!n) return ''
  // 模块数越大，同样尺寸的标签上每个点越小，贴远墙时要换更大的标签纸
  // 80×60 标签的码槽只有 36mm：29 模块刚好 8 点/模块，再多就要换更大的标签纸
  return n > 29 ? '模块偏密，80×60 标签上会掉到 8 点/模块以下，建议换大一号标签纸' : '适合 80×60mm 标签（码 36mm）'
})

async function copyUrl(row) {
  const url = mobileUrl(row.mobilePath, qrOrigin.value)
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('地址已复制')
  } catch (e) {
    ElMessage.warning('浏览器不允许自动复制，请手动选中地址复制：' + url)
  }
}

/** 勾选的渠道：翻页由 reserve-selection 保留，跨页攒一批一起打 */
const picked = ref([])
const printOpen = ref(false)
const printRows = ref([])
const printCopies = ref(1)

/** 预览只渲染第一枚，用的就是打印文档本身，所见即所得 */
const printPreviewDoc = computed(() => buildLabelDocument(printRows.value.slice(0, 1), qrOrigin.value))
const printPages = computed(() => printRows.value.length * Math.max(1, Math.floor(printCopies.value) || 1))

/** 停用中的入口也允许打印（可能是先打后启用），但要给操作人看一眼 */
const printHasDisabled = computed(() => printRows.value.some((r) => r.status !== 1))

function onSelectionChange(rows) {
  picked.value = rows
}

function openBatchPrint() {
  if (!picked.value.length) {
    ElMessage.warning('先勾选要打印的登记入口')
    return
  }
  printRows.value = [...picked.value]
  printOpen.value = true
}

function doPrint() {
  const resp = openLabelPrint(printRows.value, qrOrigin.value, printCopies.value)
  if (!resp.ok) {
    ElMessage.error(resp.message)
    return
  }
  ElMessage.success(resp.message)
  printOpen.value = false
}

/**
 * 单张打印与批量共用 utils/labelPrint.js 的 80×60 标签模板。
 *
 * 这里原来手拼的是 104mm 宽、二维码 62mm 的 A4 台卡，
 * 放进 80×60 热敏标签会被整张裁掉，所以统一改走标签模板。
 */
function printQr(row) {
  const resp = openLabelPrint([row], qrOrigin.value, 1)
  if (!resp.ok) {
    ElMessage.error(resp.message)
  }
}</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Postcard /></el-icon>
        <div class="title">登记渠道维护</div>
        <div class="hint">一张码绑定一个业务科室，贴到对应科室；信息科统一受理</div>
      </div>
      <div class="tools">
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 120px" @change="load">
          <el-option :value="1" label="启用中" />
          <el-option :value="0" label="已停用" />
        </el-select>
        <el-button :disabled="!picked.length" @click="openBatchPrint">批量打印标签</el-button>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新增登记入口</el-button>
      </div>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="rows" border row-key="id" @selection-change="onSelectionChange">
        <!-- 跨页保留勾选必须配 row-key，否则翻页就丢选择 -->
        <el-table-column type="selection" width="46" reserve-selection />
        <el-table-column prop="channelName" label="入口名称" min-width="140" />
        <el-table-column label="问题所在科室" min-width="140">
          <template #default="{ row }">{{ row.bizDeptName || '未绑定' }}</template>
        </el-table-column>
        <el-table-column prop="channelCode" label="渠道码" width="110" />
        <el-table-column label="默认问题分类" width="150">
          <template #default="{ row }">{{ row.defaultCategoryId ? categoryName(row.defaultCategoryId) : '报修人自选' }}</template>
        </el-table-column>
        <el-table-column label="联系电话" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.needPhone === 1 ? 'warning' : 'info'" effect="plain">
              {{ row.needPhone === 1 ? '必填' : '选填' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dailyLimit" label="每日上限" width="90" align="center" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '启用中' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="张贴位置" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="285" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openQr(row)">二维码</el-button>
            <el-button link @click="printQr(row)">打印</el-button>
            <el-button link @click="copyUrl(row)">复制地址</el-button>
            <el-button link @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.status === 1 ? 'danger' : 'success'" @click="toggle(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有登记入口，先新增一个再打印二维码" />
        </template>
      </el-table>
      <el-pagination
        class="pager"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.size"
        :current-page="query.page"
        @current-change="(p) => { query.page = p; load() }"
      />
    </el-card>

    <el-dialog v-model="dialogOpen" :title="dialogMode === 'create' ? '新增登记入口' : '编辑登记入口'" width="520px">
      <el-form label-position="top" :model="form">
        <el-form-item label="问题所在科室" required>
          <el-select
            v-model="form.bizDeptId"
            filterable
            placeholder="绑定科室管理中的科室，一码一科室"
            style="width: 100%"
            @change="onBizDeptChange"
          >
            <el-option v-for="d in deptFlat" :key="d.id" :value="d.id" :label="d.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="入口名称" required>
          <el-input v-model="form.channelName" maxlength="100" placeholder="例如：内科报修" />
        </el-form-item>
        <el-form-item label="默认问题分类">
          <el-select v-model="form.defaultCategoryId" clearable placeholder="不选则由报修人自己选" style="width: 100%">
            <el-option v-for="c in categoryOptions" :key="c.id" :value="c.id" :label="c.categoryName" />
          </el-select>
        </el-form-item>
        <el-form-item label="联系电话">
          <el-radio-group v-model="form.needPhone">
            <el-radio :value="1">必填</el-radio>
            <el-radio :value="0">选填</el-radio>
          </el-radio-group>
          <span class="tip">
            报修人可填手机号、短号或内线，不限 11 位。<br />
            这里是「本入口单独收紧」：全局开关在 参数配置 → 登记表单 → 联系电话必填，
            两边取更严的一方，所以这里选「选填」不保证真的可以不填。
          </span>
        </el-form-item>
        <el-form-item label="每日提交上限">
          <el-input-number v-model="form.dailyLimit" :min="1" :max="10000" />
          <span class="tip">防刷：超过上限后当天该入口不再接收新登记</span>
        </el-form-item>
        <el-form-item label="张贴位置备注">
          <el-input v-model="form.remark" maxlength="255" placeholder="例如：住院部 A 电梯口" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="qrOpen" title="登记二维码" width="420px">
      <div v-if="qrRow" class="qr">
        <!-- 内容由 qrcode-generator 生成的固定结构 SVG，不含任何用户输入拼接 -->
        <div class="qr-box" v-html="qrSvg" />
        <div class="qr-name">{{ qrRow.channelName }}</div>
        <div v-if="qrRow.bizDeptName" class="qr-url">问题科室：{{ qrRow.bizDeptName }}</div>
        <div class="qr-url">{{ qrUrl }}</div>
        <div class="qr-origin">
          <span class="qr-origin-label">扫码打开的地址</span>
          <el-select
            :model-value="qrOrigin"
            filterable
            allow-create
            default-first-option
            style="width: 100%"
            @change="onQrOriginChange"
          >
            <el-option v-for="o in qrOriginOptions" :key="o" :label="o" :value="o" />
          </el-select>
        </div>
        <div class="qr-tip">{{ qrDensity }}</div>
        <el-alert
          v-if="qrUsesLoopback"
          type="error"
          :closable="false"
          show-icon
          title="当前二维码指向 localhost，手机扫开的是手机自己，打不开本机页面。请改成电脑的局域网 IP（手机与电脑同一 Wi-Fi；Windows 防火墙需放行开发端口）。"
        />
        <el-alert
          v-else
          type="info"
          :closable="false"
          show-icon
          title="二维码里只有渠道码，不含任何凭据；停用该渠道后扫码即失效。本地试用请确保手机能访问上面的局域网地址。"
        />
      </div>
      <template #footer>
        <el-button @click="qrOpen = false">关闭</el-button>
        <el-button @click="copyUrl(qrRow)">复制地址</el-button>
        <el-button type="primary" @click="printQr(qrRow)">打印标签</el-button>
      </template>
    </el-dialog>
    <!-- 批量打印：一次勾选多个入口，一页一枚按顺序走纸 -->
    <el-dialog v-model="printOpen" title="批量打印标签（80×60mm 热敏纸）" width="780px">
      <div class="print-wrap">
        <div class="print-left">
          <div class="print-field">
            <span class="print-label">扫码打开的地址</span>
            <el-select
              :model-value="qrOrigin"
              filterable
              allow-create
              default-first-option
              style="width: 100%"
              @change="onQrOriginChange"
            >
              <el-option v-for="o in qrOriginOptions" :key="o" :label="o" :value="o" />
            </el-select>
          </div>
          <el-alert
            v-if="qrUsesLoopback"
            type="error"
            :closable="false"
            show-icon
            title="当前地址是 localhost，印出去的码手机扫不开，已禁止打印。改成电脑局域网 IP 后再试。"
          />
          <div class="print-field">
            <span class="print-label">每个入口打印几份</span>
            <div class="print-inline">
              <el-input-number v-model="printCopies" :min="1" :max="50" />
              <span class="print-hint">合计 {{ printPages }} 张，走纸 {{ printPages }} 次</span>
            </div>
          </div>
          <el-alert
            v-if="printHasDisabled"
            type="warning"
            :closable="false"
            show-icon
            title="勾选里含已停用入口，扫码会提示入口已停用。确认不是误选后再打印。"
          />
          <div class="print-field">
            <span class="print-label">待打印入口（{{ printRows.length }} 个）</span>
            <div class="print-list">
              <div v-for="r in printRows" :key="r.id" class="print-item">
                <span class="nm">{{ r.channelName }}</span>
                <span class="cd">{{ r.channelCode }}</span>
                <el-tag size="small" :type="r.status === 1 ? 'success' : 'danger'" effect="plain">
                  {{ r.status === 1 ? '启用中' : '已停用' }}
                </el-tag>
              </div>
            </div>
          </div>
          <div class="print-cfg">
            打印机设置（缺一不可，否则尺寸和点阵都会错）：<br />
            ① 驱动里自定义纸张 <b>80mm × 60mm</b>、分辨率 <b>203dpi</b>；<br />
            ② 页边距全部 <b>0</b>，并<b>关闭「适应页面 / 缩放至可打印区域」</b>——一勾缩放，二维码的整数点对齐立刻失效；<br />
            ③ 打印对话框里「纸张大小」也要选 <b>80×60</b>（选 A4 会把标签居中打在 A4 上，白废一张）；
            ④ 关闭<b>页眉页脚</b>，否则标签上会多印一行网址。
          </div>
        </div>
        <div class="print-right">
          <div class="print-label">首张标签预览（1:1）</div>
          <iframe class="print-preview" :srcdoc="printPreviewDoc" scrolling="no" title="标签预览" />
        </div>
      </div>
      <template #footer>
        <el-button @click="printOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!printRows.length || qrUsesLoopback" @click="doPrint">开始打印</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  padding: 18px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.titleWrap {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.titleIcon {
  font-size: 22px;
  color: var(--el-color-primary);
  align-self: center;
}

.title {
  font-size: 18px;
  font-weight: 900;
  color: var(--g-text);
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.tools {
  display: flex;
  gap: 10px;
}

.table-card {
  flex: 1 1 auto;
  min-height: 0;
  border-radius: var(--g-radius-md);
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.tip {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.qr {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.qr-box {
  width: 240px;
  height: 240px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  overflow: hidden;
}

.qr-box :deep(svg) {
  width: 100%;
  height: 100%;
  display: block;
}

.qr-name {
  font-size: 15px;
  font-weight: 800;
}

.qr-url {
  font-size: 12px;
  color: var(--el-text-color-regular);
  word-break: break-all;
  user-select: all;
}

.qr-origin {
  width: 100%;
}

.qr-origin-label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.qr-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* --- 批量打印弹窗 --- */
.print-wrap {
  display: flex;
  gap: 18px;
  align-items: flex-start;
}

.print-left {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.print-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.print-inline {
  display: flex;
  align-items: center;
  gap: 10px;
}

.print-label,
.print-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.print-list {
  max-height: 168px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.print-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-size: 13px;
}

.print-item:last-child {
  border-bottom: 0;
}

.print-item .nm {
  flex: 1 1 auto;
  min-width: 0;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.print-item .cd {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.print-cfg {
  font-size: 12px;
  line-height: 1.9;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  padding: 10px 12px;
  border-radius: 8px;
}

.print-right {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* 预览用 mm 出尺寸，和打印文档同一把尺子 */
.print-preview {
  width: 80mm;
  height: 60mm;
  border: 1px solid var(--el-border-color);
  background: #fff;
}</style>

