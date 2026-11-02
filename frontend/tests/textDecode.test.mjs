import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { decodeTextBuffer, detectEncoding, sniffContainer, sniffOfficeZip } from '../src/core/textDecode.ts'

/** GBK 编码的「姓名,部门\n张三,运维一班\n」 */
const GBK_CSV = [208, 213, 195, 251, 44, 178, 191, 195, 197, 10, 213, 197, 200, 253, 44, 212, 203, 206, 172, 210, 187, 176, 224, 10]

const bytesOf = (list) => new Uint8Array(list).buffer

test('a GBK encoded csv is decoded as Chinese instead of mojibake', () => {
  const decoded = decodeTextBuffer(bytesOf(GBK_CSV))
  assert.equal(decoded.encoding, 'gb18030')
  assert.equal(decoded.text, '姓名,部门\n张三,运维一班')
  assert.equal(decoded.binary, false)
  assert.ok(!decoded.text.includes('\uFFFD'))
})

test('a UTF-8 csv keeps its text and drops the BOM', () => {
  const raw = new TextEncoder().encode('姓名,部门\n张三,运维一班\n')
  const withBom = new Uint8Array(raw.length + 3)
  withBom.set([0xef, 0xbb, 0xbf])
  withBom.set(raw, 3)
  const decoded = decodeTextBuffer(withBom.buffer)
  assert.equal(decoded.encoding, 'utf-8')
  assert.equal(decoded.text, '姓名,部门\n张三,运维一班')
})

test('an xlsx renamed to .csv is recognised as a zip container instead of being read as text', () => {
  const head = new Uint8Array([0x50, 0x4b, 0x03, 0x04, ...new TextEncoder().encode('xl/workbook.xml')])
  assert.equal(sniffContainer(head), 'zip')
  assert.equal(sniffOfficeZip(head), 'xlsx')
})

test('a pdf is recognised by its magic bytes even when the extension is wrong', () => {
  const head = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37])
  assert.equal(sniffContainer(head), 'pdf')
})

test('legacy office compound files are recognised as OLE containers', () => {
  const head = new Uint8Array([0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1, 0, 0])
  assert.equal(sniffContainer(head), 'ole')
})

test('binary content is reported as binary so the page can refuse it', () => {
  const binary = new Uint8Array(200)
  for (let i = 0; i < binary.length; i += 1) binary[i] = i % 32
  const decoded = decodeTextBuffer(binary.buffer)
  assert.equal(decoded.binary, true)
})

test('plain utf-8 text selects utf-8 without falling back to gb18030', () => {
  const buffer = new TextEncoder().encode('设备编号,巡检日期\nPD-001,2026-10-20').buffer
  assert.equal(detectEncoding(buffer), 'utf-8')
})
