<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ossPreviewFile } from '../api/work'

const visible = ref(false)
const loading = ref(false)
const kind = ref('')
const src = ref('')
const filename = ref('')

const title = computed(() => filename.value || '附件预览')

function revokeSrc() {
  if (src.value && src.value.startsWith('blob:')) {
    URL.revokeObjectURL(src.value)
  }
  src.value = ''
}

function kindFromMime(type) {
  const t = (type || '').toLowerCase()
  if (t.includes('pdf')) return 'pdf'
  if (t.startsWith('image/')) return 'image'
  if (t.includes('word') || t.includes('officedocument') || t.includes('msword')) return 'office'
  return 'other'
}

function kindFromBytes(bytes) {
  if (!bytes || bytes.length < 4) return ''
  if (bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46) return 'pdf'
  if (bytes[0] === 0xFF && bytes[1] === 0xD8 && bytes[2] === 0xFF) return 'image'
  if (bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4E && bytes[3] === 0x47) return 'image'
  if (bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x38) return 'image'
  if (bytes[0] === 0xD0 && bytes[1] === 0xCF && bytes[2] === 0x11 && bytes[3] === 0xE0) return 'office'
  if (bytes[0] === 0x50 && bytes[1] === 0x4B) return 'office'
  return ''
}

async function detectKind(blob) {
  const fromMime = kindFromMime(blob.type)
  if (fromMime === 'pdf' || fromMime === 'image' || fromMime === 'office') return fromMime
  const buf = await blob.slice(0, 16).arrayBuffer()
  return kindFromBytes(new Uint8Array(buf)) || fromMime
}

async function blobFromError(blob) {
  const text = await blob.text()
  try {
    const json = JSON.parse(text)
    if (json && typeof json.msg === 'string' && json.msg) return json.msg
  } catch {
    /* 非 JSON */
  }
  return text || '打开预览失败'
}

function downloadBlob(blob, name) {
  const u = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = u
  a.download = name || '附件'
  a.click()
  URL.revokeObjectURL(u)
}

async function open(url) {
  if (!url) {
    ElMessage.warning('没有可预览的文件')
    return
  }
  revokeSrc()
  kind.value = ''
  filename.value = ''
  visible.value = true
  loading.value = true
  try {
    const resp = await ossPreviewFile(url)
    const blob = resp.data
    const mime = (blob?.type || '').toLowerCase()
    if (!blob || blob.size === 0) {
      throw new Error('文件为空')
    }
    if (mime.includes('json') || mime.includes('text')) {
      throw new Error(await blobFromError(blob))
    }
    const k = await detectKind(blob)
    kind.value = k
    filename.value = '附件预览'
    if (k !== 'pdf' && k !== 'image') {
      visible.value = false
      if (k === 'office') {
        ElMessage.info('Word 文档无法在浏览器中预览，已开始下载')
      } else {
        ElMessage.info('该文件无法在线预览，已开始下载')
      }
      downloadBlob(blob, k === 'office' ? '附件.docx' : '附件')
      return
    }
    src.value = URL.createObjectURL(blob)
  } catch (e) {
    visible.value = false
    const msg = e?.message || '打开预览失败'
    ElMessage.error(msg === 'Request failed with status code 401' ? '未登录或登录已过期' : msg)
  } finally {
    loading.value = false
  }
}

function openInNewTab() {
  if (src.value) window.open(src.value, '_blank', 'noopener')
}

watch(visible, (v) => {
  if (!v) revokeSrc()
})

defineExpose({ open })
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title"
    class="file-preview-dialog"
    width="92vw"
    top="4vh"
    append-to-body
    @closed="revokeSrc"
  >
    <div v-loading="loading" class="viewer">
      <iframe v-if="kind === 'pdf' && src" class="frame" :src="src" title="PDF 预览" />
      <img v-else-if="kind === 'image' && src" class="photo" :src="src" :alt="title" />
      <div v-else-if="!loading" class="empty">无法预览该文件</div>
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :disabled="!src" @click="openInNewTab">新窗口打开</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.viewer {
  min-height: 72vh;
  background: #1c1917;
  border-radius: 8px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}
.frame {
  width: 100%;
  height: 72vh;
  border: 0;
  background: #fff;
}
.photo {
  max-width: 100%;
  max-height: 72vh;
  object-fit: contain;
}
.empty {
  color: #a8a29e;
  padding: 48px 16px;
}
</style>
