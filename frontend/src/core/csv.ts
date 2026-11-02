/**
 * 导出 CSV 的单元格转义与拼接。
 *
 * 时间列必须按文本导出：Excel 会把 `2026-10-26 10:37:33` 识别成日期，
 * 列宽不足时整列显示成 `####`，用户以为数据没导出来。`="…"` 写进单元格后
 * Excel/WPS 都按文本显示，日期再长也不会变成井号。
 */

export function csvCell(value: unknown): string {
  const text = String(value ?? '')
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

/** 时间单元格：空值留空，其余以文本公式形式写入，避免被识别成日期。 */
export function csvTimeCell(value: unknown): string {
  const text = String(value ?? '').trim()
  return text ? `="${text.replace(/"/g, '""')}"` : ''
}

export function buildCsv(rows: unknown[][]): string {
  return rows.map((row) => row.map(csvCell).join(',')).join('\n')
}
