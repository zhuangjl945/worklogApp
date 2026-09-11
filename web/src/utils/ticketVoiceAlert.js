const STORAGE_KEY = 'ticketVoiceAlert'

/** 未处理完就按这个间隔重复播报 */
export const REPEAT_MS = 60_000

/** 一次播报最多念几条明细：再多就成了流水账，值班的人听不完也记不住 */
export const MAX_BRIEF_ITEMS = 2

export function isVoiceAlertEnabled() {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    if (v == null) return true
    return v === '1'
  } catch {
    return true
  }
}

export function setVoiceAlertEnabled(on) {
  try {
    localStorage.setItem(STORAGE_KEY, on ? '1' : '0')
  } catch {
    // 隐私模式写不进 storage 也不挡开关本身
  }
}

export function shouldAnnounceIncrease(lastCount, nextCount) {
  if (lastCount == null) return false
  return Number(nextCount) > Number(lastCount)
}

/**
 * 会被某些朗读引擎念成「逗号」「左括号」的字符，拼短语前一律去掉。
 * 换行、空格仍然保留成停顿，免得把「Win11 无法启动」这种带空格的标题念成一串。
 */
const SPEECH_PUNCT = /[，。、；：！？,.;:!?'"“”‘’()（）【】\[\]{}《》<>|/\\~～\-—_*#&@^+=`%$]/g

/** 把任意字段洗成能朗读的短句：洗掉标点噪声，压缩空白，超长截断后补个「等」 */
function speechPhrase(text, maxLen) {
  const s = String(text ?? '')
    .replace(SPEECH_PUNCT, '')
    .replace(/\s+/g, ' ')
    .trim()
  if (!s) return ''
  return s.length > maxLen ? `${s.slice(0, maxLen)}等` : s
}

/**
 * 单条待受理摘要念出来长什么样：优先「问题科室 + 问题分类」，分类缺了退回标题，两者都缺就只报科室的新问题。
 *
 * 后端这些字段都是可选的（渠道没配业务科室、登记时没选分类都可能为空），
 * 所以这里宁可逐级降级，也绝不能把 undefined 拼进句子念出声。
 */
function briefPhrase(item) {
  const dept = speechPhrase(item?.bizDeptName, 12)
  const what = speechPhrase(item?.categoryName, 12) || speechPhrase(item?.title, 14)
  if (dept && what) return `${dept}的${what}`
  if (dept) return `${dept}的新问题`
  return what
}

/**
 * 组装播报文案。
 *
 * 带了明细就报到「哪个科室的哪类问题」，没带明细（老版本接口、摘要没查到）退回原来的笼统说法，
 * 保证升级前后都能出声。总数比实际念出来的条数多时补一个「等」，免得听起来像把所有单子都报完了。
 */
export function announceText(count, briefs) {
  const n = Number(count) || 0
  const list = (Array.isArray(briefs) ? briefs : [])
    .map((item) => ({ phrase: briefPhrase(item), urgent: !!item?.urgent }))
    .filter((x) => x.phrase)
  if (!list.length) {
    if (n <= 1) return '有新的问题待接收，请及时受理'
    return `有 ${n} 条问题待接收，请及时受理`
  }
  const spoken = list.slice(0, MAX_BRIEF_ITEMS).map((x) => x.phrase)
  const urgentTail = list.some((x) => x.urgent) ? (n <= 1 ? '，这条加急' : '，其中含加急') : ''
  if (n <= 1) return `${spoken[0]}待接收${urgentTail}，请及时受理`
  const moreTail = n > spoken.length ? '等' : ''
  return `有 ${n} 条问题待接收：${spoken.join('、')}${moreTail}${urgentTail}，请及时受理`
}

function pickZhVoice() {
  const voices = window.speechSynthesis.getVoices() || []
  return voices.find((v) => v.lang === 'zh-CN') || voices.find((v) => (v.lang || '').startsWith('zh')) || null
}

/**
 * 必须在用户点击的同步调用栈里执行，不能 setTimeout：
 * Chrome 过了手势窗口会静默丢掉 speak。
 */
export function speakTicketAlert(text) {
  if (typeof window === 'undefined' || !window.speechSynthesis) return false
  const synth = window.speechSynthesis
  synth.resume()
  synth.cancel()
  const u = new SpeechSynthesisUtterance(String(text || ''))
  u.lang = 'zh-CN'
  u.rate = 0.95
  u.volume = 1
  const zh = pickZhVoice()
  if (zh) {
    u.voice = zh
    u.lang = zh.lang
  }
  synth.speak(u)
  return true
}

export function unlockSpeech() {
  if (typeof window === 'undefined' || !window.speechSynthesis) return
  const u = new SpeechSynthesisUtterance('语音提醒已就绪')
  u.volume = 0.01
  u.rate = 2
  u.lang = 'zh-CN'
  window.speechSynthesis.resume()
  window.speechSynthesis.speak(u)
}

export function stopTicketAlert() {
  if (typeof window === 'undefined' || !window.speechSynthesis) return
  window.speechSynthesis.cancel()
}

export function bindSpeechKeepAlive() {
  if (typeof document === 'undefined' || !window.speechSynthesis) return () => {}
  const resume = () => {
    if (document.visibilityState === 'visible') {
      window.speechSynthesis.resume()
    }
  }
  document.addEventListener('visibilitychange', resume)
  if (window.speechSynthesis.getVoices) {
    window.speechSynthesis.getVoices()
  }
  return () => document.removeEventListener('visibilitychange', resume)
}