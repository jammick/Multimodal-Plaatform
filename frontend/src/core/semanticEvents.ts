/* =============================================================================
   core/semanticEvents.ts — 复杂场景语义解析的结构化结果（纯函数，可在 Node 单测里直接跑）
   -----------------------------------------------------------------------------
   口径：
   * 时间全部按整秒展示（如 15 秒），不用「约 / 左右」这类模糊说法；
   * 主语 / 客体优先显示「类型 + 轨迹编号」，没有匹配到真实轨迹时如实显示模型文本、编号留空；
   * 事件链标签直接取后端返回的 chain_tag，不在前端二次拼造。
   ========================================================================== */

import { objectLabelZh } from './objectLabels.ts'

export interface SemanticKeyNode {
  role: string
  timeMs: number
  ref: string
}

export interface SemanticEvent {
  startMs: number
  endMs: number
  subject: string
  subjectType: string
  subjectId: string
  predicate: string
  object: string
  objectType: string
  objectId: string
  frameRef: string
  /** 因果判断：由模型给出的一句前置原因，空表示模型未判断 */
  cause: string
  keyNodes: SemanticKeyNode[]
}

export interface SemanticEventRow {
  taskId: string
  fileId: string
  ruleId: string
  ruleVersion: string
  seq: number
  startMs: number
  endMs: number
  subject: string
  subjectType: string
  subjectId: string
  predicate: string
  object: string
  objectType: string
  objectId: string
  chainTag: string
  source: string
  /** 因果判断：模型给出的一句前置原因 */
  cause: string
  finishedAt: string
}

