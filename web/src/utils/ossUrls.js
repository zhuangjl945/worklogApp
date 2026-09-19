/**
 * OSS 地址/key 的纯函数。单独成文件，避免单测被 uploadToOss 的 API 依赖拖下水。
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

/** 原来有、现在没有的地址：保存成功后再拿去删 OSS */
export function removedUrls(original, current) {
  const kept = new Set(current || [])
  return (original || []).filter((u) => u && !kept.has(u))
}
