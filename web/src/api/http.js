import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

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

   /*  if (body.code === 401) {
      localStorage.removeItem('access_token')
      if (!location.pathname.startsWith('/login')) {
        const redirect = encodeURIComponent(location.pathname + location.search)
        window.location.href = `/login?redirect=${redirect}`
      }
    } */

    return Promise.reject(new Error(body.msg || '请求失败'))
  },
  (error) => Promise.reject(error)
)

export default http
