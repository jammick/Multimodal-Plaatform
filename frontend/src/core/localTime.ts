/**
 * 后端时间统一为 UTC ISO 字符串（如 2026-10-23T05:52:30.123Z）。
 * 页面展示必须走这里转成本地时区，禁止直接 `replace('T', ' ').slice(0, 19)`，
 * 否则会出现“本地 13:52、页面 05:52”的 8 小时偏差。
 */
export function localDateTime(value: unknown, fallback = '—'): string {
  const raw = String(value ?? '').trim()
  if (!raw) return fallback
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) {
    /* 非 ISO / 仅时间等历史格式：保留原样，但不再把 Z 当本地时间展示 */
    return raw.replace('T', ' ').replace(/\.\d+/, '').replace(/Z$/, '').slice(0, 19) || fallback
  }
  const pad = (part: number): string => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
