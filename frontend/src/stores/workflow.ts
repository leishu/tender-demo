import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import type { CatalogResult, TenderRequirements } from '@/api/types'
import { getUserId } from '@/api/identity'
import { clearSessions } from '@/utils/session'

const STORAGE_KEY = 'tender-demo:workflow'

interface PersistedState {
  tenderRequirementId: number | null
  requirements: TenderRequirements | null
  catalogResult: CatalogResult | null
  catalogRunId: number | null
  /** 最近一次解析任务ID：刷新页面后据此回到事件流续看，不必重新解析 */
  parseTaskId: number | null
  /** 最近一次匹配任务ID：刷新页面后据此回到事件流续看，不必重新匹配 */
  matchTaskId: number | null
  /**
   * 尚未落成任务号的匹配幂等键。
   *
   * 匹配一次要跑两路模型，比解析贵：POST 超时（服务端其实已建任务）时再点一次，
   * 必须复用同一个键才能拿回原任务，而不是让两路匹配再跑一遍。
   * 一旦拿到 taskId，意图已经落地，这个键就作废（重新匹配要用新键）。
   */
  matchIdempotencyKey: string | null
}

function emptyState(): PersistedState {
  return {
    tenderRequirementId: null,
    requirements: null,
    catalogResult: null,
    catalogRunId: null,
    parseTaskId: null,
    matchTaskId: null,
    matchIdempotencyKey: null,
  }
}

function loadState(): PersistedState {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return emptyState()
    }
    return { ...emptyState(), ...(JSON.parse(raw) as Partial<PersistedState>) }
  } catch {
    return emptyState()
  }
}

/**
 * 工作流状态：只放跨页面共享的 ID 与结构化结果。
 * 表单草稿、弹窗开关等留在组件内部。
 */
export const useWorkflowStore = defineStore('workflow', () => {
  const initial = loadState()

  // 身份由 api/identity 统一管理：它同时负责给每个请求（含 SSE）注入 X-User-Id
  const userId = ref(getUserId())
  const tenderRequirementId = ref<number | null>(initial.tenderRequirementId)
  const requirements = ref<TenderRequirements | null>(initial.requirements)
  const catalogResult = ref<CatalogResult | null>(initial.catalogResult)
  const catalogRunId = ref<number | null>(initial.catalogRunId)
  const parseTaskId = ref<number | null>(initial.parseTaskId)
  const matchTaskId = ref<number | null>(initial.matchTaskId)
  const matchIdempotencyKey = ref<string | null>(initial.matchIdempotencyKey)
  const sessionKey = ref(0)

  const hasRequirement = computed(() => tenderRequirementId.value !== null)
  const hasCatalog = computed(() => catalogResult.value !== null)

  function setTenderRequirementId(id: number | null) {
    // 匹配结果只对所属招标要求有效，切换文件后不应再回放上一份的匹配任务
    if (tenderRequirementId.value !== id) {
      matchTaskId.value = null
      matchIdempotencyKey.value = null
    }
    tenderRequirementId.value = id
  }

  function setRequirements(value: TenderRequirements | null) {
    requirements.value = value
  }

  function setCatalogResult(value: CatalogResult | null) {
    catalogResult.value = value
  }

  function setCatalogRunId(id: number | null) {
    catalogRunId.value = id
  }

  function setParseTaskId(id: number | null) {
    parseTaskId.value = id
  }

  function setMatchTaskId(id: number | null) {
    matchTaskId.value = id
  }

  function setMatchIdempotencyKey(key: string | null) {
    matchIdempotencyKey.value = key
  }

  function clearParsed() {
    tenderRequirementId.value = null
    requirements.value = null
    catalogResult.value = null
    catalogRunId.value = null
    parseTaskId.value = null
    matchTaskId.value = null
    matchIdempotencyKey.value = null
  }

  function reset() {
    clearParsed()
    sessionKey.value += 1
    // 会话属于“这一轮工作”：工作流清空后不能再让新会话继承旧上下文
    clearSessions()
    try {
      sessionStorage.removeItem(STORAGE_KEY)
    } catch {
      // 忽略隐私模式下的存储异常
    }
  }

  watch(
    [
      tenderRequirementId,
      requirements,
      catalogResult,
      catalogRunId,
      parseTaskId,
      matchTaskId,
      matchIdempotencyKey,
    ],
    () => {
      const state: PersistedState = {
        tenderRequirementId: tenderRequirementId.value,
        requirements: requirements.value,
        catalogResult: catalogResult.value,
        catalogRunId: catalogRunId.value,
        parseTaskId: parseTaskId.value,
        matchTaskId: matchTaskId.value,
        matchIdempotencyKey: matchIdempotencyKey.value,
      }
      try {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(state))
      } catch {
        // 忽略配额不足等异常
      }
    },
    { deep: true },
  )

  return {
    userId,
    tenderRequirementId,
    requirements,
    catalogResult,
    catalogRunId,
    parseTaskId,
    matchTaskId,
    matchIdempotencyKey,
    sessionKey,
    hasRequirement,
    hasCatalog,
    setTenderRequirementId,
    setRequirements,
    setCatalogResult,
    setCatalogRunId,
    setParseTaskId,
    setMatchTaskId,
    setMatchIdempotencyKey,
    clearParsed,
    reset,
  }
})
