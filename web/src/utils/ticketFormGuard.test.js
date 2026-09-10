import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  DEFAULT_FORM_RULES,
  isWeakQueryCode,
  normalizePhone,
  normalizeQueryCode,
  randomQueryCode,
  resolveFormRules,
  submitBlockReason
} from './ticketFormGuard.js'

describe('submitBlockReason', () => {
  it('只填标题和描述、渠道要联系电话时不能静默放行', () => {
    const r = submitBlockReason({
      title: '打印机卡纸了',
      content: '三楼打印机不出纸',
      contactPhone: '',
      needPhone: true,
      uploading: false
    })
    assert.equal(r?.field, 'f-phone')
    assert.match(r.msg, /联系电话/)
  })

  it('标题描述和手机号齐了就可以提交', () => {
    assert.equal(submitBlockReason({
      title: '打印机卡纸了',
      content: '三楼打印机不出纸',
      contactPhone: '13800138000',
      needPhone: true,
      uploading: false,
      queryCode: '472815'
    }), null)
  })

  it('短号或内线电话可以通过', () => {
    assert.equal(submitBlockReason({
      title: '打印机卡纸了',
      content: '三楼打印机不出纸',
      contactPhone: '8012',
      needPhone: true,
      uploading: false,
      queryCode: '472815'
    }), null)
    assert.equal(submitBlockReason({
      title: '打印机卡纸了',
      content: '三楼打印机不出纸',
      contactPhone: '3-8012',
      needPhone: true,
      uploading: false,
      queryCode: '472815'
    }), null)
  })

  it('渠道不要求联系电话时不必填', () => {
    assert.equal(submitBlockReason({
      title: '打印机卡纸了',
      content: '三楼打印机不出纸',
      contactPhone: '',
      needPhone: false,
      uploading: false,
      queryCode: '472815'
    }), null)
  })

  it('照片上传中要拦住并说明原因', () => {
    const r = submitBlockReason({
      title: '打印机卡纸了',
      content: '三楼打印机不出纸',
      needPhone: false,
      uploading: true
    })
    assert.equal(r?.field, 'shots')
  })
})

describe('normalizePhone', () => {
  it('去掉空格和 +86', () => {
    assert.equal(normalizePhone('138 0013 8000'), '13800138000')
    assert.equal(normalizePhone('+86 13800138000'), '13800138000')
  })

  it('短号不去掉前导数字', () => {
    assert.equal(normalizePhone('8012'), '8012')
    assert.equal(normalizePhone('3-8012'), '38012')
  })
})

