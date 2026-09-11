import { ossPolicy } from '../api/work'

/**
 * 与后端 OssKeys.toKey 对齐：完整地址 / 签名地址 / objectKey 都能还原出要删的 key。
 */
export function toOssKey(urlOrKey) {
  if (!urlOrKey) return ''
  const v = String(urlOrKey).trim()
  if (!v) return ''
  const scheme = v.indexOf('://')
  let path = v
  if (scheme >= 0) {
    const slash = v.indexOf('/', scheme + 3)
    if (slash < 0) return ''
    path = v.slice(slash + 1)
  }
  const query = path.indexOf('?')
  if (query >= 0) path = path.slice(0, query)
  while (path.startsWith('/')) path = path.slice(1)
  return path
}

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
