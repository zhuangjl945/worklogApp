import http from './http'

/**
 * OSS 相关接口。
 *
 * <p>只放这一个函数：ossPolicy / ossPreviewUrl / ossPreviewFile / ossDeleteObject 历史长在 api/work.js 里，
 * 而 work.js 此刻有在途改动，不做无谓的大挪动，避免和你冲突。
 */

/**
 * 批量换取短时签名读地址（bucket 收敛成私有读之后，图片渲染不能再直连 OSS 域名）。
 *
 * <p>与 api/work.js 里的 ossPreviewUrl 的区别：那条会下载整个对象来嗅探 Content-Type 并决定
 * inline/attachment，适合「点开一个附件预览」；这条只校验 + 签名，一个字节都不读，
 * 因为列表页一屏可能几十张缩略图。
 *
 * @param {string[]} urls 完整地址或 objectKey 可以混传，后端会归一化
 * @param {number} ttlSeconds 期望有效期，后端夹在 60~600 秒
 */
export async function ossSignUrls(urls, ttlSeconds = 300) {
  return await http.post('/oss/sign-urls', { urls, ttlSeconds })
}
