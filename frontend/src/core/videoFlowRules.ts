/* =============================================================================
   core/videoFlowRules.ts — 视频标准化流程配置的纯规则（可在 Node 单测里直接跑）
   -----------------------------------------------------------------------------
   与 specs/videoFlow.ts 的分工：这里只放字段定义、展示文案、草稿归一化与校验，
   页面/接口在 specs/videoFlow.ts（存储于 console-registry 数据集 tf.videoFlow）。
   口径：校验只拦「一定不合法」的输入；缺省值不伪造业务结论。
   ========================================================================== */

export const FLOW_STEP_OPTIONS = [
  { value: 'VIDEO_STANDARD', label: '视频标准化' },
  { value: 'VIDEO_AUDIO', label: '音轨与转写' },
  { value: 'VIDEO_KEYFRAME', label: '抽帧' },
  { value: 'VIDEO_EVENT', label: '事件分析' },
  { value: 'VIDEO_SEMANTIC', label: '复杂场景语义解析' },
  { value: 'VIDEO_SUMMARY', label: '内容总结' },
]

export const FLOW_STEP_LABELS: Record<string, string> = {
  VIDEO_STANDARD: '视频标准化',
  VIDEO_AUDIO: '音轨与转写',
  VIDEO_KEYFRAME: '抽帧',
  VIDEO_EVENT: '事件分析',
  VIDEO_SEMANTIC: '复杂场景语义解析',
  VIDEO_SUMMARY: '内容总结',
}

export const FLOW_FORMAT_OPTIONS = [
  { value: 'mp4', label: 'MP4' },
  { value: 'avi', label: 'AVI' },
  { value: 'mov', label: 'MOV' },
  { value: 'mkv', label: 'MKV' },
]

export const FLOW_FORMAT_LABELS: Record<string, string> = { mp4: 'MP4', avi: 'AVI', mov: 'MOV', mkv: 'MKV' }

export const FLOW_META_OPTIONS = [
  { value: 'title', label: '视频标题' },
  { value: 'shotAt', label: '拍摄时间' },
  { value: 'deviceNo', label: '设备编号' },
]

export const FLOW_META_LABELS: Record<string, string> = {
  title: '视频标题',
  shotAt: '拍摄时间',
  deviceNo: '设备编号',
}

/** 通知方式固定为系统内消息：平台只做触发记录与拉取接口，客户接入自己的消息中心。 */
export const FLOW_CHANNEL_OPTIONS = [
  { value: 'SYSTEM_MESSAGE', label: '系统内消息' },
]

export const FLOW_CHANNEL_LABELS: Record<string, string> = { SYSTEM_MESSAGE: '系统内消息' }

/** 多选值统一成字符串数组（兼容单值 / 逗号串，避免历史数据形状不一致导致页面报错）。 */
export function flowTexts(value: unknown): string[] {
  if (Array.isArray(value)) return value.map((item) => String(item || '').trim()).filter(Boolean)
  const text = String(value || '').trim()
  return text ? text.split(/[,，\s]+/).filter(Boolean) : []
}

/** 值数组 → 中文展示（未知值原样保留，不隐藏）。 */
export function flowLabelsOf(value: unknown, labels: Record<string, string>): string {
  const items = flowTexts(value).map((item) => labels[item] || item)
  return items.length ? items.join('、') : '—'
}

/** 大小范围文案：0/空 表示不限。 */
export function flowSizeText(row: Record<string, unknown>): string {
  const min = Number(row.minSizeMB || 0)
  const max = Number(row.maxSizeMB || 0)
  return `${min > 0 ? `${min} MB` : '不限'} ~ ${max > 0 ? `${max} MB` : '不限'}`
}

/** 时间窗口文案：起止都为空时如实显示「不限时段」。 */
export function flowWindowText(row: Record<string, unknown>): string {
  const start = String(row.windowStart || '').trim()
  const end = String(row.windowEnd || '').trim()
  if (!start && !end) return '不限时段'
  const workdays = String(row.workdaysOnly || '是') === '是' ? '工作日 ' : ''
  return `${workdays}${start || '00:00'}–${end || '23:59'}`
}

/** 事件分析 / 语义摘要依赖抽帧：勾选时自动补上抽帧，避免存下跑不起来的流程。 */
export function withStepDependencies(steps: unknown): string[] {
  const list = flowTexts(steps)
  const needsKeyframe = list.includes('VIDEO_EVENT') || list.includes('VIDEO_SEMANTIC')
  if (needsKeyframe && !list.includes('VIDEO_KEYFRAME')) list.unshift('VIDEO_KEYFRAME')
  return list
}

const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/

/** 表单 → 存储行；只做类型归一，不补业务默认值。 */
export function normalizeFlowDraft(values: Record<string, unknown>): Record<string, unknown> {
  const rules = values.rules && typeof values.rules === 'object' ? values.rules as Record<string, unknown> : {}
  return {
    ...values,
    steps: withStepDependencies(values.steps),
    rules,
    formats: flowTexts(values.formats).map((item) => item.toLowerCase()),
    requiredMeta: flowTexts(values.requiredMeta),
    notifyChannels: ['SYSTEM_MESSAGE'],
    minSizeMB: Number(values.minSizeMB || 0),
    maxSizeMB: Number(values.maxSizeMB || 0),
    corruptSeconds: Number(values.corruptSeconds || 0),
    windowStart: String(values.windowStart || '').trim(),
    windowEnd: String(values.windowEnd || '').trim(),
    notifyReceivers: '',
  }
}

