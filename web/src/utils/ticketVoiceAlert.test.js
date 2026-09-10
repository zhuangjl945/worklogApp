import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { announceText, REPEAT_MS, shouldAnnounceIncrease } from './ticketVoiceAlert.js'

describe('shouldAnnounceIncrease', () => {
  it('首次采样不当作新增', () => {
    assert.equal(shouldAnnounceIncrease(null, 3), false)
  })

  it('数量上升才算新增', () => {
    assert.equal(shouldAnnounceIncrease(0, 1), true)
    assert.equal(shouldAnnounceIncrease(2, 5), true)
  })

  it('持平或下降不算新增', () => {
    assert.equal(shouldAnnounceIncrease(3, 3), false)
    assert.equal(shouldAnnounceIncrease(3, 1), false)
    assert.equal(shouldAnnounceIncrease(1, 0), false)
  })
})

describe('announceText', () => {
  it('一条用单数口吻', () => {
    assert.equal(announceText(1), '有新的问题待接收，请及时受理')
  })

  it('多条报当前待受理总数', () => {
    assert.equal(announceText(3), '有 3 条问题待接收，请及时受理')
  })
})

describe('REPEAT_MS', () => {
  it('未处理完按一分钟重复', () => {
    assert.equal(REPEAT_MS, 60_000)
  })
})
