import http from './http'

// --- 受理台（需登录） ---
export async function ticketStatuses() {
  return await http.get('/tickets/statuses')
}

export async function ticketPage(params) {
  return await http.get('/tickets', { params })
}

export async function ticketPendingCount() {
  return await http.get('/tickets/pending-count')
}

export async function ticketDetail(id) {
  return await http.get(`/tickets/${id}`)
}

export async function ticketAccept(id) {
  return await http.post(`/tickets/${id}/accept`)
}

export async function ticketAssign(id, payload) {
  return await http.post(`/tickets/${id}/assign`, payload)
}

export async function ticketReply(id, payload) {
  return await http.post(`/tickets/${id}/reply`, payload)
}

export async function ticketDone(id, remark) {
  return await http.post(`/tickets/${id}/done`, { remark })
}

export async function ticketReject(id, reason) {
  return await http.post(`/tickets/${id}/reject`, { reason })
}

export async function ticketClose(id) {
  return await http.post(`/tickets/${id}/close`)
}

export async function ticketToRecord(id) {
  return await http.post(`/tickets/${id}/to-record`)
}

// --- 登记渠道 ---
export async function ticketChannelPage(params) {
  return await http.get('/ticket-channels', { params })
}

export async function ticketChannelCreate(payload) {
  return await http.post('/ticket-channels', payload)
}

export async function ticketChannelUpdate(id, payload) {
  return await http.put(`/ticket-channels/${id}`, payload)
}

export async function ticketChannelToggle(id, status) {
  return await http.post(`/ticket-channels/${id}/status`, null, { params: { status } })
}

export async function ticketChannelQrcode(id) {
  return await http.get(`/ticket-channels/${id}/qrcode`)
}
