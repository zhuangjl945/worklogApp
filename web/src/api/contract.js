import http from './http'

export async function contractCreate(payload) {
  return await http.post('/contract', payload)
}

export async function contractUpdate(id, payload) {
  return await http.put(`/contract/${id}`, payload)
}

export async function contractUpdateAttachments(id, payload) {
  return await http.put(`/contract/${id}/attachments`, payload)
}

export async function contractDetail(id) {
  return await http.get(`/contract/${id}`)
}

export async function contractPage(params) {
  return await http.get('/contract/list', { params })
}

export async function contractDelete(id) {
  return await http.delete(`/contract/${id}`)
}

export async function contractStart(id) {
  return await http.post(`/contract/${id}/start`)
}

export async function contractRenew(id) {
  return await http.post(`/contract/${id}/renew`)
}

export async function contractComplete(id) {
  return await http.post(`/contract/${id}/complete`)
}

export async function contractTerminate(id) {
  return await http.post(`/contract/${id}/terminate`)
}

export async function contractExpiring(params) {
  return await http.get('/contract/expiring', { params })
}

export async function paymentPlanExpiring(params) {
  return await http.get('/payment-plan/expiring', { params })
}

export async function contractPaymentPlans(contractId) {
  return await http.get(`/contract/${contractId}/payment-plans`)
}

export async function paymentPlanRecord(id, payload) {
  return await http.post(`/payment-plan/${id}/record`, payload)
}

export async function paymentPlanUpdate(id, payload) {
  return await http.put(`/payment-plan/${id}`, payload)
}

export async function contractAddPaymentPlan(contractId, payload) {
  return await http.post(`/contract/${contractId}/payment-plan`, payload)
}