describe('按参数控制的必填项', () => {
  // 查询密码是硬性必填项（不随参数变），所以这份 base 里必须带上一个合规的 6 位号
  const base = {
    title: '打印机卡纸了',
    content: '三楼打印机不出纸',
    contactPhone: '',
    queryCode: '472815',
    needPhone: false,
    uploading: false
  }

  it('没下发 formRules 时沿用默认规则（与服务端 defaults 一致）', () => {
    assert.deepEqual(resolveFormRules(undefined), DEFAULT_FORM_RULES)
    assert.equal(submitBlockReason(base), null)
    assert.equal(submitBlockReason({ ...base, title: '卡纸' })?.field, 'f-title')
  })

  it('标题改成选填后不再拦空值，但填了仍要够字数', () => {
    const rules = { ...DEFAULT_FORM_RULES, titleRequired: false, titleMinLen: 4 }
    assert.equal(submitBlockReason({ ...base, title: '', rules }), null)
    assert.match(submitBlockReason({ ...base, title: '卡纸', rules }).msg, /至少 4 个字/)
  })

  it('标题描述都选填时至少要说清一件事', () => {
    const rules = { ...DEFAULT_FORM_RULES, titleRequired: false, contentRequired: false }
    assert.match(submitBlockReason({ ...base, title: '', content: '', rules }).msg, /至少填写/)
    assert.equal(submitBlockReason({ ...base, title: '网卡了', content: '', rules }), null)
  })

  it('全局参数要求填电话时，渠道选填也拦', () => {
    const rules = { ...DEFAULT_FORM_RULES, contactPhoneRequired: true }
    const r = submitBlockReason({ ...base, needPhone: false, rules })
    assert.equal(r?.field, 'f-phone')
  })

  it('姓名、地点、问题类型可以被参数打开', () => {
    const rules = {
      ...DEFAULT_FORM_RULES,
      contactNameRequired: true,
      locationRequired: true,
      categoryRequired: true
    }
    assert.equal(submitBlockReason({ ...base, rules }).field, 'f-name')
    assert.equal(submitBlockReason({ ...base, rules, contactName: '张三' }).field, 'f-loc')
    assert.equal(
      submitBlockReason({
        ...base, rules, contactName: '张三', location: '三楼机房', categoryOptions: [{ id: 7 }]
      }).field,
      'f-category'
    )
    assert.equal(
      submitBlockReason({
        ...base, rules, contactName: '张三', location: '三楼机房',
        categoryId: 7, categoryOptions: [{ id: 7 }]
      }),
      null
    )
  })

  it('科室没有可选类型时不会把入口堵死', () => {
    const rules = { ...DEFAULT_FORM_RULES, categoryRequired: true }
    assert.equal(
      submitBlockReason({ ...base, rules, categoryOptions: [], categoryId: null }),
      null
    )
  })

  it('照片最少张数由参数决定', () => {
    const rules = { ...DEFAULT_FORM_RULES, minImages: 2 }
    const r = submitBlockReason({ ...base, rules, imageCount: 1 })
    assert.equal(r?.field, 'shots')
    assert.match(r.msg, /至少上传 2 张/)
    assert.equal(submitBlockReason({ ...base, rules, imageCount: 2 }), null)
  })
})

describe('查询密码（报修人自设 6 位数字）', () => {
  const ok = {
    title: '打印机卡纸了',
    content: '三楼打印机不出纸',
    needPhone: false,
    uploading: false
  }

  it('没设查询密码就拦下来，并且指出是哪个框', () => {
    const r = submitBlockReason({ ...ok })
    assert.equal(r?.field, 'f-code')
    assert.match(r.msg, /6 位数字查询密码/)
  })

  it('位数不够或不是纯数字都不放行', () => {
    for (const code of ['12345', '1234567', 'abc123', '', '   ']) {
      assert.equal(submitBlockReason({ ...ok, queryCode: code })?.field, 'f-code',
        `不应放行：[${code}]`)
    }
  })

  it('全相同、连号和常见组合都算太弱', () => {
    for (const weak of ['111111', '000000', '123456', '654321', '234567', '121212']) {
      assert.equal(isWeakQueryCode(weak), true, `应判为弱口令：${weak}`)
      assert.match(submitBlockReason({ ...ok, queryCode: weak }).msg, /太好猜/)
    }
    assert.equal(isWeakQueryCode('472815'), false)
    assert.equal(submitBlockReason({ ...ok, queryCode: '472815' }), null)
  })

  it('输入框里的多余字符会被当场吃掉，只留 6 位数字', () => {
    assert.equal(normalizeQueryCode(' 4 7-2 81 599 '), '472815')
    assert.equal(normalizeQueryCode('a1b2c3d4e5f6'), '123456')
  })

  it('随机生成的号不会撞在自己的弱口令黑名单上', () => {
    for (let i = 0; i < 50; i++) {
      const code = randomQueryCode()
      assert.match(code, /^\d{6}$/)
      assert.equal(isWeakQueryCode(code), false, `生成了弱口令：${code}`)
    }
  })

  it('查询密码这道坎排在最后：前面的字段没填时先提示前面的', () => {
    // 标题不合规 + 没设密码：应先报标题，而不是把用户跳到一个还没看见的框
    assert.equal(submitBlockReason({ ...ok, title: '卡纸', queryCode: '' })?.field, 'f-title')
  })
})