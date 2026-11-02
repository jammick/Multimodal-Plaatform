import test from 'node:test'
import assert from 'node:assert/strict'
import {
  formatSecondsText, keyNodePairs, keyNodeText, recognizedTargetTypes, semanticTriplesCsv, splitTarget, targetDisplay,
  targetId, targetName, toSemanticEvent, toSemanticEventRow, tripleTargetText,
} from '../src/core/semanticEvents.ts'

test('识别到的目标类型来自轨迹与事件，去重且不补造', () => {
  const events = [
    { subjectType: 'person', objectType: 'car', subject: '', subjectId: '', predicate: '', object: '', objectId: '', startMs: 0, endMs: 0, frameRef: '', keyNodes: [] },
    { subjectType: 'person', objectType: '画面', subject: '', subjectId: '', predicate: '', object: '', objectId: '', startMs: 0, endMs: 0, frameRef: '', keyNodes: [] },
  ]
  assert.deepEqual(recognizedTargetTypes([{ label: 'person' }, { label: 'tie' }], events), ['person', 'tie', 'car'])
  assert.deepEqual(recognizedTargetTypes(undefined, []), [])
  assert.deepEqual(recognizedTargetTypes([{ label: '' }, { label: '画面' }], []), [])
})

test('时间按整秒展示，秒是最小时间单位', () => {
  assert.equal(formatSecondsText(0), '0 秒')
  assert.equal(formatSecondsText(1234), '1 秒')
  assert.equal(formatSecondsText(3723456), '3723 秒')
  assert.equal(formatSecondsText(undefined), '0 秒')
  assert.equal(formatSecondsText('bad'), '0 秒')
})

test('关键节点只展示真实存在的起始 / 转折 / 结束', () => {
  assert.equal(keyNodeText([
    { role: 'start', timeMs: 0, ref: 'a' },
    { role: 'turning', timeMs: 10000, ref: 'b' },
    { role: 'end', timeMs: 15000, ref: 'c' },
  ]), '起始 0 秒 · 转折 10 秒 · 结束 15 秒')
  assert.equal(keyNodeText([{ role: 'start', timeMs: 500, ref: '' }]), '起始 1 秒')
  assert.equal(keyNodeText([]), '')
})

test('主语宾语带轨迹编号时写成类型#编号，没有编号只显示文本', () => {
  assert.equal(tripleTargetText('person', 'T1', '行人'), 'person#T1')
  assert.equal(tripleTargetText('', '', '画面'), '画面')
  assert.equal(tripleTargetText('', '', ''), '—')
})

test('后端事件归一化保留毫秒与关键节点，缺字段给空值', () => {
  const event = toSemanticEvent({
    start_ms: 1200, end_ms: 3400, subject: 'person#T1', subject_type: 'person', subject_id: 'T1',
    predicate: '停留', object: 'car#T2', object_type: 'car', object_id: 'T2',
    cause: '人员先进入画面',
    key_nodes: [{ role: 'start', time_offset_ms: 1200, storage_ref: 'ref-1' }],
  })
  assert.equal(event.startMs, 1200)
  assert.equal(event.endMs, 3400)
  assert.equal(event.subjectId, 'T1')
  assert.equal(event.cause, '人员先进入画面')
  assert.deepEqual(event.keyNodes, [{ role: 'start', timeMs: 1200, ref: 'ref-1' }])
  const empty = toSemanticEvent({})
  assert.equal(empty.subjectId, '')
  assert.equal(empty.cause, '')
  assert.equal(empty.endMs, 0)
})

test('三元组导出带 BOM 且对逗号引号换行做转义', () => {
  const csv = semanticTriplesCsv([toSemanticEventRow({
    taskId: 't-or-1', fileId: 'FILE-1', ruleId: 'SEM-1', ruleVersion: 3, seq: 1,
    startMs: 0, endMs: 4000, subject: 'person#T1', subjectType: 'person', subjectId: 'T1',
    predicate: '停留,观察', object: 'car#T2', objectType: 'car', objectId: 'T2',
    chainTag: 'person#T1·停留·car#T2', source: 'model', finishedAt: '2026-10-21T02:05:00Z',
  })])
  assert.ok(csv.startsWith('\uFEFF'))
  assert.match(csv, /任务编号,数据空间文件/)
  assert.match(csv, /"停留,观察"/)
  assert.match(csv, /4 秒/)
  assert.match(csv, /模型/)
})

test('关键节点按 起始 / 转折 / 结束 固定顺序拆成可排版结构，缺失节点不占位', () => {
  const pairs = keyNodePairs([
    { role: 'end', timeMs: 10000, ref: 'c' },
    { role: 'start', timeMs: 0, ref: 'a' },
  ])
  assert.deepEqual(pairs.map((item) => item.label), ['起始', '结束'])
  assert.equal(pairs[0].timeText, '0 秒')
  assert.equal(pairs[1].timeText, '10 秒')
  assert.deepEqual(keyNodePairs([]), [])
})

test('主语/宾语的类别走中文映射、编号单独展示，界面不再出现 类型#编号', () => {
  assert.equal(targetName('person', 'person#T1'), '人')
  assert.equal(targetName('', 'car'), '汽车')
  assert.equal(targetName('', ''), '')
  assert.equal(targetId('T1'), '1')
  assert.equal(targetId(''), '')
  assert.equal(tripleTargetText('person', 'T1', 'person#T1'), 'person#T1')
})
test('类型#编号拆分为类别与编号', () => {
  assert.deepEqual(splitTarget('person#T1'), { type: 'person', id: 'T1' })
  assert.deepEqual(splitTarget('画面'), { type: '画面', id: '' })
  assert.deepEqual(splitTarget(''), { type: '', id: '' })
  assert.deepEqual(splitTarget('a#b#T9'), { type: 'a#b', id: 'T9' })
})
test('界面编号不暴露内部前缀 T，展示为中文类别加数字', () => {
  assert.equal(targetId('T12'), '12')
  assert.equal(targetId('7'), '7')
  assert.equal(targetDisplay('person', 'T12', ''), '人 12')
  assert.equal(targetDisplay('laptop', '', 'laptop'), '笔记本电脑')
})
