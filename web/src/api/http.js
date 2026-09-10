import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

/** 视为「权限不足」的返回码：403 来自角色拦截器，40003 来自业务层数据范围判定 */
const FORBIDDEN_CODES = new Set([403, 40003])
const notifiedAt = new Map()

/** 同一条权限提示 3 秒内只弹一次，避免列表/轮询多请求同时失败刷屏 */
function notifyForbidden(msg) {
  const now = Date.now()
  if (now - (notifiedAt.get(msg) || 0) < 3000) return
  notifiedAt.set(msg, now)
  ElMessage.error(msg)
}

http.interceptors.request.use((config) => {
  // 登录接口不携带 Token
  if (config.url === '/auth/login' || config.url === 'auth/login') {
    return config
  }
  const auth = localStorage.getItem('access_token')
  if (auth) {
    config.headers = config.headers ?? {}
    config.headers.Authorization = auth
  }
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data ?? {}
    if (typeof body.code !== 'number') {
      return resp
    }

    if (body.code === 0) {
      return body
    }

    if (body.code === 401) {
      localStorage.removeItem('access_token')
      if (!location.pathname.startsWith('/login')) {
        const redirect = encodeURIComponent(location.pathname + location.search)
        window.location.href = `/login?redirect=${redirect}`
      }
    }

    // 角色门槛（403 / 业务码 40003）统一提示一次：
    // 不少调用点是静默 catch 的（看板切范围、后台轮询），不提示的话按钮就像坏了
    if (FORBIDDEN_CODES.has(body.code)) {
      notifyForbidden(body.msg || '当前角色无权执行该操作')
    }

    const err = new Error(body.msg || '请求失败')
    err.code = body.code
    return Promise.reject(err)
  },
  (error) => Promise.reject(error)
)

export default http
