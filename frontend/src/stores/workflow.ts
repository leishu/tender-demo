import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import type { CatalogResult, TenderRequirements } from '@/api/types'

const STORAGE_KEY = 'tender-demo:workflow'
const USER_KEY = 'tender-demo:user-id'

interface PersistedState {
  tenderRequirementId: number | null
  requirements: TenderRequirements | null
  catalogResult: CatalogResult | null
  catalogRunId: number | null
  /** 最近一次解析任务ID：刷新页面后据此回到事件流续看，不必重新解析 */
  parseTaskId: number | null
}

function emptyState(): PersistedState {
  return {
    tenderRequirementId: null,
    requirements: null,
    catalogResult: null,
    catalogRunId: null,
    parseTaskId: null,
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

function loadUserId(): string {
  try {
    const existing = localStorage.getItem(USER_KEY)
    if (existing) {
      return existing
    }
    const created = `web-${Math.random().toString(36).slice(2, 10)}`
    localStorage.setItem(USER_KEY, created)
    return created
  } catch {
    return `web-${Math.random().toString(36).slice(2, 10)}`
  }
}

/**
 * 工作流状态：只放跨页面共享的 ID 与结构化结果。
 * 表单草稿、弹窗开关等留在组件内部。
 */
export const useWorkflowStore = defineStore('workflow', () => {
  const initial = loadState()

  const userId = ref(loadUserId())
  const tenderRequirementId = ref<number | null>(initial.tenderRequirementId)
  const requirements = ref<TenderRequirements | null>(initial.requirements)
  const catalogResult = ref<CatalogResult | null>(initial.catalogResult)
  const catalogRunId = ref<number | null>(initial.catalogRunId)
  const parseTaskId = ref<number | null>(initial.parseTaskId)
  const sessionKey = ref(0)

  const hasRequirement = computed(() => tenderRequirementId.value !== null)
  const hasCatalog = computed(() => catalogResult.value !== null)

  function setTenderRequirementId(id: number | null) {
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

  function clearParsed() {
    tenderRequirementId.value = null
    requirements.value = null
    catalogResult.value = null
    catalogRunId.value = null
    parseTaskId.value = null
  }

  function reset() {
    clearParsed()
    sessionKey.value += 1
    try {
      sessionStorage.removeItem(STORAGE_KEY)
    } catch {
      // 忽略隐私模式下的存储异常
    }
  }

  watch(
    [tenderRequirementId, requirements, catalogResult, catalogRunId, parseTaskId],
    () => {
      const state: PersistedState = {
        tenderRequirementId: tenderRequirementId.value,
        requirements: requirements.value,
        catalogResult: catalogResult.value,
        catalogRunId: catalogRunId.value,
        parseTaskId: parseTaskId.value,
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
    sessionKey,
    hasRequirement,
    hasCatalog,
    setTenderRequirementId,
    setRequirements,
    setCatalogResult,
    setCatalogRunId,
    setParseTaskId,
    clearParsed,
    reset,
  }
})
