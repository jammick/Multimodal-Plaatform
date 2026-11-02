/**
 * 瞬时失败重试。
 *
 * 客户现场「excel 多次尝试才导入成功」「log 报服务暂不可用」属上游瞬时不可用
 * （网关/对象存储/服务重启窗口）。对这类错误自动重试一次，比让用户手点「重试失败项」更可靠。
 * 只重试可安全重放的步骤：上传（同 sha256 幂等）与提交入库（服务端按内容指纹幂等）。
 */

const TRANSIENT = /超时|ECONNABORTED|TIMEOUT|网络异常|连接中断|连接被重置|502|503|504|暂不可用|稍后重试|Failed to fetch|Network Error/i

export function isTransientError(error: unknown): boolean {
  const status = Number((error as { status?: number })?.status || 0)
  if (status >= 500) return true
  const message = error instanceof Error ? error.message : String(error ?? '')
  return TRANSIENT.test(message)
}

export interface RetryOptions {
  /** 总尝试次数，默认 2（首次 + 重试 1 次） */
  attempts?: number
  delayMs?: number
  onRetry?: (error: unknown, nextAttempt: number) => void
}

export async function withRetry<T>(task: () => Promise<T>, options: RetryOptions = {}): Promise<T> {
  const attempts = Math.max(1, options.attempts ?? 2)
  const delayMs = Math.max(0, options.delayMs ?? 1500)
  let lastError: unknown
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return await task()
    } catch (error) {
      lastError = error
      if (attempt >= attempts || !isTransientError(error)) throw error
      options.onRetry?.(error, attempt + 1)
      if (delayMs) await new Promise((resolve) => setTimeout(resolve, delayMs * attempt))
    }
  }
  throw lastError
}

/** 给错误补上执行阶段（解析 / 上传 / 提交入库），避免只报「服务暂不可用」看不出哪一步失败 */
export function stageError(stage: string, error: unknown): Error {
  const message = error instanceof Error ? error.message : String(error ?? '')
  if (!message) return new Error(`${stage}失败`)
  if (message.startsWith(stage) || message.includes(`：${stage}`) || message.includes(`${stage}失败`)) {
    return error instanceof Error ? error : new Error(message)
  }
  return new Error(`${stage}：${message}`)
}
