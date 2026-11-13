import http from './http'

export async function fetchCaptcha() {
  return await http.get('/auth/captcha')
}

export async function login(username, password, captchaId, captchaCode) {
  return await http.post('/auth/login', { username, password, captchaId, captchaCode })
}

export async function me() {
  return await http.get('/auth/me')
}
