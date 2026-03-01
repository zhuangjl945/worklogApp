import http from './http'

export async function supplierCreate(payload) {
  return await http.post('/supplier', payload)
}

export async function supplierUpdate(id, payload) {
  return await http.put(`/supplier/${id}`, payload)
}

export async function supplierDetail(id) {
  return await http.get(`/supplier/${id}`)
}

export async function supplierPage(params) {
  return await http.get('/supplier/list', { params })
}

export async function supplierDisable(id) {
  return await http.post(`/supplier/${id}/disable`)
}

export async function supplierEnable(id) {
  return await http.post(`/supplier/${id}/enable`)
}

export async function supplierDelete(id) {
  return await http.delete(`/supplier/${id}`)
}

export async function supplierNextCode() {
  return await http.get('/supplier/next-code')
}

export async function supplierContacts(id) {
  return await http.get(`/supplier/${id}/contacts`)
}

export async function supplierAddContact(id, payload) {
  return await http.post(`/supplier/${id}/contacts`, payload)
}

export async function supplierDeleteContact(id, cid) {
  return await http.delete(`/supplier/${id}/contacts/${cid}`)
}
