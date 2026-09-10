/**
 * 手机端提交前校验。故意不把「填不齐」做成按钮 disabled：
 * 原生 disabled 在微信/iOS 里点击无反馈，用户会以为坏了。
 *
 * <p>必填项与字数全部来自表单规则（sys_config 的 ticket_form 分组，经 meta 接口下发），
 * 这里只负责「按规则拦人」，规则本身由服务端读参数生成——所以下面那份默认值必须和
 * TicketFormRules.defaults() 保持一致，用作 meta 还没回来 / 老后端没下发 formRules 时的兜底。
 */
export const DEFAULT_FORM_RULES = {
  titleRequired: true,
  titleMinLen: 4,
  contentRequired: true,
  contentMinLen: 5,
  categoryRequired: false,
  locationRequired: false,
  contactNameRequired: false,
  contactPhoneRequired: false,
  minImages: 0,
  maxImages: 9
}

/** 字段中文名：报错文案与服务端 requireByRules() 用同一套叫法，免得两边各说各话 */
const LABELS = {
  title: '问题标题',
  content: '问题描述',
  contactName: '你的姓名或工号',
  location: '发生地点'
}

/**
 * 把 meta 下发的规则补齐成完整对象。
 * 只覆盖真正下发的那些键，缺的一律回到默认值（服务端也是这么夹的）。
 */
export function resolveFormRules(source) {
  return { ...DEFAULT_FORM_RULES, ...(source || {}) }
}

/**
 * 查询密码的硬规则：6 位数字，不能是全同/连号/常见组合。
 *
 * 与服务端 TicketQueryCode 是同一套判断，两边必须同步改，且都不读 meta 下发的参数：
 * 单号已经是可预测的短流水（ST100001 起），这 6 位数字是唯一还在挡遍历的东西，
 * 做成可关闭、可改短的配置项就等于给公开接口留了个开关。
 * 前端这份只是「提前拦一道、少跑一趟网络」，真正的裁决在服务端。
 */
export const QUERY_CODE_LENGTH = 6

const WEAK_QUERY_CODES = new Set([
  '000000', '111111', '222222', '333333', '444444',
  '555555', '666666', '777777', '888888', '999999',
  '123456', '234567', '345678', '456789', '654321',
  '543210', '987654', '876543', '765432', '121212',
  '123123', '112233', '010101', '000001', '100000'
])

/** 只留数字并截到 6 位：让键盘上多敲的字符当场消失，而不是等提交时才报错 */
export function normalizeQueryCode(raw) {
  return String(raw || '').replace(/\D/g, '').slice(0, QUERY_CODE_LENGTH)
}

/** 太好猜的号：全同、升/降连号，外加一份常见组合黑名单（与服务端一致） */
export function isWeakQueryCode(code) {
  const c = String(code || '')
  if (c.length !== QUERY_CODE_LENGTH) return true
  if (WEAK_QUERY_CODES.has(c)) return true
  let allSame = true
  let asc = true
  let desc = true
  for (let i = 1; i < c.length; i++) {
    if (c[i] !== c[0]) allSame = false
    if (Number(c[i]) !== Number(c[i - 1]) + 1) asc = false
    if (Number(c[i]) !== Number(c[i - 1]) - 1) desc = false
  }
  return allSame || asc || desc
}

/** 随机换一个：给「懒得想」的报修人兜底，同样避开弱口令 */
export function randomQueryCode() {
  for (let i = 0; i < 20; i++) {
    const code = String(Math.floor(Math.random() * 1e6)).padStart(QUERY_CODE_LENGTH, '0')
    if (!isWeakQueryCode(code)) return code
  }
  return '802719'
}

export function normalizePhone(raw) {
  let s = String(raw || '').replace(/\D/g, '')
  if (s.startsWith('86') && s.length === 13) {
    s = s.slice(2)
  }
  return s
}

/** 手机号、短号、内线均可：去掉分隔符后 3~20 位数字 */
export function isValidContactPhone(raw) {
  const s = normalizePhone(raw)
  return s.length >= 3 && s.length <= 20
}

/** 文本字段：必填则不可空；填了才检查字数。返回 trim 后的值 */
function blockForText({ required, minLen }, label, value, field) {
  const v = String(value || '').trim()
  if (required && !v) {
    return { msg: `请填写${label}`, field }
  }
  if (v && minLen > 1 && v.length < minLen) {
    return { msg: `${label}至少 ${minLen} 个字`, field }
  }
  return null
}

/**
 * @param rules       meta.formRules（缺省时用 DEFAULT_FORM_RULES）
 * @param needPhone   服务端合并过的「本入口联系电话是否必填」（渠道勾选 OR 全局参数）
 * @param imageCount  已上传成功的照片张数
 * @returns 第一个不满足规则的原因，全通过则 null
 */
export function submitBlockReason({
  title,
  content,
  contactPhone,
  contactName,
  location,
  categoryId,
  categoryOptions = [],
  queryCode,
  imageCount = 0,
  needPhone = false,
  uploading = false,
  rules
} = {}) {
  if (uploading) {
    return { msg: '照片还在上传，请稍候', field: 'shots' }
  }
  const r = resolveFormRules(rules)

  const titleLabel = LABELS.title
  const contentLabel = LABELS.content
  if (!r.titleRequired && !r.contentRequired) {
    // 两样都做成选填不等于可以什么都不写：服务端这两列是 NOT NULL，至少要说清一件事
    if (!String(title || '').trim() && !String(content || '').trim()) {
      return { msg: '请至少填写问题标题或问题描述', field: 'f-title' }
    }
  } else {
    const hit = blockForText({ required: r.titleRequired, minLen: r.titleMinLen }, titleLabel, title, 'f-title')
      || blockForText({ required: r.contentRequired, minLen: r.contentMinLen }, contentLabel, content, 'f-content')
    if (hit) return hit
  }

  const phone = normalizePhone(contactPhone)
  if ((needPhone || r.contactPhoneRequired) && !phone) {
    return { msg: '请填写联系电话（手机、短号或内线均可）', field: 'f-phone' }
  }
  if (phone && !isValidContactPhone(phone)) {
    return { msg: '联系电话至少 3 位数字', field: 'f-phone' }
  }

  const nameHit = blockForText({ required: r.contactNameRequired, minLen: 0 }, LABELS.contactName, contactName, 'f-name')
  if (nameHit) return nameHit
  const locHit = blockForText({ required: r.locationRequired, minLen: 0 }, LABELS.location, location, 'f-loc')
  if (locHit) return locHit

  if (r.categoryRequired && !categoryId && categoryOptions.length) {
    return { msg: '请选择问题类型', field: 'f-category' }
  }

  if (r.minImages > 0 && Number(imageCount) < r.minImages) {
    return { msg: `请至少上传 ${r.minImages} 张现场照片`, field: 'shots' }
  }

  // 查询密码放在最后一道拦：前面的字段没填时先提示前面的，
  // 别让用户刚填完标题就被一个还没看见的输入框挡住
  const code = normalizeQueryCode(queryCode)
  if (code.length !== QUERY_CODE_LENGTH) {
    return { msg: `请设置 ${QUERY_CODE_LENGTH} 位数字查询密码`, field: 'f-code' }
  }
  if (isWeakQueryCode(code)) {
    return { msg: '查询密码太好猜（全相同、连号都不行），请换一个', field: 'f-code' }
  }
  return null
}
