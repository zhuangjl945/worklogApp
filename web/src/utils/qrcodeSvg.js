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
 * 手机端登记地址：后端只给相对路径（/m/渠道码），
 * 拼成绝对地址必须在浏览器侧用 location.origin 做，
 * 因为同一套库和同一份配置要同时跑在内网 IP、域名和本地调试端口上，写死后二维码就印错了。
 */
export function mobileUrl(mobilePath) {
  if (!mobilePath) {
    return ''
  }
  return location.origin + mobilePath
}
