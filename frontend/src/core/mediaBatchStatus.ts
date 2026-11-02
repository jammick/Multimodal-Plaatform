/**
 * 自动处理触发记录的执行状态：触发记录只保存 batchId，任务真实执行状态在编排服务。
 * 这里把「批次」聚合成一个可展示状态，避免页面把「已提交处理」当成「处理成功」。
 */
export interface BatchItemLike {
  status?: string | null
  failReason?: string | null
  finishedAt?: string | null
}

export interface BatchLike {
  batchId?: string | null
  items?: BatchItemLike[] | null
}

export type MediaTaskStatus = 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'CANCELED' | 'UNKNOWN'

export interface BatchState {
  status: MediaTaskStatus
  total: number
  running: number
  failed: number
  canceled: number
  failReason: string
  finishedAt: string
}

const RUNNING_STATUSES = new Set(['PENDING', 'PROCESSING', 'QC', 'FAILED_RETRYABLE'])
const CANCELED_REASON = 'CANCELED'

/** 一个批次内可能有多条文件任务，状态取最靠后的真实结果：处理中 > 失败 > 已取消 > 成功。 */
export function summarizeBatch(batch: BatchLike): BatchState {
  const items = Array.isArray(batch.items) ? batch.items : []
  const running = items.filter((item) => RUNNING_STATUSES.has(String(item.status || ''))).length
  const failedItems = items.filter((item) => String(item.status || '') === 'FAILED_TERMINAL'
    && String(item.failReason || '') !== CANCELED_REASON)
  const canceled = items.filter((item) => String(item.status || '') === 'FAILED_TERMINAL'
    && String(item.failReason || '') === CANCELED_REASON).length
  let status: MediaTaskStatus = 'UNKNOWN'
  if (items.length) {
    if (running) status = 'PROCESSING'
    else if (failedItems.length) status = 'FAILED'
    else if (canceled === items.length) status = 'CANCELED'
    else status = 'SUCCESS'
  }
  const finished = items
    .map((item) => Date.parse(String(item.finishedAt || '')))
    .filter((value) => Number.isFinite(value))
  return {
    status,
    total: items.length,
    running,
    failed: failedItems.length,
    canceled,
    failReason: String(failedItems[0]?.failReason || ''),
    finishedAt: finished.length ? new Date(Math.max(...finished)).toISOString() : '',
  }
}

/** 当前与已归档批次一起映射，归档后的任务仍能看到最终状态。 */
export function batchStateMap(batches: BatchLike[]): Record<string, BatchState> {
  const map: Record<string, BatchState> = {}
  for (const batch of batches) {
    const batchId = String(batch.batchId || '').trim()
    if (batchId) map[batchId] = summarizeBatch(batch)
  }
  return map
}

export function taskStatusText(status: unknown): string {
  return ({
    PROCESSING: '处理中',
    SUCCESS: '处理成功',
    FAILED: '处理失败',
    CANCELED: '已取消',
    UNKNOWN: '未找到任务',
  } as Record<string, string>)[String(status || '')] || '—'
}

export function taskStatusTone(status: unknown): 'success' | 'danger' | 'warning' | 'info' {
  const code = String(status || '')
  if (code === 'SUCCESS') return 'success'
  if (code === 'FAILED') return 'danger'
  if (code === 'PROCESSING') return 'warning'
  return 'info'
}
