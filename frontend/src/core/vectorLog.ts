/**
 * 向量知识库「运行日志」生成：维护动作 + 入库任务，按真实字段展开成详细日志块。
 * 数据来源都是真实接口（/vector/monitor/ops、/vector/knowledge/jobs），
 * 取不到的字段不输出，不做任何推演。
 */
import { type LogBlock, logField, logFields, logTime } from './logBlocks.ts'
import { paramText } from './paramText.ts'

const ACTION_LABELS: Record<string, string> = {
  INDEX_REBUILD: '重建索引',
  RATE_LIMIT: '查询限流',
  POOL_SCALE: '连接池扩容',
}

const RESULT_LABELS: Record<string, string> = {
  INDEX_READY: '索引就绪',
  RATE_LIMIT_SET: '限流已生效',
  POOL_RESIZED: '连接池已调整',
}

/** 操作人去敏：历史记录里的占位值不当作真人 */
function operatorText(value: unknown): string {
  const text = String(value || '').trim()
  if (!text || text === 'CURRENT_USER') return '历史未记录'
  return text
}

/** 业务状态统一成中文（后端状态值已是中文，这里只兜底英文） */
function statusText(value: unknown): string {
  const text = String(value || '').trim()
  if (!text) return '状态未记录'
  const map: Record<string, string> = {
    PENDING: '待处理', PARSING: '解析中', PROCESSING: '向量化中',
    SUCCESS: '成功', FAILED: '失败', CANCELED: '已取消', CANCELLED: '已取消',
  }
  return map[text.toUpperCase()] || text
}

/** 参数逐项展开（对象/数组按 key=value 展开，避免出现 [object Object]） */
function paramLines(params: unknown): string[] {
  if (!params || typeof params !== 'object' || Array.isArray(params)) return []
  const entries = Object.entries(params as Record<string, unknown>)
    .filter(([key]) => !['requestId', 'batchId'].includes(key))
    .map(([key, value]) => [key, paramText(value)] as const)
    .filter(([, value]) => value !== '')
  if (!entries.length) return []
  return ['  生效参数：', ...entries.map(([key, value]) => `    · ${key} = ${value}`)]
}

/** 维护动作日志块 */
function opsBlock(row: Record<string, unknown>, scope: string): LogBlock {
  const type = String(row.actionType || '')
  const result = String(row.result || '')
  const at = logTime(row.executedAt || row.createdAt)
  const resultText = RESULT_LABELS[result] || result
  const typeText = ACTION_LABELS[type] || type || '维护动作'
  return {
    head: `[${at || '时间未记录'}] 维护 · ${typeText} · ${resultText || '已执行'}`,
    lines: logFields([
      logField('动作编号', row.actionId),
      logField('动作类型', ACTION_LABELS[type] ? `${ACTION_LABELS[type]}（${type}）` : type),
      logField('作用对象', scope),
      logField('执行结果', result ? (RESULT_LABELS[result] ? `${RESULT_LABELS[result]}（${result}）` : result) : ''),
      logField('执行原因', row.reason),
      logField('操作人', operatorText(row.operator)),
      logField('执行时间', at),
      ...paramLines(row.params),
    ]),
  }
}

/** 入库任务日志块 */
function jobBlock(row: Record<string, unknown>, fileNameOf: (fileId: string) => string,
                  modelLabelOf?: (model: string) => string): LogBlock {
  const at = logTime(row.at || row.createdAt)
  const fileId = String(row.fileId || '')
  const fileLabel = fileId ? fileNameOf(fileId) : '（来源文件未记录）'
  const chunks = Number(row.chunks ?? 0)
  const totalRaw = row.totalChunks
  const total = totalRaw === null || totalRaw === undefined || totalRaw === '' ? null : Number(totalRaw)
  const strategy = String(row.strategy || '')
  const chunkSize = row.chunkSize === null || row.chunkSize === undefined ? '' : String(row.chunkSize)
  const overlap = row.overlap === null || row.overlap === undefined ? '' : String(row.overlap)
  const model = String(row.model || '')
  const flags = [row.withdrawn === true ? '向量已撤回' : '', row.archived === true ? '已归档' : ''].filter(Boolean)
  return {
    head: `[${at || '时间未记录'}] 入库 · ${fileLabel} · ${statusText(row.status)}${flags.length ? `（${flags.join('、')}）` : ''}`,
    lines: logFields([
      logField('任务编号', row.id),
      logField('目标知识库', row.collection ? `${row.collection}（${row.collectionId || ''}）` : row.collectionId),
      logField('来源文件', fileId ? `${fileLabel}（${fileId}）` : ''),
      logField('来源加工任务', row.sourceTaskId),
      logField('切分策略', strategy),
      logField('分块长度', chunkSize),
      logField('重叠长度', overlap),
      logField('切片结果', total === null ? (chunks ? `${chunks} 片` : '') : `${chunks}/${total} 片`),
      logField('向量模型', model ? (modelLabelOf?.(model) || model) : ''),
      logField('记录时间', at),
      logField('失败原因', row.error),
    ]),
  }
}

export interface VectorLogInput {
  collectionId?: string
  collectionName?: string
  ops?: Record<string, unknown>[]
  jobs?: Record<string, unknown>[]
  /** 文件 ID → 文件名称（取不到就显示文件 ID，不编造） */
  fileNameOf?: (fileId: string) => string
  /** 向量模型 ID → 模型名称 */
  modelLabelOf?: (model: string) => string
}

/**
 * 生成知识库运行日志块：首块是日志范围概览，其后按时间倒序排列维护动作与入库任务。
 * 维护动作按 actionId 去重（详情页会把本库动作与平台级动作合并后传入）。
 */
export function buildVectorLogBlocks(input: VectorLogInput): LogBlock[] {
  const fileNameOf = input.fileNameOf || ((fileId: string) => fileId || '（未记录）')
  const scope = input.collectionName
    ? `知识库 ${input.collectionName}${input.collectionId ? `（${input.collectionId}）` : ''}`
    : (input.collectionId ? `知识库 ${input.collectionId}` : '平台级（全部知识库）')
  const seen = new Set<string>()
  const ops = (input.ops || []).filter((row) => {
    const key = String(row.actionId || '')
    if (!key) return true
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
  const jobs = input.jobs || []
  const entries: { at: string; block: LogBlock }[] = [
    ...ops.map((row) => ({ at: logTime(row.executedAt || row.createdAt), block: opsBlock(row, scope) })),
    ...jobs.map((row) => ({ at: logTime(row.at || row.createdAt), block: jobBlock(row, fileNameOf, input.modelLabelOf) })),
  ].sort((a, b) => (a.at < b.at ? 1 : a.at > b.at ? -1 : 0))

  const summary: LogBlock = {
    head: `==== ${input.collectionName || '向量数据服务'} · 运行日志 ====`,
    lines: logFields([
      logField('日志范围', scope),
      logField('维护动作', `${ops.length} 条`),
      logField('入库任务', `${jobs.length} 条`),
    ]),
  }
  return [summary, ...entries.map((entry) => entry.block)]
}
