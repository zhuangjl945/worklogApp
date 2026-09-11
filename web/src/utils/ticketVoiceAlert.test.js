import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { announceText, MAX_BRIEF_ITEMS, REPEAT_MS, shouldAnnounceIncrease } from './ticketVoiceAlert.js'

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

describe('announceText 不带明细', () => {
  it('一条用单数口吻', () => {
    assert.equal(announceText(1), '有新的问题待接收，请及时受理')
  })

  it('多条报当前待受理总数', () => {
    assert.equal(announceText(3), '有 3 条问题待接收，请及时受理')
  })

  it('明细为空数组或脏数据时退回笼统播报', () => {
    assert.equal(announceText(2, []), '有 2 条问题待接收，请及时受理')
    assert.equal(announceText(2, [null, {}]), '有 2 条问题待接收，请及时受理')
    assert.equal(announceText(2, 'x'), '有 2 条问题待接收，请及时受理')
  })
})

describe('announceText 带科室明细', () => {
  it('单条报科室加问题分类', () => {
    assert.equal(
      announceText(1, [{ bizDeptName: '放射科', categoryName: '网络设备' }]),
      '放射科的网络设备待接收，请及时受理'
    )
  })

  it('多条只念前两条，剩下的用「等」带过', () => {
    const briefs = [
      { bizDeptName: '放射科', categoryName: '网络设备' },
      { bizDeptName: '检验科', categoryName: '打印机' },
      { bizDeptName: '骨科', categoryName: '电脑故障' }
    ]
    assert.equal(
      announceText(5, briefs),
      '有 5 条问题待接收：放射科的网络设备、检验科的打印机等，请及时受理'
    )
  })

  it('念出来的条数正好等于总数时不补「等」', () => {
    const briefs = [
      { bizDeptName: '放射科', categoryName: '网络设备' },
      { bizDeptName: '检验科', categoryName: '打印机' }
    ]
    assert.equal(
      announceText(2, briefs),
      '有 2 条问题待接收：放射科的网络设备、检验科的打印机，请及时受理'
    )
  })

  it('加急单要额外喊一句', () => {
    assert.equal(
      announceText(1, [{ bizDeptName: '急诊科', categoryName: '系统卡顿', urgent: true }]),
      '急诊科的系统卡顿待接收，这条加急，请及时受理'
    )
    assert.equal(
      announceText(4, [
        { bizDeptName: '急诊科', categoryName: '系统卡顿', urgent: true },
        { bizDeptName: '外科', categoryName: '打印机' }
      ]),
      '有 4 条问题待接收：急诊科的系统卡顿、外科的打印机等，其中含加急，请及时受理'
    )
  })

  it('分类缺失退回标题，标题也缺就退回「某科室的新问题」', () => {
    assert.equal(
      announceText(1, [{ bizDeptName: '内科', title: '换墨盒' }]),
      '内科的换墨盒待接收，请及时受理'
    )
    assert.equal(
      announceText(1, [{ bizDeptName: '内科' }]),
      '内科的新问题待接收，请及时受理'
    )
  })

  it('科室缺失时只报问题，不写出「的」字', () => {
    assert.equal(
      announceText(1, [{ categoryName: '网络故障' }]),
      '网络故障待接收，请及时受理'
    )
  })

  it('标点会被洗掉，避免引擎念出「左括号」', () => {
    assert.equal(
      announceText(1, [{ bizDeptName: '急诊科', categoryName: '网络（内网）故障' }]),
      '急诊科的网络内网故障待接收，请及时受理'
    )
  })

  it('超长标题截断后补「等」，播报不会变成小作文', () => {
    const phrase = announceText(1, [{ categoryName: '打印机连续卡纸需要整机更换耗材并清洁' }])
    assert.equal(phrase, '打印机连续卡纸需要整机更等待接收，请及时受理')
  })

  it('明细条数上限只念两条', () => {
    assert.equal(MAX_BRIEF_ITEMS, 2)
  })
})

describe('REPEAT_MS', () => {
  it('未处理完按一分钟重复', () => {
    assert.equal(REPEAT_MS, 60_000)
  })
})