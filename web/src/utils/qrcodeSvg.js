import qrcode from '../vendor/qrcode-generator.js'

/**
 * 生成登记入口的二维码（纯本地计算，不请求任何外部服务）。
 *
 * 为什么必须本地生成：渠道链接指向的是内部报修入口，
 * 交给第三方在线二维码接口等于把内网地址和使用节奏发给外部服务，离线部署时也会直接失效。
 *
 * 为什么输出 SVG 而不是 PNG：二维码最终多半要打印贴墙，
 * 矢量图放大到 A4 标签也不会有锯齿，扫描件号率明显更高。
 */

/** 容错级别取 M（约 15% 恢复能力）：贴墙的码会被磨损、贴胶带，S/L 要么不耐脏要么撑大版本 */
const EC_LEVEL = 'M'

/**
 * @param text 要编码的完整地址
 * @param opts.cellSize 单个模块的像素基准，默认 4
 * @param opts.margin 留白（quiet zone），默认 16，扫码要求四周至少 4 个模块
 * @param opts.title 无障碍标题
 * @returns {string} 可直接 v-html 的 <svg> 片段；入参为空时返回空串
 */
export function qrcodeSvg(text, opts = {}) {
  const value = (text || '').trim()
  if (!value) {
    return ''
  }
  // typeNumber=0 表示由库按内容长度自动选版本，避免以后加参数把码撑出容量还生成失败
  const qr = qrcode(0, EC_LEVEL)
  qr.addData(value, 'Byte')
  qr.make()
  return qr.createSvgTag({
    cellSize: opts.cellSize || 4,
    margin: opts.margin == null ? 16 : opts.margin,
    // scalable=true 才会省略 width/height，交给外层容器控制尺寸，打印时才能铺满标签
    scalable: true,
    title: opts.title || value,
    alt: opts.title || value
  })
}

/** 二维码对应的模块数，用来提示「内容太长，打印时要把标签放大」 */
export function qrcodeModuleCount(text) {
  const value = (text || '').trim()
  if (!value) {
    return 0
  }
  const qr = qrcode(0, EC_LEVEL)
  qr.addData(value, 'Byte')
  qr.make()
  return qr.getModuleCount()
}

/**
 * 热敏标签机的物理点距：203dpi 机型 1mm 正好 8 个点（300dpi 是 12）。
 * 买机器常见的 203dpi 占绝大多数，所以按 8 算。
 */
export const DOTS_PER_MM = 8

/**
 * 生成「打印专用」二维码：边长必须落在整数个物理点上。
 *
 * 为什么不能复用 qrcodeSvg()：那个函数输出的是像素语义的 cellSize，
 * 交给标签机驱动后会被按 203dpi 重新栅格化，29 个模块摊到 43.5mm 上
 * 每个模块是 11.09 个点，驱动只能靠抖动凑，模块边缘出现灰阶，
 * 扫码枪和老手机摄像头识别率会明显下降。这里反过来先算整数点、再倒推毫米边长，
 * 保证每个模块正好占整数个点，打出来是硬边缘。
 *
 * 取「不超过槽位的最大整数点」：宁可小一点也不溢出标签。
 * 以后扫码地址换成 33 模块的长域名时，36mm 槽会自动降到 33mm 而不是撑破标签。
 *
 * @param text 要编码的完整地址
 * @param slotMm 版式给二维码预留的最大边长（不含静区）
 * @returns {{svg:string,sizeMm:number,moduleCount:number,dotsPerModule:number}|null} 入参为空返回 null
 */
export function qrcodeLabelSvg(text, slotMm) {
  const value = (text || '').trim()
  if (!value) {
    return null
  }
  const qr = qrcode(0, EC_LEVEL)
  qr.addData(value, 'Byte')
  qr.make()
  const n = qr.getModuleCount()
  const dots = Math.max(1, Math.floor((slotMm * DOTS_PER_MM) / n))
  const sizeMm = (dots * n) / DOTS_PER_MM
  const cell = sizeMm / n
  // 同一行连续的暗模块合并成一个 rect：一张码从 841 个矩形降到 216 个，打印窗口不卡
  let rects = ''
  for (let r = 0; r < n; r++) {
    let c = 0
    while (c < n) {
      if (!qr.isDark(r, c)) {
        c++
        continue
      }
      let e = c
      while (e < n && qr.isDark(r, e)) e++
      rects += `<rect x="${(c * cell).toFixed(3)}" y="${(r * cell).toFixed(3)}" width="${((e - c) * cell).toFixed(3)}" height="${cell.toFixed(3)}"/>`
      c = e
    }
  }
  // shape-rendering:crispEdges 禁止浏览器对矩形做抗锯齿，否则屏幕上看着就已经发灰
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${sizeMm} ${sizeMm}" ` +
    `style="width:${sizeMm}mm;height:${sizeMm}mm;shape-rendering:crispEdges" fill="#000" ` +
    `role="img" aria-label="${escAttr(value)}">${rects}</svg>`
  return { svg, sizeMm, moduleCount: n, dotsPerModule: dots }
}

function escAttr(s) {
  return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;')
}
const QR_ORIGIN_KEY = 'worklog.ticketQrOrigin'

export function isLoopbackHost(hostname) {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]'
}

export function isLoopbackOrigin(origin = location.origin) {
  try {
    return isLoopbackHost(new URL(origin).hostname)
  } catch {
    return false
  }
}

export function normalizeOrigin(origin) {
  return String(origin || '').trim().replace(/\/+$/, '')
}

export function getStoredQrOrigin() {
  try {
    return normalizeOrigin(localStorage.getItem(QR_ORIGIN_KEY) || '')
  } catch {
    return ''
  }
}

export function setStoredQrOrigin(origin) {
  const value = normalizeOrigin(origin)
  try {
    if (value) localStorage.setItem(QR_ORIGIN_KEY, value)
    else localStorage.removeItem(QR_ORIGIN_KEY)
  } catch {
    // 隐私模式写不进 localStorage 时，仍可用当前页的选择生成二维码
  }
}

/**
 * 开发服务器暴露的局域网 origin 列表（生产环境没有该接口，返回空数组）。
 */
export async function fetchDevLanOrigins() {
  try {
    const res = await fetch('/__dev/public-origin', { headers: { Accept: 'application/json' } })
    if (!res.ok) return []
    const data = await res.json()
    return Array.isArray(data?.origins) ? data.origins.map(normalizeOrigin).filter(Boolean) : []
  } catch {
    return []
  }
}

/**
 * 手机端登记地址：后端只给相对路径（/m/渠道码），
 * 拼成绝对地址必须在浏览器侧做。
 *
 * 生产环境用当前页 origin（内网 IP 或域名）。
 * 本地用 localhost 打开管理端时，必须改成电脑局域网地址，否则手机扫到的是手机自己。
 */
export function mobileUrl(mobilePath, origin = location.origin) {
  if (!mobilePath) {
    return ''
  }
  const base = normalizeOrigin(origin) || location.origin
  const path = mobilePath.startsWith('/') ? mobilePath : `/${mobilePath}`
  return base + path
}
