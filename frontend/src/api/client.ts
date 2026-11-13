import createClient from 'openapi-fetch'
import type { paths } from './schema'
import { identityHeaders } from './identity'
import { SESSION_HEADER } from '@/utils/session'

export const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export const client = createClient<paths>({ baseUrl: apiBaseUrl })

/**
 * 统一注入调用者身份。放在中间件里而不是每个调用点手写：
 * 任务查询、取消这类接口没有请求体，漏带身份就会被当成匿名用户而拿到 404。
 */
client.use({
  onRequest({ request }) {
    for (const [name, value] of Object.entries(identityHeaders())) {
      request.headers.set(name, value)
    }
    return request
  },
})

export class ApiError extends Error {
  readonly status: number
  readonly detail: unknown

  constructor(status: number, message: string, detail?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.detail = detail
  }
}

interface ApiResult<T> {
  data?: T
  error?: unknown
  response: Response
}

/**
 * 一次调用的结果：业务数据 + 服务端为这次调用签发的会话 ID。
 *
 * 会话 ID 由服务端签发，客户端要续写就得拿到它；同步接口（目录/章节）的响应体里没有位置，
 * 服务端把它放在 `X-Session-Id` 响应头上，这里统一读出来。
 */
export interface ApiResponse<T> {
  data: T
  sessionId: string | null
}

function readSessionId(response: Response): string | null {
  const raw = response.headers.get(SESSION_HEADER)
  return raw && raw.trim() ? raw.trim() : null
}

/**
 * openapi-fetch 不抛 HTTP 错误，这里统一转成 ApiError，同时把会话 ID 读出来。
 * 兼容 Spring 默认错误体、ProblemDetail 与纯文本错误。
 */
export function unwrapWithSession<T>(result: ApiResult<T>): ApiResponse<T> {
  if (result.error !== undefined) {
    throw new ApiError(result.response.status, extractMessage(result.error), result.error)
  }
  if (result.data === undefined) {
    throw new ApiError(result.response.status, '服务端未返回数据')
  }
  return { data: result.data, sessionId: readSessionId(result.response) }
}

/** 只关心业务数据的调用点用这个：会话 ID 由任务型接口的响应体自带 */
export function unwrap<T>(result: ApiResult<T>): T {
  return unwrapWithSession(result).data
}

function extractMessage(error: unknown): string {
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
