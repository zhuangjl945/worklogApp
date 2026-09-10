import { isLoopbackOrigin, mobileUrl, qrcodeLabelSvg } from './qrcodeSvg'

/**
 * 80×60mm 热敏标签的「方案 1 居中三段式」打印模板。
 *
 * 为什么单独一个文件而不是继续写在 TicketChannelView 里：批量打印要一次生成 N 枚标签，
 * 手拼 HTML 字符串的写法很快就会失控；版式尺寸、分页规则、点阵对齐这三件事
 * 必须集中在一处，改标签纸规格时只动这里。
 *
 * 版式的三个数字不是拍脑袋来的：
 *   1) 203dpi 标签机 1mm = 8 个点，二维码边长必须是「模块数 × 整数点」，否则驱动栅格化时踩点出灰阶；
 *   2) 二维码四周要留 4 个模块的静区（纯白），这条最吃空间，直接把 60mm 高里的可用码长压到 36mm；
 *   3) 打印机四周有 1~2mm 打不到的边，所以内容一律内收 1.5mm，不靠纸边排版。
 */

/** 标签纸物理尺寸：80mm 宽 × 60mm 高（横版，走纸方向为高） */
export const LABEL_PAPER = { widthMm: 80, heightMm: 60 }

/**
 * 纵向分配（mm）：1.5 + 6.2 + 46.25 + 4 + 1.5 = 59.45，留 0.55mm 给走纸误差。
 * qrSlotMm 是「码 + 上下静区」的容器高，qrMaxMm 是码的边长。
 *
 * 36.25 这个数不是凑的：29 模块 × 10 点 = 290 点 = 36.25mm，是 60mm 纸高里
 * 能同时满足「整数点」和「上下各留满 4 模块静区」的最大一档
 * （静区 = (46.25 − 36.25) / 2 = 5.00mm = 4 模块 × 1.25mm，正好卡在规范线上）。
 * 上一档 11 点 = 39.88mm 时静区只剩 3.2mm，不合规；下一档 9 点只有 32.63mm，白浪费 3.6mm。
 */
export const LABEL_LAYOUT = {
  padYMm: 1.5,
  titleMm: 6.2,
  qrSlotMm: 46.25,
  qrMaxMm: 36.25,
  footerMm: 4
}

/** 打印文档样式：只服务于标签本身，不参与页面布局 */
const DOC_CSS =
  '@page{size:80mm 60mm;margin:0}' +
  'html,body{margin:0;padding:0;background:#fff}' +
  '.lbl{box-sizing:border-box;width:80mm;height:60mm;padding:' + LABEL_LAYOUT.padYMm + 'mm 2mm;background:#fff;color:#000;' +
  'display:flex;flex-direction:column;align-items:center;overflow:hidden;' +
  'font-family:"SimHei","Microsoft YaHei","Heiti SC",sans-serif;' +
  /* 一页一枚：热敏卷纸每页正好走一张标签 */
  'page-break-after:always;break-after:page;page-break-inside:avoid;break-inside:avoid}' +
  /* 最后一枚必须取消分页，否则打印机会为这个空页多走一张纸，白废一张标签 */
  '.lbl:last-child{page-break-after:auto;break-after:auto}' +
  '.ttl{height:' + LABEL_LAYOUT.titleMm + 'mm;line-height:' + LABEL_LAYOUT.titleMm + 'mm;font-size:12.5pt;' +
  'font-weight:700;width:100%;text-align:center;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
  '.qr{height:' + LABEL_LAYOUT.qrSlotMm + 'mm;display:flex;align-items:center;justify-content:center}' +
  /* 静区红线：码区容器内不允许出现任何文字、边框、logo */
  '.ft{height:' + LABEL_LAYOUT.footerMm + 'mm;line-height:' + LABEL_LAYOUT.footerMm + 'mm;font-size:7.5pt;' +
  'font-weight:700;width:100%;display:flex;justify-content:space-between;align-items:center}' +
  '.cd{font-family:Consolas,monospace;letter-spacing:.15mm}'

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 生成一枚标签的 HTML 片段。
 * 地址为空的渠道直接跳过，避免打出一张扫不开的废码。
 */
export function labelMarkup(row, origin) {
  const url = mobileUrl(row.mobilePath, origin)
  const qr = qrcodeLabelSvg(url, LABEL_LAYOUT.qrMaxMm)
  if (!qr) {
    return ''
  }
  return (
    '<div class="lbl">' +
    '<div class="ttl">' + esc(row.channelName || row.channelCode) + '</div>' +
    '<div class="qr">' + qr.svg + '</div>' +
    '<div class="ft"><span>手机扫码登记 · 无需登录</span>' +
    '<span class="cd">' + esc(row.channelCode || '') + '</span></div>' +
    '</div>'
  )
}

/**
 * 组装完整打印文档。同一份文档也用于弹窗里的 iframe 预览，保证所见即所得。
 * @param list 已按「渠道 × 份数」展开好的数组
 */
export function buildLabelDocument(list, origin) {
  const body = (list || []).map((row) => labelMarkup(row, origin)).join('')
  const first = (list && list[0] && list[0].channelName) || '登记二维码标签'
  return (
    '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">' +
    '<title>' + esc(first) + '</title><style>' + DOC_CSS + '</style></head><body>' + body + '</body></html>'
  )
}

/**
 * 把「渠道 × 份数」展开成出纸顺序。
 * 同一渠道的几份连续排，拿到的是一叠同名标签，不用在出纸口按名字翻找。
 */
export function expandCopies(rows, copies = 1) {
  const n = Math.max(1, Math.floor(copies) || 1)
  const out = []
  for (const row of rows || []) {
    for (let i = 0; i < n; i++) {
      out.push(row)
    }
  }
  return out
}

/**
 * 打开打印窗口并触发打印。
 *
 * @param rows 勾选的渠道
 * @param origin 扫码地址前缀（必须是手机能访问的局域网地址或域名）
 * @param copies 每个渠道打印几份
 * @returns {{ok:boolean,message:string,pages:number}}
 */
export function openLabelPrint(rows, origin, copies = 1) {
  const list = (rows || []).filter((r) => r && r.mobilePath)
  if (!list.length) {
    return { ok: false, message: '没有可打印的渠道', pages: 0 }
  }
  // 本地用 localhost 打开管理端时，打出来的码指向手机自己，贴出去就是废码，直接拦掉
  if (isLoopbackOrigin(origin)) {
    return { ok: false, message: '当前扫码地址是 localhost，手机扫开的是它自己。请先改成局域网地址再打印。', pages: 0 }
  }
  const flat = expandCopies(list, copies)
  const win = window.open('', '_blank', 'width=460,height=720')
  if (!win) {
    return { ok: false, message: '浏览器拦截了打印窗口，请允许本页弹窗后重试', pages: 0 }
  }
  win.document.write(buildLabelDocument(flat, origin))
  win.document.close()
  win.focus()
  // 等 SVG 完成排版再触发，否则部分浏览器会打出空白标签
  setTimeout(() => {
    try {
      win.print()
    } catch (e) {
      // 打印被系统取消不需要打扰用户，窗口留着可以手动再按 Ctrl+P
    }
  }, 400)
  return { ok: true, message: `已生成 ${flat.length} 张标签（${list.length} 个渠道 × ${Math.max(1, Math.floor(copies) || 1)} 份）`, pages: flat.length }
}