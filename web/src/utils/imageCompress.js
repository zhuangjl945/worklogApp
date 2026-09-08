/**
 * 移动端图片上传前的本地压缩。
 *
 * 手机原图普遍 5~12MB，而后端访客策略只放行 5MB，不压必失败；
 * 这是移动表单最常见的「点了没反应」来源。报修场景 1600px 长边完全够用。
 */
const MAX_EDGE = 1600
const QUALITY = 0.8

export async function compressImage(file, { maxEdge = MAX_EDGE, quality = QUALITY } = {}) {
  if (!file) throw new Error('没有选中文件')

  // iOS 会给出 HEIC，浏览器画不出来，必须转码；这里统一走 createImageBitmap
  let bitmap
  try {
    bitmap = await createImageBitmap(file)
  } catch (e) {
    if (/heic|heif/i.test(file.type || '') || /\.heic$/i.test(file.name || '')) {
      throw new Error('iPhone 的 HEIC 格式暂不支持，请在设置里打开「兼容性优先」后重拍')
    }
    throw new Error('图片读取失败，请换一张试试')
  }

  const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height))
  const width = Math.max(1, Math.round(bitmap.width * scale))
  const height = Math.max(1, Math.round(bitmap.height * scale))

  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  ctx.drawImage(bitmap, 0, 0, width, height)
  bitmap.close?.()

  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
  if (!blob) throw new Error('图片压缩失败，请换一张试试')

  const out = new File([blob], replaceExt(file.name, 'jpg'), { type: 'image/jpeg', lastModified: Date.now() })
  return out
}

function replaceExt(name, ext) {
  const base = (name || 'photo').replace(/\.[^.]*$/, '')
  return `${base}.${ext}`
}
