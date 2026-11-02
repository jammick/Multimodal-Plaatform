import test from 'node:test'
import assert from 'node:assert/strict'
import { localDateTime } from '../src/core/localTime.ts'

test('UTC stored time is rendered in the browser local timezone', () => {
  const raw = '2026-10-23T05:52:30Z'
  const date = new Date(raw)
  const pad = (part) => String(part).padStart(2, '0')
  const expected = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  assert.equal(localDateTime(raw), expected)
})

test('empty or invalid values keep a readable fallback', () => {
  assert.equal(localDateTime(''), '—')
  assert.equal(localDateTime('2026-10-23 05:52:30'), '2026-10-23 05:52:30')
})
