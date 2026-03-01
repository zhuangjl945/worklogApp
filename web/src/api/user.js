import http from './http'

export async function userPage(params) {
  return await http.get('/users', { params })
}

export async function userCount(params) {
  return await http.get('/users/count', { params })
}

export async function userCreate(payload) {
  return await http.post('/users', payload)
}

export async function userUpdatePassword(id, newPassword) {
  return await http.put(`/users/${id}/password`, { newPassword })
}

export async function userUpdateStatus(id, status) {
  return await http.put(`/users/${id}/status`, { status })
}

export async function userMyDept() {
  return await http.get('/users/my-dept')
}
