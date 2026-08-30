<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ossPreviewUrl } from '../api/work'

const visible = ref(false)
const loading = ref(false)
const kind = ref('')
const src = ref('')
const filename = ref('')
const previewable = ref(false)

const title = computed(() => filename.value || '附件预览')

async function open(url) {
  if (!url) {
    ElMessage.warning('没有可预览的文件')
    return
  }
  loading.value = true
  kind.value = ''
  src.value = ''
  filename.value = ''
  previewable.value = false
  visible.value = true
  try {
    const resp = await ossPreviewUrl({ url })
    const d = resp.data || {}
    kind.value = d.kind || 'other'
    src.value = d.url || ''
    filename.value = d.filename || ''
    previewable.value = !!d.previewable
    if (!d.previewable) {
      visible.value = false
      if (d.kind === 'office') {
        ElMessage.info('Word 文档无法在浏览器中预览，已开始下载')
      } else {
        ElMessage.info('该文件无法在线预览，已开始下载')
      }
      if (d.url) window.open(d.url, '_blank', 'noopener')
    }
  } catch (e) {
    visible.value = false
    ElMessage.error(e?.message || '打开预览失败')
  } finally {
    loading.value = false
  }
}

function openInNewTab() {
  if (src.value) window.open(src.value, '_blank', 'noopener')
}

defineExpose({ open })
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title"
    class="file-preview-dialog"
    width="92vw"
    top="4vh"
    destroy-on-close
    append-to-body
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
