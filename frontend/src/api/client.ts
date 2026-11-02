import createClient from 'openapi-fetch'
import type { paths } from './schema'

export const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export const client = createClient<paths>({ baseUrl: apiBaseUrl })

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
 * openapi-fetch 不抛 HTTP 错误，这里统一转成 ApiError。
 * 兼容 Spring 默认错误体、ProblemDetail 与纯文本错误。
 */
export function unwrap<T>(result: ApiResult<T>): T {
  if (result.error !== undefined) {
    throw new ApiError(result.response.status, extractMessage(result.error), result.error)
  }
  if (result.data === undefined) {
    throw new ApiError(result.response.status, '服务端未返回数据')
  }
  return result.data
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
