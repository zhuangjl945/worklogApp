import { ossPolicy, ossDeleteObject } from '../api/work'
import { parseFileUrlList, removedUrls, toOssKey } from './ossUrls'

export { parseFileUrlList, removedUrls, toOssKey }

export async function uploadToOss(file, dir) {
  const policyResp = await ossPolicy({
    dir,
    filename: file?.name,
    contentType: file?.type || undefined
  })
  const p = policyResp.data
  const formData = new FormData()
  formData.append('key', p.key)
  formData.append('policy', p.policy)
  formData.append('OSSAccessKeyId', p.accessKeyId)
  formData.append('signature', p.signature)
  formData.append('success_action_status', '200')
  if (p.contentType) formData.append('Content-Type', p.contentType)
  if (p.contentDisposition) formData.append('Content-Disposition', p.contentDisposition)
  formData.append('file', file)

  const resp = await fetch(p.host, { method: 'POST', body: formData })
  if (!resp.ok) throw new Error(file?.name ? `文件 ${file.name} 上传失败` : '上传失败')
  return p.url
}

/** 登录用户删未引用对象；失败只打日志，不挡页面上的移除 */
export async function tryDeleteOssObject(urlOrKey) {
  const key = toOssKey(urlOrKey)
  if (!key) return
  try {
    await ossDeleteObject({ key })
  } catch (e) {
    console.error('清理 OSS 图片失败:', e)
  }
}
