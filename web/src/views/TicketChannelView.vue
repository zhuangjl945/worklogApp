<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Postcard } from '@element-plus/icons-vue'
import {
  ticketChannelCreate,
  ticketChannelPage,
  ticketChannelToggle,
  ticketChannelUpdate
} from '../api/ticket'
import { workCategoryEnabled } from '../api/work'
import { mobileUrl, qrcodeModuleCount, qrcodeSvg } from '../utils/qrcodeSvg'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ page: 1, size: 20, status: null })

const categoryOptions = ref([])

const dialogOpen = ref(false)
const dialogMode = ref('create')
const editingId = ref(null)
const busy = ref(false)
const form = ref(emptyForm())

const qrOpen = ref(false)
const qrRow = ref(null)

function emptyForm() {
  return {
    channelName: '',
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
  await loadCategories()
  await load()
})

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

const qrUrl = computed(() => (qrRow.value ? mobileUrl(qrRow.value.mobilePath) : ''))
const qrSvg = computed(() => (qrUrl.value ? qrcodeSvg(qrUrl.value, { cellSize: 4, margin: 16 }) : ''))
const qrDensity = computed(() => {
  const n = qrcodeModuleCount(qrUrl.value)
  if (!n) return ''
  // 模块数越大，同样尺寸的标签上每个点越小，贴远墙时要换更大的标签纸
  return n > 57 ? '模块较密，建议用 A6 以上标签打印' : '适合 5×5cm 以上标签'
})

async function copyUrl(row) {
  const url = mobileUrl(row.mobilePath)
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('地址已复制')
  } catch (e) {
    ElMessage.warning('浏览器不允许自动复制，请手动选中地址复制：' + url)
  }
}

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 打印墙面标签。
 *
 * 单独开一个窗口打印而不是在本页打遮罩：本页是 Element Plus 布局，
 * 用 @media print 精确排除所有其它节点太脆，容易连侧边栏一起打出来。
 */
function printQr(row) {
  const url = mobileUrl(row.mobilePath)
  const svg = qrcodeSvg(url, { cellSize: 8, margin: 24, title: row.channelName })
  const win = window.open('', '_blank', 'width=420,height=560')
  if (!win) {
    ElMessage.warning('浏览器拦截了新窗口，请允许弹窗后重试')
    return
  }
  win.document.write(
    '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>' +
      escapeHtml(row.channelName) +
      '</title><style>' +
      'html,body{margin:0;padding:0;background:#fff}' +
      '.card{width:88mm;margin:12mm auto;padding:8mm;border:1px dashed #bbb;text-align:center;font-family:-apple-system,"Microsoft YaHei",sans-serif;page-break-inside:avoid}' +
      '.card svg{width:62mm;height:62mm}' +
      'h1{font-size:18px;margin:0 0 6px}' +
      'p{font-size:13px;margin:4px 0;color:#333}' +
      '.u{font-size:10px;color:#666;word-break:break-all;margin-top:8px}' +
      '</style></head><body><div class="card">' +
      '<h1>' + escapeHtml(row.channelName) + '</h1>' +
      '<p>手机扫码登记问题 · 无需登录</p>' +
      svg +
      '<p class="u">' + escapeHtml(url) + '</p>' +
      '</div></body></html>'
  )
  win.document.close()
  win.focus()
  // 等 SVG 完成排版再触发，否则部分浏览器会打出空白页
  setTimeout(() => win.print(), 300)
}
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="titleWrap">
        <el-icon class="titleIcon"><Postcard /></el-icon>
        <div class="title">登记渠道维护</div>
        <div class="hint">一个渠道一张二维码，贴在不同楼层或科室门口，报修人扫码即可登记</div>
      </div>
      <div class="tools">
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 120px" @change="load">
          <el-option :value="1" label="启用中" />
          <el-option :value="0" label="已停用" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新增登记入口</el-button>
      </div>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="rows" border>
        <el-table-column prop="channelName" label="入口名称" min-width="140" />
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
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openQr(row)">二维码</el-button>
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
        <el-form-item label="入口名称" required>
          <el-input v-model="form.channelName" maxlength="100" placeholder="例如：门诊楼 3 层水电报修" />
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
        <div class="qr-url">{{ qrUrl }}</div>
        <div class="qr-tip">{{ qrDensity }}</div>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="二维码里只有渠道码，不含任何凭据；停用该渠道后扫码即失效，历史工单不受影响。"
        />
      </div>
      <template #footer>
        <el-button @click="qrOpen = false">关闭</el-button>
        <el-button @click="copyUrl(qrRow)">复制地址</el-button>
        <el-button type="primary" @click="printQr(qrRow)">打印标签</el-button>
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
  color: #111827;
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
  border-radius: 14px;
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

.qr-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
