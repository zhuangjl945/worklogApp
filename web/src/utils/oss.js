import { ossPolicy } from '../api/work'

export async function uploadToOss(file, dir) {
  const policyResp = await ossPolicy({
    dir,
    filename: file?.name,
    contentType: file?.type || undefined
  })
  const p = policyResp.data
  const formData = new FormData()
  formData.append('key', p.key)
  formData.append('policy', p.policy)
  formData.append('OSSAccessKeyId', p.accessKeyId)
  formData.append('signature', p.signature)
  formData.append('success_action_status', '200')
  if (p.contentType) formData.append('Content-Type', p.contentType)
  if (p.contentDisposition) formData.append('Content-Disposition', p.contentDisposition)
  formData.append('file', file)

  const resp = await fetch(p.host, { method: 'POST', body: formData })
  if (!resp.ok) throw new Error(file?.name ? `文件 ${file.name} 上传失败` : '上传失败')
  return p.url
}

export function parseFileUrlList(raw) {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    if (Array.isArray(parsed)) return parsed.filter(Boolean)
    if (typeof parsed === 'string' && parsed) return [parsed]
  } catch {
    /* 兼容历史单 URL */
  }
  return String(raw).split(',').map((s) => s.trim()).filter(Boolean)
}
