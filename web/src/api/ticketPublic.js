import axios from 'axios'

/**
 * 手机端（报修人）专用请求实例。
 *
 * 刻意不复用 api/http.js：那个实例会自动附带管理员的 Authorization，
 * 并且一旦收到 401 就清 token 并跳转 /login —— 报修人没有账号，
 * 复用会让他在查进度时被踢进管理员登录页，还会把管理员令牌带给公开接口。
 */
const publicHttp = axios.create({
  baseURL: '/api/public/tickets',
  timeout: 20000
})

publicHttp.interceptors.response.use(
  (resp) => {
    const body = resp.data ?? {}
    if (typeof body.code !== 'number') return resp
    if (body.code === 0) return body
    return Promise.reject(new Error(body.msg || '提交失败，请稍后重试'))
  },
  (error) => Promise.reject(new Error(error?.message || '网络异常，请检查信号后重试'))
)

/** 换取一次性填表凭证 */
export async function fetchFormToken(channelCode) {
  return await publicHttp.post('/form-token', { channelCode })
}

/** 表单配置：渠道名、问题类型、紧急度 */
export async function fetchMeta(formToken) {
  return await publicHttp.get('/meta', { params: { formToken } })
}

/** 图片直传签名（只允许图片、5MB、锁在本渠道目录） */
export async function fetchUploadPolicy(formToken, filename) {
  return await publicHttp.post('/upload-policy', { formToken, filename })
}

/** 提交问题 */
export async function submitTicket(payload) {
  // 必须打到 /api/public/tickets（无尾斜杠）。axios 的 post('/') 会变成
  // /api/public/tickets/，Spring Boot 3 默认不匹配尾斜杠，请求会落到静态资源
  // 并返回「No static resource api/public/tickets」，手机端表现为提交失败。
  return await publicHttp.post('', payload)
}

function authHeader(accessToken) {
  return { 'X-Ticket-Auth': accessToken || '' }
}

/** 凭单号 + 查询密码查进度；请求头名沿用 X-Ticket-Auth，值就是那 6 位查询密码 */
export async function fetchTicketDetail(ticketNo, accessToken) {
  return await publicHttp.get(`/${encodeURIComponent(ticketNo)}`, { headers: authHeader(accessToken) })
}

export async function reporterReply(ticketNo, accessToken, payload) {
  return await publicHttp.post(`/${encodeURIComponent(ticketNo)}/reply`, payload, { headers: authHeader(accessToken) })
}

export async function reporterConfirm(ticketNo, accessToken) {
  return await publicHttp.post(`/${encodeURIComponent(ticketNo)}/confirm`, null, { headers: authHeader(accessToken) })
}

export async function reporterReopen(ticketNo, accessToken, reason) {
  return await publicHttp.post(`/${encodeURIComponent(ticketNo)}/reopen`, { reason }, { headers: authHeader(accessToken) })
}

export async function reporterRate(ticketNo, accessToken, payload) {
  return await publicHttp.post(`/${encodeURIComponent(ticketNo)}/rate`, payload, { headers: authHeader(accessToken) })
}

/**
 * 按凭证取回图片并转成 objectURL。
 *
 * 之所以不能让 <img src> 直接指向后端：授权信息在 X-Ticket-Auth 头里，
 * img 标签发不出这个头；而把令牌塞进 URL 又会落进浏览器历史、代理日志和 Referer。
 * 因此一律用 XHR 取二进制再交给 img，代价是每张图多一次请求，换来的是令牌不外泄。
 */
export async function loadTicketImage(ticketNo, accessToken, key) {
  const resp = await publicHttp.get(`/${encodeURIComponent(ticketNo)}/image`, {
    params: { key },
    headers: authHeader(accessToken),
    responseType: 'blob'
  })
  // responseType=blob 时后端返回的是原始字节，不走上面的拦截器
  return URL.createObjectURL(resp.data ?? resp)
}

/** 直传到 OSS，结构与 utils/oss.js 一致 */
export async function uploadTicketImage(formToken, file) {
  const resp = await fetchUploadPolicy(formToken, file?.name)
  const p = resp.data
  const fd = new FormData()
  fd.append('key', p.key)
  fd.append('policy', p.policy)
  fd.append('OSSAccessKeyId', p.accessKeyId)
  fd.append('signature', p.signature)
  fd.append('success_action_status', '200')
  if (p.contentType) fd.append('Content-Type', p.contentType)
  fd.append('file', file, file?.name || 'image.jpg')

  const r = await fetch(p.host, { method: 'POST', body: fd })
  if (!r.ok) throw new Error(`图片 ${file?.name || ''} 上传失败`)
  return p.key
}

export default publicHttp
