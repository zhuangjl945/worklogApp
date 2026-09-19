import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { removedUrls, toOssKey } from './ossUrls.js'

describe('removedUrls', () => {
  it('只返回原来有、现在没有的地址', () => {
    assert.deepEqual(
      removedUrls(['a.jpg', 'b.jpg', 'c.jpg'], ['a.jpg', 'c.jpg']),
      ['b.jpg']
    )
  })

  it('空列表安全，不会把现有文件当成被删', () => {
    assert.deepEqual(removedUrls(null, ['a']), [])
    assert.deepEqual(removedUrls(['a'], null), ['a'])
    assert.deepEqual(removedUrls([], []), [])
  })
})

describe('toOssKey', () => {
  it('完整地址和 objectKey 都能还原成要删的 key', () => {
    assert.equal(
      toOssKey('https://bucket.oss-cn-hangzhou.aliyuncs.com/work-records/a.jpg'),
      'work-records/a.jpg'
    )
    assert.equal(toOssKey('work-records/a.jpg'), 'work-records/a.jpg')
    assert.equal(toOssKey(''), '')
  })
})
