import http from './http'

export async function deptTree() {
  return await http.get('/depts/tree')
}

export async function deptCreate(payload) {
  return await http.post('/depts', payload)
}

export async function deptUpdate(id, payload) {
  return await http.put(`/depts/${id}`, payload)
}

export async function deptUpdateStatus(id, status) {
  return await http.put(`/depts/${id}/status`, { status })
}

export async function deptDelete(id) {
  return await http.delete(`/depts/${id}`)
}

export async function deptMyRootChildren() {
  return await http.get('/depts/my-root-children')
}

export async function deptAllEnabled() {
  const resp = await http.get('/depts', { params: { page: 1, size: 5000, status: 1 } })
  return { data: resp?.data?.records || [] }
}