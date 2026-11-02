import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { buildVectorLogBlocks } from '../src/core/vectorLog.ts'
import { renderLogBlocks } from '../src/core/logBlocks.ts'

const job = {
  id: 'KB-1', fileId: 'FILE-1', collectionId: 'KB-COLL-1', collection: '测试库',
  model: 'm1', sourceTaskId: 't-or-1', chunks: 3, totalChunks: 5,
  strategy: '固定长度切分', chunkSize: 512, overlap: 50, status: '失败',
  error: '索引构建失败：连接超时', at: '2026-10-19T10:00:00Z',
}

const ops = {
  actionId: 'OPS-1', instance: 'KB-COLL-1', actionType: 'INDEX_REBUILD',
  params: { requestId: 'req-1', force: true }, reason: '索引异常', result: 'INDEX_READY',
  operator: 'CURRENT_USER', executedAt: '2026-10-19T09:00:00Z',
}

test('运行日志逐项展开任务真实字段（切片、切分参数、失败原因、模型）', () => {
  const text = renderLogBlocks(buildVectorLogBlocks({
    collectionId: 'KB-COLL-1', collectionName: '测试库', jobs: [job], ops: [],
    fileNameOf: () => '需求说明.xlsx', modelLabelOf: (model) => `embedding-${model}`,
  }))
  for (const expected of ['入库 · 需求说明.xlsx · 失败', '任务编号：KB-1', '来源文件：需求说明.xlsx（FILE-1）',
    '切分策略：固定长度切分', '分块长度：512', '重叠长度：50', '切片结果：3/5 片',
    '向量模型：embedding-m1', '失败原因：索引构建失败：连接超时', '来源加工任务：t-or-1']) {
    assert.ok(text.includes(expected), `缺少 ${expected}`)
  }
})

test('维护动作带编号/结果/原因/操作人，参数过滤请求号且不出现 [object Object]', () => {
  const text = renderLogBlocks(buildVectorLogBlocks({ collectionId: 'KB-COLL-1', ops: [ops], jobs: [] }))
  for (const expected of ['维护 · 重建索引 · 索引就绪', '动作编号：OPS-1', '执行结果：索引就绪（INDEX_READY）',
    '执行原因：索引异常', '操作人：历史未记录', '· force = true']) {
    assert.ok(text.includes(expected), `缺少 ${expected}`)
  }
  assert.equal(text.includes('requestId'), false)
  assert.equal(text.includes('[object Object]'), false)
})

test('同一动作重复出现只记一条，空字段不输出占位符', () => {
  const blocks = buildVectorLogBlocks({ jobs: [{ id: 'KB-2', status: '成功', at: '2026-10-19T11:00:00Z' }], ops: [ops, { ...ops }] })
  const text = renderLogBlocks(blocks)
  assert.equal((text.match(/动作编号：OPS-1/g) || []).length, 1)
  assert.equal(text.includes('失败原因'), false)
  assert.ok(text.includes('来源文件未记录'))
})

test('关键词以块为单位过滤，保留整条日志', () => {
  const blocks = buildVectorLogBlocks({
    jobs: [job, { id: 'KB-3', fileId: 'FILE-2', status: '成功', at: '2026-10-19T12:00:00Z' }],
    fileNameOf: () => '需求说明.xlsx',
  })
  const text = renderLogBlocks(blocks, '索引构建失败')
  assert.ok(text.includes('任务编号：KB-1'))
  assert.ok(text.includes('失败原因：索引构建失败：连接超时'))
  assert.equal(text.includes('KB-3'), false)
})
