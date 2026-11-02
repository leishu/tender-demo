import { client, unwrap } from './client'
import type { KnowledgeSearchHit } from './types'
import { withTimeout } from '@/utils/async'

const SHORT_TIMEOUT = 30_000
const LONG_TIMEOUT = 120_000

export const knowledgeApi = {
  async save(knowledgeBaseName: string, contents: string[], signal?: AbortSignal): Promise<number> {
    const result = await client.POST('/knowledge/{knowledgeBaseName}', {
      params: { path: { knowledgeBaseName } },
      body: contents,
      signal: withTimeout(signal, LONG_TIMEOUT),
    })
    return unwrap<number>(result)
  },

  async search(
    params: { knowledgeBaseName?: string[]; query: string; topK?: number },
    signal?: AbortSignal,
  ): Promise<KnowledgeSearchHit[]> {
    const result = await client.GET('/knowledge/search', {
      params: { query: params },
      signal: withTimeout(signal, SHORT_TIMEOUT),
    })
    return unwrap<KnowledgeSearchHit[]>(result)
  },
}
