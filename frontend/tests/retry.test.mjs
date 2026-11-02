import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { isTransientError, stageError, withRetry } from '../src/core/retry.ts'

test('a 503 style failure is treated as transient and retried once', async () => {
  let calls = 0
  const result = await withRetry(async () => {
    calls += 1
    if (calls === 1) throw new Error('文件上传：服务暂不可用，请稍后重试')
    return 'ok'
  }, { delayMs: 0 })
  assert.equal(result, 'ok')
  assert.equal(calls, 2)
})

test('a business validation failure is not retried', async () => {
  let calls = 0
  await assert.rejects(() => withRetry(async () => {
    calls += 1
    throw new Error('文本长度 2000000 字，超过单次入库上限 1000000 字')
  }, { delayMs: 0 }))
  assert.equal(calls, 1)
  assert.equal(isTransientError(new Error('文本长度超限')), false)
})

test('stage label is added once and kept when already present', () => {
  assert.equal(stageError('文件上传', new Error('网络异常，无法连接服务器')).message, '文件上传：网络异常，无法连接服务器')
  assert.equal(stageError('文件上传', new Error('文件上传：服务暂不可用')).message, '文件上传：服务暂不可用')
  assert.equal(stageError('提交入库', new Error('请求处理：服务异常')).message, '提交入库：请求处理：服务异常')
})
