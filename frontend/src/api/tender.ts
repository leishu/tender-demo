import { apiBaseUrl, client, unwrap } from './client'
import type {
  CatalogAdjustRequest,
  CatalogRequest,
  CatalogResult,
  CatalogSaveRequest,
  ChapterRequest,
  TenderRequirements,
  TenderRequirementSummary,
  TenderTaskCreated,
  TenderTaskView,
} from './types'
import { withTimeout } from '@/utils/async'

const SHORT_TIMEOUT = 30_000
const LONG_TIMEOUT = 180_000

export const tenderApi = {
  /** 查询已解析（已入库）的招标文件列表，按最近入库倒序 */
  async listRequirements(
    params: { keyword?: string; limit?: number } = {},
    signal?: AbortSignal,
  ): Promise<TenderRequirementSummary[]> {
    const result = await client.GET('/tender/requirements', {
      params: { query: params },
      signal: withTimeout(signal, SHORT_TIMEOUT),
    })
    return unwrap<TenderRequirementSummary[]>(result)
  },

  /**
   * 创建解析任务，立即返回 taskId；进度通过事件流订阅。
   *
   * 同一个 idempotencyKey 重复提交会返回同一个任务，不会重复调用模型。
   */
  async createParseTask(
    body: TenderRequestBody,
    signal?: AbortSignal,
    idempotencyKey?: string,
  ): Promise<TenderTaskCreated> {
    const result = await client.POST('/tender/parse', {
      body,
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
      signal: withTimeout(signal, SHORT_TIMEOUT),
    })
    return unwrap<TenderTaskCreated>(result)
  },

  /**
   * 创建人员/资质匹配任务，立即返回 taskId；进度通过事件流订阅。
   *
   * 与解析任务同构：同一个 idempotencyKey 重复提交会返回同一个任务，
   * 不会让两路子 Agent 再跑一遍。
   */
  async createMatchTask(
    body: MatchRequestBody,
    signal?: AbortSignal,
    idempotencyKey?: string,
  ): Promise<TenderTaskCreated> {
    const result = await client.POST('/tender/match', {
      body,
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
      signal: withTimeout(signal, SHORT_TIMEOUT),
    })
    return unwrap<TenderTaskCreated>(result)
  },

  /**
   * 任务事件流地址。lastEventId > 0 时从该序号之后续传，
   * 与 SSE 的 Last-Event-ID 语义一致。
   */
  taskEventsUrl(taskId: number, lastEventId = 0): string {
    const query = lastEventId > 0 ? `?lastEventId=${lastEventId}` : ''
    return `${apiBaseUrl}/tender/tasks/${taskId}/events${query}`
  },

  /** 查询任务状态，用于事件流断开后判断任务是否仍在执行 */
  async getTask(taskId: number, signal?: AbortSignal): Promise<TenderTaskView> {
    const result = await client.GET('/tender/tasks/{taskId}', {
      params: { path: { taskId } },
      signal: withTimeout(signal, SHORT_TIMEOUT),
    })
    return unwrap<TenderTaskView>(result)
  },

  /** 取消任务：服务端会断开上游并落一条 error 事件，响应 204 无响应体 */
  async cancelTask(taskId: number, signal?: AbortSignal): Promise<void> {
    await client.POST('/tender/tasks/{taskId}/cancel', {
      params: { path: { taskId } },
      signal: withTimeout(signal, SHORT_TIMEOUT),
    })
  },

  /**
   * 确认入库。传入 idempotencyKey 时，服务端同一 key 只会入库一次，
   * 重复点击/网络重试会拿回首次创建的主键，不会产生重复数据。
   */
  async confirm(
    body: TenderRequirements,
    signal?: AbortSignal,
    idempotencyKey?: string,
  ): Promise<number> {
    const result = await client.POST('/tender/parse/confirm', {
      body,
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
      signal: withTimeout(signal, SHORT_TIMEOUT),
    })
    return unwrap<number>(result)
  },

  /**
   * 生成投标目录。传入 idempotencyKey 时，命中已完成的任务会直接返回缓存的目录，
   * 不再调用模型（重试、重复点击不会白花一次模型调用）。
   */
  async generateCatalog(
    body: CatalogRequest,
    signal?: AbortSignal,
    idempotencyKey?: string,
  ): Promise<CatalogResult> {
    const result = await client.POST('/tender/catalog', {
      body,
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
      signal: withTimeout(signal, LONG_TIMEOUT),
    })
    return unwrap<CatalogResult>(result)
  },

  async adjustCatalog(
    body: CatalogAdjustRequest,
    signal?: AbortSignal,
    idempotencyKey?: string,
  ): Promise<CatalogResult> {
    const result = await client.POST('/tender/catalog/adjust', {
      body,
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
      signal: withTimeout(signal, LONG_TIMEOUT),
    })
    return unwrap<CatalogResult>(result)
  },

  async getCatalog(tenderRequirementId: number, signal?: AbortSignal): Promise<CatalogResult> {
    const result = await client.GET('/tender/catalog', {
      params: { query: { tenderRequirementId } },
      signal: withTimeout(signal, SHORT_TIMEOUT),
    })
    return unwrap<CatalogResult>(result)
  },

  async saveCatalog(body: CatalogSaveRequest, signal?: AbortSignal): Promise<number> {
    const result = await client.POST('/tender/catalog/save', {
      body,
      signal: withTimeout(signal, SHORT_TIMEOUT),
    })
    return unwrap<number>(result)
  },

  async generateChapter(
    body: ChapterRequest,
    signal?: AbortSignal,
    idempotencyKey?: string,
  ): Promise<string> {
    const result = await client.POST('/tender/chapter', {
      body,
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
      signal: withTimeout(signal, LONG_TIMEOUT),
    })
    return unwrap<string>(result)
  },
}

/** 解析任务的请求体，与后端 TenderRequest 一致 */
export interface TenderRequestBody {
  userId?: string
  tenderText: string
}

/** 匹配任务的请求体，与后端 MatchRequest 一致 */
export interface MatchRequestBody {
  userId?: string
  tenderRequirementId: number
}
