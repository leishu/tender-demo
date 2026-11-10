import { client, unwrap } from './client'
import type {
  CatalogAdjustRequest,
  CatalogRequest,
  CatalogResult,
  CatalogSaveRequest,
  ChapterRequest,
  TenderRequirements,
  TenderRequirementSummary,
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

  async generateCatalog(body: CatalogRequest, signal?: AbortSignal): Promise<CatalogResult> {
    const result = await client.POST('/tender/catalog', {
      body,
      signal: withTimeout(signal, LONG_TIMEOUT),
    })
    return unwrap<CatalogResult>(result)
  },

  async adjustCatalog(body: CatalogAdjustRequest, signal?: AbortSignal): Promise<CatalogResult> {
    const result = await client.POST('/tender/catalog/adjust', {
      body,
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

  async generateChapter(body: ChapterRequest, signal?: AbortSignal): Promise<string> {
    const result = await client.POST('/tender/chapter', {
      body,
      signal: withTimeout(signal, LONG_TIMEOUT),
    })
    return unwrap<string>(result)
  },
}
