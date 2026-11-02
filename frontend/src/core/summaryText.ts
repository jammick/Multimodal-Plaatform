/**
 * 内容总结展示前清理。
 *
 * 模型偶尔直接返回 `{"summary":"..."}`、代码块包裹的 JSON、被截断的 JSON，
 * 或者 summary 字段里又是一层 JSON；这些都不能原样进界面。
 * 这里只提取总结正文，不改动普通文本。
 */
export function cleanSummaryText(value: unknown): string {
  let text = String(value ?? '').trim()
  for (let round = 0; round < 3; round += 1) {
    const before = text
    text = extractSummary(stripFence(text))
    if (text === before) break
  }
  return text.trim()
}

function stripFence(value: string): string {
  if (!value.startsWith('```')) return value
  const lines = value.split(/\r?\n/)
  const body = lines.length >= 3 ? lines.slice(1, -1).join('\n') : value
  return body.replace(/^\s*json\s*/i, '').trim()
}

function extractSummary(value: string): string {
  const closed = /"summary"\s*:\s*"((?:\\.|[^"\\])*)"/.exec(value)
  if (closed) return unescapeSummary(closed[1])
  const open = /"summary"\s*:\s*"([\s\S]*)/.exec(value)
  if (!open) return value
  const body = unescapeSummary(open[1])
    .replace(/"\s*[,}]?\s*$/, '')
    .trim()
  return body || value
}

function unescapeSummary(value: string): string {
  return value
    .replace(/\\"/g, '"')
    .replace(/\\n/g, '\n')
    .replace(/\\r/g, '')
    .trim()
}
