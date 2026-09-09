import { reactive } from 'vue'
import { ossSignUrls } from '../api/oss'

/**
 * bucket 收敛成私有读之后，前端不能再把 https://bucket.host/key 直接绑到 <img src>。
 *
 * 为什么是「短时签名地址」而不是「同源代理 + blob」：
 * 1) 列表页一屏几十张图，代理方案要把每个对象整体读进服务端堆内存（现有 /api/oss/file 就是 readAllBytes），
 *    图片还行，几十 MB 的合同扫描件就是内存事故；签名方案一个字节都不过服务器。
 * 2) 图片流量不占应用服务器带宽，浏览器还能按签名 URL 自身命中缓存。
 * 3) 附件的「点开预览」继续走 FilePreviewDialog 的同源代理，两条路各管各的场景。
 *
 * 迁移友好：签名接口不可用（后端未升级、OSS 未配置、网络抖动）时退回原始地址，
 * 因此公开读 / 私有读两种状态下都不会整屏裂图。
 */

/** 签名地址提前多少秒刷新，避免边界上正在渲染的图突然过期 */
const RENEW_AHEAD_MS = 30 * 1000
/** 失败或越权时的静默期：这段时间内认它是「已知结果」，不再重复请求 */
const NEGATIVE_TTL_MS = 60 * 1000

// input(完整地址或 objectKey) -> 签名地址；'' 表示后端确认取不到
const resolved = reactive({})
// input -> 过期时间戳。与 resolved 分开存，因为 0 和 '' 都是有意义的值
const deadline = new Map()

const waiting = new Set()
let flushTimer = null

/** 已经问过且还在有效期内 */
function isSettled(input) {
  return Object.prototype.hasOwnProperty.call(resolved, input) && (deadline.get(input) || 0) > Date.now()
}

/**
 * 登记需要换地址的对象。同一 tick 内的多次调用合并成一次请求，
 * 列表页 20 行图片因此只花一个 XHR，而不是 20 个。
 */
export function ensureSignedUrls(inputs) {
  let added = false
  for (const u of inputs || []) {
    if (typeof u !== 'string' || !u || isSettled(u)) continue
    waiting.add(u)
    added = true
  }
  if (added && !flushTimer) {
    flushTimer = setTimeout(flush, 0)
  }
}

async function flush() {
  flushTimer = null
  const batch = [...waiting]
  waiting.clear()
  if (!batch.length) return
  try {
    const resp = await ossSignUrls(batch)
    const ttlMs = (resp.data?.ttlSeconds || 300) * 1000
    const urls = resp.data?.urls || {}
    for (const input of batch) {
      const url = urls[input]
      // null/缺项 = 后端判定越权或地址非法，按「已知取不到」记一笔，别死循环重试
      resolved[input] = url || ''
      deadline.set(input, Date.now() + (url ? Math.max(ttlMs - RENEW_AHEAD_MS, RENEW_AHEAD_MS) : NEGATIVE_TTL_MS))
    }
  } catch (e) {
    // 换不到就暂时按原始地址渲染（公开读环境下照样能看图），静默期后自动重试
    for (const input of batch) {
      resolved[input] = input
      deadline.set(input, Date.now() + NEGATIVE_TTL_MS)
    }
  }
}

/** 单张图的渲染地址；没签出来时返回空串，由 el-image 的占位显示 */
export function signedUrl(input) {
  if (!input) return ''
  if (!isSettled(input)) {
    ensureSignedUrls([input])
    return ''
  }
  return resolved[input]
}

/**
 * 预览大图用的列表。
 *
 * <p>这里绝不能 filter(Boolean)：模板里 :initial-index 是拿原始数组的 indexOf 算的，
 * 一旦过滤，位置就错位，用户点第 3 张会打开第 2 张。
 */
export function signedList(inputs) {
  return (inputs || []).map((u) => signedUrl(u))
}
