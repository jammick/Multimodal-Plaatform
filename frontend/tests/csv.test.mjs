import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { buildCsv, csvCell, csvTimeCell } from '../src/core/csv.ts'

test('含逗号/引号/换行的单元格按 CSV 规则转义', () => {
  assert.equal(csvCell('普通'), '普通')
  assert.equal(csvCell('a,b'), '"a,b"')
  assert.equal(csvCell('说"明"'), '"说""明"""')
  assert.equal(csvCell('第一行\n第二行'), '"第一行\n第二行"')
  assert.equal(csvCell(null), '')
})

test('时间单元格按文本写入，Excel 不会把它识别成日期', () => {
  assert.equal(csvTimeCell('2026-10-26 10:37:33'), '="2026-10-26 10:37:33"')
  assert.equal(csvTimeCell(''), '')
  assert.equal(csvTimeCell(null), '')
})

test('拼成一行后可被 Excel 还原成 ="" 文本公式', () => {
  const line = buildCsv([['V4', csvTimeCell('2026-10-26 10:45:00')]])
  assert.equal(line, 'V4,"=""2026-10-26 10:45:00"""')
})
