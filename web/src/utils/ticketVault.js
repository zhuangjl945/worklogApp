/**
 * 报修人本机的「我的工单」记录。
 * 单号 + 查询密码只存在手机本地：用户不需要注册，本机查进度时也不用重复输密码。
 * 清浏览器数据会丢，届时凭「单号 + 自己设的 6 位密码」照样能查；
 * 真把密码也忘了，只能让科室按单号代查（后端仍存着工单）。
 * accessToken 是本机存储的既有字段名，装的就是那 6 位查询密码，改名会让老记录读不出来。
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
