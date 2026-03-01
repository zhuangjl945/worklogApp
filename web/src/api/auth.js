import http from './http'

export async function login(username, password) {
  return await http.post('/auth/login', { username, password })
}

export async function me() {
  return await http.get('/auth/me')
}
