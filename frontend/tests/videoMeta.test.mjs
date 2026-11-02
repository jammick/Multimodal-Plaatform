import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import {
  hasVideoMeta,
  missingVideoMeta,
  normalizeVideoMeta,
  validShotAtFormat,
} from '../src/core/videoMetaRules.ts'

test('normalizes only the three metadata fields and trims blanks', () => {
  const meta = normalizeVideoMeta({ title: '  巡检视频  ', shotAt: '2026-09-18 10:20:00', deviceNo: ' CAM-01 ', extra: 'x' })
  assert.deepEqual(meta, { title: '巡检视频', shotAt: '2026-09-18 10:20:00', deviceNo: 'CAM-01' })
})

test('missing fields are reported by Chinese label, never filled with defaults', () => {
  assert.deepEqual(missingVideoMeta({ title: '巡检视频' }), ['拍摄时间', '设备编号'])
  assert.deepEqual(missingVideoMeta({}), ['视频标题', '拍摄时间', '设备编号'])
  assert.equal(hasVideoMeta({ title: 'a', shotAt: '2026-09-18', deviceNo: 'b' }), true)
  assert.equal(hasVideoMeta({ title: 'a', shotAt: '', deviceNo: 'b' }), false)
})

test('shot time accepts date or date-time, rejects malformed values', () => {
  assert.equal(validShotAtFormat(''), true, '空值交给必填校验处理')
  assert.equal(validShotAtFormat('2026-09-18'), true)
  assert.equal(validShotAtFormat('2026-09-18 10:20'), true)
  assert.equal(validShotAtFormat('2026-09-18 10:20:30'), true)
  assert.equal(validShotAtFormat('2026/09/18 10:20'), false)
  assert.equal(validShotAtFormat('2026-13-40 10:20'), false)
})
