import test from 'node:test'
import assert from 'node:assert/strict'
import { cleanSummaryText } from '../src/core/summaryText.ts'

test('普通总结原样返回', () => {
  assert.equal(cleanSummaryText('这是一段普通总结。'), '这是一段普通总结。')
  assert.equal(cleanSummaryText(''), '')
  assert.equal(cleanSummaryText(undefined), '')
})

test('只返回 summary 的 JSON 取出正文', () => {
  assert.equal(cleanSummaryText('{"summary":"视频讲的是巡检"}'), '视频讲的是巡检')
})

test('带 tags 与 confidence 的 JSON 只取 summary', () => {
  assert.equal(
    cleanSummaryText('{"summary":"人员进入后停留","tags":["人员"],"confidence":0.9}'),
    '人员进入后停留',
  )
})

test('截断的 JSON 不把大括号和引号带出来', () => {
  const value = cleanSummaryText('{"summary":"视频以亚洲海洋生态为主题，从海岸线')
  assert.ok(value.startsWith('视频以亚洲'))
  assert.ok(!value.includes('{'))
  assert.ok(!value.includes('"summary"'))
})

test('双层 JSON 逐层取出正文', () => {
  assert.equal(cleanSummaryText('{"summary":"{\\"summary\\":\\"真正的总结\\"}"}'), '真正的总结')
})

test('代码块包裹的 JSON 也能取出正文', () => {
  assert.equal(cleanSummaryText('```json\n{"summary":"代码块总结","tags":[]}\n```'), '代码块总结')
})

test('转义换行还原为正常文本', () => {
  assert.equal(cleanSummaryText('{"summary":"第一句\\n第二句"}'), '第一句\n第二句')
})
