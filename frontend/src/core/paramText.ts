/** 步骤参数值转可读文本：对象/数组展开成 key=value，避免界面出现 [object Object]。 */
export function paramText(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') return String(value)
  if (Array.isArray(value)) return value.map(paramText).filter(Boolean).join('、')
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([key, item]) => !['requestId', 'batchId'].includes(key) && paramText(item) !== '')
      .map(([key, item]) => `${key}=${paramText(item)}`)
    return entries.length ? entries.join(', ') : ''
  }
  return String(value)
}

/** 过滤空参数后按 `key=value` 拼成一行，供运行日志/参数区展示。 */
export function paramLine(params: Record<string, unknown> | null | undefined, skip: string[] = []): string {
  if (!params) return ''
  return Object.entries(params)
    .filter(([key]) => !skip.includes(key) && !['requestId', 'batchId'].includes(key))
    .map(([key, value]) => `${key}=${paramText(value)}`)
    .filter((item) => !item.endsWith('='))
    .join(', ')
}