/** 保存前校验；返回中文错误文案，通过返回 null。 */
export function validateFlowDraft(values: Record<string, unknown>): string | null {
  if (!String(values.name || '').trim()) return '请填写流程名称'
  const steps = withStepDependencies(values.steps)
  if (!steps.length) return '请至少勾选一个执行节点'
  if (steps.includes('VIDEO_SUMMARY')
      && !['VIDEO_AUDIO', 'VIDEO_EVENT', 'VIDEO_SEMANTIC'].some((step) => steps.includes(step)))
    return '内容总结需要同时勾选音轨与转写、事件分析或复杂场景语义解析'
  if (!flowTexts(values.formats).length) return '请至少勾选一种触发格式'
  const min = Number(values.minSizeMB || 0)
  const max = Number(values.maxSizeMB || 0)
  if (min < 0 || max < 0) return '文件大小不能为负数'
  if (max > 0 && min > max) return '最小文件大小不能大于最大文件大小'
  const start = String(values.windowStart || '').trim()
  const end = String(values.windowEnd || '').trim()
  if (!start || !end) return '请填写完整的时间窗口（HH:mm）'
  if (!HHMM.test(start) || !HHMM.test(end)) return '时间窗口须为 24 小时制 HH:mm'
  if (start >= end) return '时间窗口的开始时间须早于结束时间'
  const corrupt = Number(values.corruptSeconds || 0)
  if (corrupt <= 0) return '损坏判定阈值须大于 0 秒'
  return null
}

/* -----------------------------------------------------------------------------
   触发判定（自检用；S3 的服务端判定与此保持同一口径）
   -------------------------------------------------------------------------- */

export interface FlowTriggerFile {
  name: string
  sizeBytes: number
  /** 上传/登记时间，ISO 字符串 */
  uploadedAt: string
}

export interface FlowTriggerResult {
  matched: boolean
  code: 'MATCHED' | 'FORMAT_REJECTED' | 'SIZE_REJECTED' | 'WINDOW_REJECTED' | 'META_INCOMPLETE'
  message: string
}

const pad2 = (value: number): string => String(value).padStart(2, '0')
const mbText = (bytes: number): string => `${(bytes / 1024 / 1024).toFixed(1)} MB`
const extensionOf = (name: string): string => {
  const index = String(name || '').lastIndexOf('.')
  return index >= 0 ? String(name).slice(index + 1).toLowerCase() : ''
}

/**
 * 判定一个视频文件是否命中某条流程的触发条件。
 * 顺序固定：格式 → 大小 → 时间窗口 → 元数据完整性，返回第一条不满足的原因。
 */
export function evaluateFlowTrigger(input: {
  flow: Record<string, unknown>
  file: FlowTriggerFile
  meta?: Record<string, unknown> | null
}): FlowTriggerResult {
  const flow = input.flow || {}
  const file = input.file
  const formats = flowTexts(flow.formats).map((item) => item.toLowerCase())
  const ext = extensionOf(file.name)
  if (formats.length && !formats.includes(ext)) {
    return {
      matched: false, code: 'FORMAT_REJECTED',
      message: `格式 ${ext ? ext.toUpperCase() : '未知'} 不在触发白名单 ${formats.map((item) => item.toUpperCase()).join(' / ')}`,
    }
  }

  const minBytes = Number(flow.minSizeMB || 0) * 1024 * 1024
  const maxBytes = Number(flow.maxSizeMB || 0) * 1024 * 1024
  if (minBytes > 0 && file.sizeBytes < minBytes) {
    return { matched: false, code: 'SIZE_REJECTED', message: `文件 ${mbText(file.sizeBytes)} 小于下限 ${Number(flow.minSizeMB)} MB` }
  }
  if (maxBytes > 0 && file.sizeBytes > maxBytes) {
    return { matched: false, code: 'SIZE_REJECTED', message: `文件 ${mbText(file.sizeBytes)} 超过上限 ${Number(flow.maxSizeMB)} MB` }
  }

  const start = String(flow.windowStart || '').trim()
  const end = String(flow.windowEnd || '').trim()
  const uploadedAt = new Date(file.uploadedAt)
  if (start && end && !Number.isNaN(uploadedAt.getTime())) {
    const weekdaysOnly = String(flow.workdaysOnly || '是') === '是'
    const day = uploadedAt.getDay()
    if (weekdaysOnly && (day === 0 || day === 6)) {
      return { matched: false, code: 'WINDOW_REJECTED', message: `上传时间是周末，不在工作日窗口 ${start}–${end}` }
    }
    const time = `${pad2(uploadedAt.getHours())}:${pad2(uploadedAt.getMinutes())}`
    if (time < start || time > end) {
      return { matched: false, code: 'WINDOW_REJECTED', message: `上传时间 ${time} 不在窗口 ${start}–${end}` }
    }
  }

  const required = flowTexts(flow.requiredMeta)
  if (required.length) {
    const meta = (input.meta || {}) as Record<string, unknown>
    const missing = required
      .filter((key) => !String(meta[key] ?? '').trim())
      .map((key) => FLOW_META_LABELS[key] || key)
    if (missing.length) {
      return { matched: false, code: 'META_INCOMPLETE', message: `元数据缺失：${missing.join('、')}` }
    }
  }

  return { matched: true, code: 'MATCHED', message: '触发条件全部满足' }
}
