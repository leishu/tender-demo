export function isAbortError(error: unknown): boolean {
  return (
    (error instanceof DOMException && error.name === 'AbortError') ||
    (error instanceof Error && error.name === 'AbortError')
  )
}

export function toErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message
  }
  if (typeof error === 'string' && error.trim()) {
    return error
  }
  if (error && typeof error === 'object') {
    const record = error as Record<string, unknown>
    for (const key of ['message', 'detail', 'error', 'title']) {
      const value = record[key]
      if (typeof value === 'string' && value.trim()) {
        return value
      }
    }
  }
  return '请求失败，请稍后重试'
}

/**
 * 给长任务加超时，同时保留调用方主动取消的能力。
 */
export function withTimeout(signal: AbortSignal | undefined, timeoutMs: number): AbortSignal {
  const timeout = AbortSignal.timeout(timeoutMs)
  return signal ? AbortSignal.any([signal, timeout]) : timeout
}

export function formatTime(value?: number | null): string {
  if (!value) {
    return '-'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

/**
 * 按 `YYYY-MM-DD HH:mm:ss` 输出本地时间，秒级精度。
 * 传空值或非法时间返回 `-`，避免列表出现 `Invalid Date`。
 */
export function formatDateTime(value?: number | null): string {
  if (value == null || value === 0) {
    return '-'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '-'
  }
  const pad = (part: number) => String(part).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    ` ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}
