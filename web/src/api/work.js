import http from './http'

export async function workCategoryEnabled() {
  return await http.get('/work/categories/enabled')
}

export async function workCategoryPage(params) {
  return await http.get('/work/categories', { params })
}

export async function workCategoryCreate(payload) {
  return await http.post('/work/categories', payload)
}

export async function workCategoryUpdate(id, payload) {
  return await http.put(`/work/categories/${id}`, payload)
}

export async function workCategoryDelete(id) {
  return await http.delete(`/work/categories/${id}`)
}

export async function workStatusEnabled() {
  return await http.get('/work/statuses/enabled')
}

export async function workRecordPage(params) {
  return await http.get('/work/records', { params })
}

export async function workRecordStatsForReport(params) {
  return await http.get('/work/records/stats/report/by-category', { params })
}

export async function workRecordStatsForDashboard(params) {
  return await http.get('/work/records/stats/dashboard/by-category', { params })
}

export async function workRecordStatsByExpenseType(params) {
  return await http.get('/work/records/stats/by-expense-type', { params })
}

export async function workRecordStatsUserDeptCategory(params) {
  return await http.get('/work/records/stats/report/user-dept-category', { params })
}

export async function workRecordCreate(payload) {
  return await http.post('/work/records', payload)
}

export async function workRecordUpdate(id, payload) {
  return await http.put(`/work/records/${id}`, payload)
}

export async function workRecordUpdateStatus(id, statusId) {
  return await http.put(`/work/records/${id}/status`, { statusId })
}

export async function workRecordDetail(id) {
  return await http.get(`/work/records/${id}`)
}

export async function workRecordDelete(id) {
  return await http.delete(`/work/records/${id}`)
}

export async function getWorkRecordLogs(recordId) {
  return await http.get(`/work/records/${recordId}/logs`)
}

export async function addWorkRecordLog(recordId, logContent) {
  return await http.post(`/work/records/${recordId}/logs`, { logContent })
}

export async function updateWorkRecordLog(recordId, logId, logContent) {
  return await http.put(`/work/records/${recordId}/logs/${logId}`, { logContent })
}

export async function deleteWorkRecordLog(recordId, logId) {
  return await http.delete(`/work/records/${recordId}/logs/${logId}`)
}

export async function getWorkRecordExpenses(recordId) {
  return await http.get(`/work/records/${recordId}/expenses`)
}

export async function addWorkRecordExpense(recordId, payload) {
  return await http.post(`/work/records/${recordId}/expenses`, payload)
}

export async function updateWorkRecordExpense(recordId, expenseId, payload) {
  return await http.put(`/work/records/${recordId}/expenses/${expenseId}`, payload)
}

export async function deleteWorkRecordExpense(recordId, expenseId) {
  return await http.delete(`/work/records/${recordId}/expenses/${expenseId}`)
}

// --- work record transfer ---
export async function workRecordTransferCreate(recordId, payload) {
  return await http.post(`/work/transfers/records/${recordId}`, payload)
}

export async function workRecordTransferPending(params) {
  return await http.get('/work/transfers/pending', { params })
}

export async function workRecordTransferInitiated(params) {
  return await http.get('/work/transfers/initiated', { params })
}

export async function workRecordTransferAccept(id) {
  return await http.post(`/work/transfers/${id}/accept`)
}

export async function workRecordTransferReject(id, payload) {
  return await http.post(`/work/transfers/${id}/reject`, payload)
}

// --- OSS ---
export async function ossPolicy(params) {
  return await http.get('/oss/policy', { params })
}

export async function ossDeleteObject(params) {
  return await http.delete('/oss/object', { params })
}
