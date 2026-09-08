/**
 * 报修人本机的「我的工单」记录。
 * 单号 + 查询令牌只存在手机本地：用户不需要注册，也不需要记住那串密码。
 * 清浏览器数据会丢，届时可让科室按单号代查（后端仍存着工单）。
 */
const KEY = 'my_tickets_v1'
const MAX_KEEP = 30

function readAll() {
  try {
    const raw = localStorage.getItem(KEY)
    const list = raw ? JSON.parse(raw) : []
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
}

function writeAll(list) {
  try {
    localStorage.setItem(KEY, JSON.stringify(list.slice(0, MAX_KEEP)))
  } catch {
    /* 隐私模式下写入会抛错，忽略即可 */
  }
}

export function saveTicket(ticketNo, accessToken, title) {
  if (!ticketNo || !accessToken) return
  const list = readAll().filter((t) => t.ticketNo !== ticketNo)
  list.unshift({ ticketNo, accessToken, title: title || '', savedAt: Date.now() })
  writeAll(list)
}

export function listTickets() {
  return readAll()
}

export function getAccessToken(ticketNo) {
  if (!ticketNo) return ''
  const hit = readAll().find((t) => t.ticketNo === String(ticketNo).toUpperCase())
  return hit ? hit.accessToken : ''
}

export function forgetTicket(ticketNo) {
  writeAll(readAll().filter((t) => t.ticketNo !== ticketNo))
}