const number = (value: unknown, fallback = 0): number => {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

const text = (value: unknown): string => String(value ?? '').trim()

/** 统一把「类型#编号」拆开，便于中文映射与编号单独展示。 */
export function splitTarget(value: unknown): { type: string; id: string } {
  const raw = String(value ?? '').trim()
  if (!raw) return { type: '', id: '' }
  const index = raw.lastIndexOf('#')
  if (index <= 0) return { type: raw, id: '' }
  return { type: raw.slice(0, index).trim(), id: raw.slice(index + 1).trim() }
}

/** 识别到的目标类型：优先取轨迹类别，其次取事件里的主语/宾语类型；去重保序，不补造。 */
export function recognizedTargetTypes(
  tracks: unknown,
  events: SemanticEvent[],
): string[] {
  const result: string[] = []
  const push = (value: unknown): void => {
    const text = String(value ?? '').trim()
    if (!text || text === '画面' || result.includes(text)) return
    result.push(text)
  }
  if (Array.isArray(tracks)) {
    tracks.forEach((track) => {
      if (track && typeof track === 'object') push((track as Record<string, unknown>).label)
    })
  }
  ;(events || []).forEach((event) => {
    push(event.subjectType)
    push(event.objectType)
  })
  return result
}

/** 毫秒 → 整秒文案（如 15 秒）；秒是界面最小时间单位，不显示毫秒。 */
export function formatSecondsText(value: unknown): string {
  const total = Math.max(0, Math.round(number(value)))
  return `${Math.round(total / 1000)} 秒`
}

/** 关键节点一句话：起始 / 转折 / 结束，取不到的节点不占位。 */
export function keyNodeText(nodes: SemanticKeyNode[]): string {
  const labels: Record<string, string> = { start: '起始', turning: '转折', end: '结束' }
  const parts = (nodes || [])
    .filter((node) => labels[node.role])
    .map((node) => `${labels[node.role]} ${formatSecondsText(node.timeMs)}`)
  return parts.join(' · ')
}

/** 主语 / 宾语的展示文本：有轨迹编号时写成「类型#编号」。 */
export function tripleTargetText(type: string, id: string, fallback: string): string {
  const name = text(type) || text(fallback)
  const track = text(id)
  if (!name) return '—'
  return track ? `${name}#${track}` : name
}

/** 主语 / 宾语的类别名（中文映射，未知类别原样返回，空值返回空串）。 */
export function targetName(type: string, fallback = ''): string {
  return objectLabelZh(text(type) || text(fallback))
}

/** 轨迹编号单独展示；界面上只显示数字，不暴露内部前缀 T（数据里仍保留 T1 便于追溯）。 */
export function targetId(id: string): string {
  return text(id).replace(/^T(?=\d)/i, '')
}

/** 界面统一展示：中文类别 + 数字编号（没有编号时只显示类别）。 */
export function targetDisplay(type: string, id: string, fallback = ''): string {
  const name = targetName(type, fallback)
  const numeric = targetId(id)
  return numeric ? `${name} ${numeric}` : name
}

export interface KeyNodePair {
  label: string
  timeText: string
}

/** 关键节点拆成可排版的固定顺序（起始 / 转折 / 结束），取不到的节点不占位。 */
export function keyNodePairs(nodes: SemanticKeyNode[]): KeyNodePair[] {
  const labels: Record<string, string> = { start: '起始', turning: '转折', end: '结束' }
  const order = ['start', 'turning', 'end']
  const available = nodes || []
  return order
    .map((role) => {
      const node = available.find((item) => item.role === role)
      return node ? { label: labels[role], timeText: formatSecondsText(node.timeMs) } : null
    })
    .filter((pair): pair is KeyNodePair => pair !== null)
}

/** 后端事件（含历史形状）→ 统一结构；缺字段给空值，不猜。 */
export function toSemanticEvent(raw: Record<string, unknown>): SemanticEvent {
  const startMs = number(raw.start_ms ?? raw.startMs)
  const endMs = Math.max(startMs, number(raw.end_ms ?? raw.endMs, startMs))
  const nodes = Array.isArray(raw.key_nodes) ? raw.key_nodes as Record<string, unknown>[] : []
  return {
    startMs,
    endMs,
    subject: text(raw.subject),
    subjectType: text(raw.subject_type ?? raw.subjectType),
    subjectId: text(raw.subject_id ?? raw.subjectId),
    predicate: text(raw.predicate),
    object: text(raw.object),
    objectType: text(raw.object_type ?? raw.objectType),
    objectId: text(raw.object_id ?? raw.objectId),
    frameRef: text(raw.frame_ref),
    cause: text(raw.cause),
    keyNodes: nodes.map((node) => ({
      role: text(node.role),
      timeMs: number(node.time_offset_ms ?? node.timeMs),
      ref: text(node.storage_ref ?? node.ref),
    })),
  }
}

const csvCell = (value: unknown): string => {
  const content = String(value ?? '')
  return /[",\n\r]/.test(content) ? `"${content.replace(/"/g, '""')}"` : content
}

/** 语义三元组导出（UTF-8 BOM，表格软件直接打开不乱码）。 */
export function semanticTriplesCsv(rows: SemanticEventRow[]): string {
  const header = ['任务编号', '数据空间文件', '规则编号', '规则版本', '序号', '开始时间',
    '结束时间', '主语类型', '主语编号', '谓语动作', '宾语类型', '宾语编号', '事件链标签', '因果判断', '来源']
  const lines = [header.join(',')]
  rows.forEach((row) => {
    lines.push([
      row.taskId, row.fileId, row.ruleId, row.ruleVersion, row.seq,
      formatSecondsText(row.startMs), formatSecondsText(row.endMs),
      row.subjectType, row.subjectId, row.predicate, row.objectType, row.objectId,
      row.chainTag, row.cause, row.source === 'track' ? '轨迹' : '模型',
    ].map(csvCell).join(','))
  })
  return `\uFEFF${lines.join('\n')}\n`
}

/** 把后端返回的列表行归一成可展示结构（防止字段缺失导致表格报错）。 */
export function toSemanticEventRow(raw: Record<string, unknown>): SemanticEventRow {
  return {
    taskId: text(raw.taskId),
    fileId: text(raw.fileId),
    ruleId: text(raw.ruleId),
    ruleVersion: text(raw.ruleVersion),
    seq: number(raw.seq),
    startMs: number(raw.startMs),
    endMs: number(raw.endMs),
    subject: text(raw.subject),
    subjectType: text(raw.subjectType),
    subjectId: text(raw.subjectId),
    predicate: text(raw.predicate),
    object: text(raw.object),
    objectType: text(raw.objectType),
    objectId: text(raw.objectId),
    chainTag: text(raw.chainTag),
    source: text(raw.source),
    cause: text(raw.cause),
    finishedAt: text(raw.finishedAt),
  }
}
