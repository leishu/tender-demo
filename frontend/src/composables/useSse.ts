import { fetchEventSource, type EventSourceMessage } from '@microsoft/fetch-event-source'
import { isAbortError } from '@/utils/async'

export interface SseRequestOptions {
  method?: 'GET' | 'POST'
  headers?: Record<string, string>
  body?: unknown
  signal?: AbortSignal
  onMessage: (data: string, message: EventSourceMessage) => void
  onOpen?: (response: Response) => void
  onClose?: () => void
  onError?: (error: unknown) => void
}

/**
 * 后端 `/tender/parse` 是 POST + SSE，原生 EventSource 不支持 POST，
 * 因此统一用 fetch-event-source。默认关闭自动重连：解析不是幂等操作。
 */
export async function streamSse(url: string, options: SseRequestOptions): Promise<void> {
  const controller = new AbortController()
  const signal = options.signal
    ? AbortSignal.any([options.signal, controller.signal])
    : controller.signal

  try {
    await fetchEventSource(url, {
      method: options.method ?? 'POST',
      headers: {
        Accept: 'text/event-stream',
        ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...options.headers,
      },
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal,
      openWhenHidden: true,
      async onopen(response) {
        const contentType = response.headers.get('content-type') ?? ''
        if (response.ok && contentType.includes('text/event-stream')) {
          options.onOpen?.(response)
          return
        }
        const text = await response.text().catch(() => '')
        throw new Error(text || `SSE 连接失败（HTTP ${response.status}）`)
      },
      onmessage(message) {
        // 保活注释帧（服务端每 30s 一条 `: ping`）会以空 data 到达，直接丢弃：
        // 业务侧不必再对空串做防御，也不该因此触发任何状态变更
        if (!message.data) {
          return
        }
        options.onMessage(message.data, message)
      },
      onclose() {
        options.onClose?.()
      },
      onerror(error) {
        options.onError?.(error)
        // 抛出后 fetch-event-source 不再自动重试。
        throw error
      },
    })
  } catch (error) {
    if (!isAbortError(error)) {
      throw error
    }
  }
}
