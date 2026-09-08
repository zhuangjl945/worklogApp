import http from '../api/http'

/**
 * 管理端（受理台）取工单附图。
 *
 * 为什么不能像工作记录那样直接把地址塞进 <img src>：
 * 1) 工单存的是 objectKey，不是可访问 URL；
 * 2) 图片必须走 /api/tickets/{id}/image 这条后端代理，它会校验「登录态 + 同科室 + 图片确实属于该工单」，
 *    而 /api/oss/* 那条路要求入参是完整 URL 并校验 bucket 域名，语义不一样；
 * 3) 不依赖 bucket 是否公开读，将来 bucket 收敛成私有读这里不用改。
 *
 * 取回来的是二进制，用 createObjectURL 变成浏览器可渲染的本地地址。
 */

// 已解析的图片：`${工单id}::${objectKey}` -> objectURL。同一张图多处复用，只请求一次。
const resolved = new Map()
// 正在请求中的图片，用于合并并发重复请求
const inflight = new Map()
// 请求失败过的图片，避免模板重渲染时无限重试打后端
const failed = new Set()
// 按加入顺序记录，超出上限时回收最早的 objectURL，防止长驻页面内存只涨不降
const order = []
const MAX_KEEP = 200

function cacheKey(ticketId, objectKey) {
  return `${ticketId}::${objectKey}`
}

function remember(key, url) {
  resolved.set(key, url)
  order.push(key)
  if (order.length > MAX_KEEP) {
    const evict = order.shift()
    const old = resolved.get(evict)
    if (old) {
      URL.revokeObjectURL(old)
    }
    resolved.delete(evict)
    failed.delete(evict)
  }
}

/**
 * 异步取一张图，返回浏览器可用的 objectURL；失败返回空串。
 */
export async function fetchTicketImage(ticketId, objectKey) {
  if (!ticketId || !objectKey) {
    return ''
  }
  const key = cacheKey(ticketId, objectKey)
  const hit = resolved.get(key)
  if (hit) {
    return hit
  }
  if (failed.has(key)) {
    return ''
  }
  if (inflight.has(key)) {
    return await inflight.get(key)
  }
  const task = (async () => {
    try {
      // 拦截器对非 {code} 结构会原样放行 axios 响应，所以这里拿的是 response 而不是 body
      const resp = await http.get(`/tickets/${ticketId}/image`, {
        params: { key: objectKey },
        responseType: 'blob',
        timeout: 20000
      })
      const blob = resp.data
      // 后端报错时返回的是 JSON，被 responseType=blob 包成 Blob，直接渲染会得到一张裂图
      if (!blob || (blob.type || '').indexOf('image') < 0) {
        failed.add(key)
        return ''
      }
      const url = URL.createObjectURL(blob)
      remember(key, url)
      return url
    } catch (e) {
      failed.add(key)
      return ''
    } finally {
      inflight.delete(key)
    }
  })()
  inflight.set(key, task)
  return await task
}

/**
 * 同步读缓存，配合 ticketImageWatch 使用：缓存里没有就先占位并触发一次异步加载。
 */
export function ticketImageSrc(ticketId, objectKey) {
  const url = resolved.get(cacheKey(ticketId, objectKey))
  return url || ''
}

/** 该 key 是否已经确定取不到（用于显示占位而不是永久空白） */
export function ticketImageFailed(ticketId, objectKey) {
  return failed.has(cacheKey(ticketId, objectKey))
}

/** 清空缓存并回收 objectURL，切换工单时调用，避免旧图串到新工单上 */
export function clearTicketImages() {
  resolved.forEach((url) => URL.revokeObjectURL(url))
  resolved.clear()
  inflight.clear()
  failed.clear()
  order.length = 0
}

/** 主动回收单张，报修人补充图片后刷新旧占位时用 */
export function dropTicketImage(ticketId, objectKey) {
  const key = cacheKey(ticketId, objectKey)
  const url = resolved.get(key)
  if (url) {
    URL.revokeObjectURL(url)
    resolved.delete(key)
    const at = order.indexOf(key)
    if (at >= 0) {
      order.splice(at, 1)
    }
  }
  failed.delete(key)
}
