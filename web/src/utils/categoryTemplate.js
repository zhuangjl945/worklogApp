export const CATEGORY_TEMPLATE_PRESETS = [
  {
    key: 'inspect',
    label: '巡检',
    titlePattern: '巡检-{地点}-{日期}',
    contentTemplate: '巡检范围：\n发现异常：\n已处理：\n待跟进：',
    requireContent: true,
    requireEndTime: true,
    requireImage: false
  },
  {
    key: 'meeting',
    label: '会议',
    titlePattern: '会议-{主题}-{日期}',
    contentTemplate: '议题：\n结论：\n待办：',
    requireContent: true,
    requireEndTime: false,
    requireImage: false
  },
  {
    key: 'repair',
    label: '报修',
    titlePattern: '报修-{业务科室}-{现象}',
    contentTemplate: '地点：\n现象：\n已处理：\n待跟进：',
    requireContent: true,
    requireEndTime: true,
    requireImage: true
  },
  {
    key: 'train',
    label: '培训',
    titlePattern: '培训-{主题}-{对象}',
    contentTemplate: '对象：\n培训内容：\n效果/备注：',
    requireContent: true,
    requireEndTime: false,
    requireImage: false
  }
]

export function parseCategoryTemplate(json) {
  if (!json) return null
  let o = json
  if (typeof json === 'string') {
    const s = json.trim()
    if (!s) return null
    try {
      o = JSON.parse(s)
    } catch {
      return null
    }
  }
  if (!o || typeof o !== 'object') return null
  return {
    titlePattern: o.titlePattern || '',
    contentTemplate: o.contentTemplate || '',
    requireContent: !!o.requireContent,
    requireEndTime: !!o.requireEndTime,
    requireImage: !!o.requireImage
  }
}

export function stringifyCategoryTemplate(tpl) {
  if (!tpl) return ''
  const empty =
    !String(tpl.titlePattern || '').trim() &&
    !String(tpl.contentTemplate || '').trim() &&
    !tpl.requireContent &&
    !tpl.requireEndTime &&
    !tpl.requireImage
  if (empty) return ''
  return JSON.stringify({
    titlePattern: tpl.titlePattern || '',
    contentTemplate: tpl.contentTemplate || '',
    requireContent: !!tpl.requireContent,
    requireEndTime: !!tpl.requireEndTime,
    requireImage: !!tpl.requireImage
  })
}

export function templateDateLabel(startTime) {
  const raw = startTime ? String(startTime).replace(' ', 'T') : ''
  const d = raw ? new Date(raw) : new Date()
  const x = Number.isFinite(d.getTime()) ? d : new Date()
  return `${x.getMonth() + 1}-${x.getDate()}`
}

export function fillTemplatePattern(pattern, ctx) {
  if (!pattern) return ''
  const date = ctx.date || ''
  const bizDept = ctx.bizDept || ''
  const category = ctx.category || ''
  return String(pattern)
    .replaceAll('{日期}', date)
    .replaceAll('{date}', date)
    .replaceAll('{业务科室}', bizDept)
    .replaceAll('{科室}', bizDept)
    .replaceAll('{分类}', category)
}

export function hasImageUrls(imageUrls) {
  if (!imageUrls) return false
  try {
    const arr = JSON.parse(imageUrls)
    return Array.isArray(arr) && arr.filter(Boolean).length > 0
  } catch {
    return String(imageUrls).split(',').filter(Boolean).length > 0
  }
}

export function validateAgainstTemplate(tpl, form) {
  const errors = []
  if (!tpl) return errors
  if (tpl.requireContent && !String(form.content || '').trim()) {
    errors.push('该分类要求填写工作内容')
  }
  if (tpl.requireEndTime && !form.endTime) {
    errors.push('该分类要求填写截止日期')
  }
  if (tpl.requireImage && !hasImageUrls(form.imageUrls)) {
    errors.push('该分类要求上传至少一张图片')
  }
  return errors
}
