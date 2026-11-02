import { client, unwrap } from './client'
import type {
  CatalogAdjustRequest,
  CatalogRequest,
  CatalogResult,
  CatalogSaveRequest,
  ChapterRequest,
  TenderRequirements,
} from './types'
import { withTimeout } from '@/utils/async'

const SHORT_TIMEOUT = 30_000
const LONG_TIMEOUT = 180_000

export const tenderApi = {
  async confirm(body: TenderRequirements, signal?: AbortSignal): Promise<number> {
    const result = await client.POST('/tender/parse/confirm', {
      body,
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
