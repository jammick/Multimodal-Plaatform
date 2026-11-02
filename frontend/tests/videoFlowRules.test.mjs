import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import {
  evaluateFlowTrigger,
  flowSizeText,
  flowWindowText,
  normalizeFlowDraft,
  validateFlowDraft,
  withStepDependencies,
} from '../src/core/videoFlowRules.ts'

const base = {
  name: '巡检视频自动标准化',
  steps: ['VIDEO_STANDARD', 'VIDEO_KEYFRAME'],
  formats: ['mp4', 'avi', 'mov'],
  minSizeMB: 10,
  maxSizeMB: 2048,
  workdaysOnly: '否',
  windowStart: '00:00',
  windowEnd: '23:59',
  requiredMeta: [],
  notifyChannels: ['SYSTEM_MESSAGE'],
  corruptSeconds: 3,
}

test('size and window text never invent limits that are not configured', () => {
  assert.equal(flowSizeText({ minSizeMB: 10, maxSizeMB: 2048 }), '10 MB ~ 2048 MB')
  assert.equal(flowSizeText({}), '不限 ~ 不限')
  assert.equal(flowWindowText({ windowStart: '08:00', windowEnd: '18:00', workdaysOnly: '是' }), '工作日 08:00–18:00')
  assert.equal(flowWindowText({}), '不限时段')
})

test('event analysis and semantic steps automatically pull in keyframes', () => {
  assert.deepEqual(withStepDependencies(['VIDEO_EVENT']), ['VIDEO_KEYFRAME', 'VIDEO_EVENT'])
  assert.deepEqual(withStepDependencies(['VIDEO_SEMANTIC']), ['VIDEO_KEYFRAME', 'VIDEO_SEMANTIC'])
  assert.deepEqual(withStepDependencies(['VIDEO_KEYFRAME', 'VIDEO_EVENT']), ['VIDEO_KEYFRAME', 'VIDEO_EVENT'])
  assert.deepEqual(withStepDependencies(['VIDEO_STANDARD']), ['VIDEO_STANDARD'])
})

test('valid draft passes and normalizes steps, rules and numbers', () => {
  assert.equal(validateFlowDraft(base), null)
  const draft = normalizeFlowDraft({
    ...base, formats: 'MP4,AVI', minSizeMB: '10', steps: ['VIDEO_EVENT'],
    rules: { VIDEO_EVENT: 'BEHRULE-1' },
  })
  assert.deepEqual(draft.formats, ['mp4', 'avi'])
  assert.deepEqual(draft.steps, ['VIDEO_KEYFRAME', 'VIDEO_EVENT'])
  assert.deepEqual(draft.rules, { VIDEO_EVENT: 'BEHRULE-1' })
  assert.equal(draft.minSizeMB, 10)
})

test('validation requires steps, sane window and sizes', () => {
  assert.equal(validateFlowDraft({ ...base, steps: [] }), '请至少勾选一个执行节点')
  assert.equal(validateFlowDraft({ ...base, windowStart: '18:00', windowEnd: '08:00' }), '时间窗口的开始时间须早于结束时间')
  assert.equal(validateFlowDraft({ ...base, windowStart: '8:00' }), '时间窗口须为 24 小时制 HH:mm')
  assert.equal(validateFlowDraft({ ...base, minSizeMB: 3000, maxSizeMB: 2048 }), '最小文件大小不能大于最大文件大小')
  assert.equal(validateFlowDraft({ ...base, corruptSeconds: 0 }), '损坏判定阈值须大于 0 秒')
})

test('trigger evaluation reports format, size, window and metadata rejections in order', () => {
  const file = { name: '巡检.mp4', sizeBytes: 20 * 1024 * 1024, uploadedAt: '2026-10-20T10:00:00+08:00' }

  assert.equal(evaluateFlowTrigger({ flow: base, file }).code, 'MATCHED')

  const format = evaluateFlowTrigger({ flow: base, file: { ...file, name: '巡检.mkv' } })
  assert.equal(format.code, 'FORMAT_REJECTED')
  assert.match(format.message, /MKV/)

  const size = evaluateFlowTrigger({ flow: base, file: { ...file, sizeBytes: 1024 } })
  assert.equal(size.code, 'SIZE_REJECTED')

  const windowed = { ...base, workdaysOnly: '否', windowStart: '08:00', windowEnd: '18:00' }
  assert.equal(evaluateFlowTrigger({ flow: windowed, file: { ...file, uploadedAt: '2026-10-20T20:00:00+08:00' } }).code, 'WINDOW_REJECTED')

  const weekend = { ...base, workdaysOnly: '是', windowStart: '00:00', windowEnd: '23:59' }
  assert.equal(evaluateFlowTrigger({ flow: weekend, file: { ...file, uploadedAt: '2026-10-18T10:00:00+08:00' } }).code, 'WINDOW_REJECTED')

  const withMeta = { ...base, requiredMeta: ['title', 'deviceNo'] }
  const meta = evaluateFlowTrigger({ flow: withMeta, file, meta: { title: '巡检' } })
  assert.equal(meta.code, 'META_INCOMPLETE')
  assert.match(meta.message, /设备编号/)
  assert.equal(evaluateFlowTrigger({ flow: withMeta, file, meta: { title: '巡检', deviceNo: 'CAM-1' } }).code, 'MATCHED')
})
