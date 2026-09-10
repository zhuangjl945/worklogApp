const STORAGE_KEY = 'ticketVoiceAlert'

/** 未处理完就按这个间隔重复播报 */
export const REPEAT_MS = 60_000

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

export function announceText(count) {
  const n = Number(count) || 0
  if (n <= 1) return '有新的问题待接收，请及时受理'
  return `有 ${n} 条问题待接收，请及时受理`
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
