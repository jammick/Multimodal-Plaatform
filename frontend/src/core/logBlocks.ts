/**
 * 运行日志通用结构：一个动作/节点一个日志块，块内逐项标注真实字段。
 * 约定：
 * - 只输出接口真实返回的字段，取不到就不输出该行（不显示「—」、不补零、不编造）；
 * - 关键词过滤以「块」为单位（命中任一行即保留整块），避免日志被切成半截。
 */
import { localDateTime } from './localTime.ts'

export interface LogBlock {
  /** 首行：时间 + 事件 + 结果 */
  head: string
  /** 明细行（每行已带缩进） */
  lines: string[]
}

/** 一行明细 `  标签：值`；空值返回 null（调用方用 logFields 过滤） */
export function logField(label: string, value: unknown): string | null {
  if (value === null || value === undefined) return null
  const text = String(value).trim()
  if (!text) return null
  return `  ${label}：${text}`
}

/** 过滤空行，拼成明细行数组 */
export function logFields(rows: (string | null | undefined | false)[]): string[] {
  return rows.filter((row): row is string => typeof row === 'string' && row.length > 0)
}

/** 按关键词过滤日志块，再输出终端风格文本（块之间空一行） */
export function renderLogBlocks(blocks: LogBlock[], keyword = ''): string {
  const key = keyword.trim()
  const kept = key ? blocks.filter((block) => `${block.head}\n${block.lines.join('\n')}`.includes(key)) : blocks
  return kept.map((block) => [block.head, ...block.lines].join('\n')).join('\n\n')
}

/** 时间统一成 `YYYY-MM-DD HH:mm:ss`（后端 UTC 按浏览器本地时区展示） */
export function logTime(value: unknown): string {
  const text = String(value || '').trim()
  if (!text) return ''
  return localDateTime(text, '')
}
