import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import {
  batchStateMap,
  summarizeBatch,
  taskStatusText,
  taskStatusTone,
} from '../src/core/mediaBatchStatus.ts'

test('a running item keeps the batch in processing even when another file already succeeded', () => {
  const state = summarizeBatch({
    batchId: 'MB-1',
    items: [
      { status: 'SUCCESS', finishedAt: '2026-10-20T10:00:00Z' },
      { status: 'PROCESSING' },
    ],
  })
  assert.equal(state.status, 'PROCESSING')
  assert.equal(state.running, 1)
  assert.equal(state.total, 2)
})

test('a terminal failure is reported as failed with the real reason', () => {
  const state = summarizeBatch({
    batchId: 'MB-2',
    items: [
      { status: 'SUCCESS', finishedAt: '2026-10-20T10:00:00Z' },
      { status: 'FAILED_TERMINAL', failReason: '模型服务暂不可用', finishedAt: '2026-10-20T10:01:00Z' },
    ],
  })
  assert.equal(state.status, 'FAILED')
  assert.equal(state.failed, 1)
  assert.equal(state.failReason, '模型服务暂不可用')
  assert.equal(state.finishedAt, '2026-10-20T10:01:00.000Z')
})

test('all success is success, all canceled is canceled, and missing batch is unknown', () => {
  assert.equal(summarizeBatch({ batchId: 'MB-3', items: [{ status: 'SUCCESS' }] }).status, 'SUCCESS')
  assert.equal(summarizeBatch({ batchId: 'MB-4', items: [{ status: 'FAILED_TERMINAL', failReason: 'CANCELED' }] }).status, 'CANCELED')
  assert.equal(summarizeBatch({ batchId: 'MB-5' }).status, 'UNKNOWN')
})

test('batch map keeps archived and current records addressable by batch id', () => {
  const map = batchStateMap([
    { batchId: 'MB-6', items: [{ status: 'SUCCESS' }] },
    { batchId: 'MB-7', items: [{ status: 'FAILED_TERMINAL', failReason: '格式异常' }] },
  ])
  assert.equal(map['MB-6'].status, 'SUCCESS')
  assert.equal(map['MB-7'].status, 'FAILED')
  assert.equal(taskStatusText('PROCESSING'), '处理中')
  assert.equal(taskStatusText('FAILED'), '处理失败')
  assert.equal(taskStatusText(''), '—')
  assert.equal(taskStatusTone('SUCCESS'), 'success')
  assert.equal(taskStatusTone('FAILED'), 'danger')
})
