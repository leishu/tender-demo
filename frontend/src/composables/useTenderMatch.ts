import { computed, onScopeDispose, ref, shallowRef } from 'vue'
import type { MatchEvent, PersonnelMatchResult, QualificationMatchResult } from '@/api/types'
import { streamSse } from './useSse'
import { isAbortError, toErrorMessage } from '@/utils/async'

export type MatchStreamStatus = 'idle' | 'connecting' | 'streaming' | 'done' | 'error' | 'cancelled'

/**
 * 人员/资质并发匹配的 SSE 状态。
 *
 * 两路事件会交错到达，因此分别按 requirementId 归并；
 * 单个子 Agent 失败只记入 errors，另一路仍会继续，直到收到 completed。
 */
export function useTenderMatch() {
  const status = ref<MatchStreamStatus>('idle')
  const errorMessage = ref('')
  const errors = ref<string[]>([])
  const personnelResults = ref<PersonnelMatchResult[]>([])
  const qualificationResults = ref<QualificationMatchResult[]>([])
  const startedAt = ref<number | null>(null)
  const finishedAt = ref<number | null>(null)
  const controller = shallowRef<AbortController | null>(null)
  let runId = 0
  // 只有收到 completed 事件才算正常结束；连接关闭本身不代表匹配成功
  let sawCompleted = false

  const isRunning = computed(() => status.value === 'connecting' || status.value === 'streaming')
  const receivedCount = computed(
    () => personnelResults.value.length + qualificationResults.value.length,
  )
  const hasData = computed(
    () => personnelResults.value.length > 0 || qualificationResults.value.length > 0,
  )

  async function start(tenderRequirementId: number, userId?: string) {
    if (!tenderRequirementId) {
      errorMessage.value = '缺少招标要求 ID，请先完成解析并确认入库'
      status.value = 'error'
      return
    }

    reset()
    const currentRun = runId
    status.value = 'connecting'
    startedAt.value = Date.now()
    const currentController = new AbortController()
    controller.value = currentController

    try {
      await streamSse('/tender/match', {
        body: { userId, tenderRequirementId },
        signal: currentController.signal,
        onMessage: (data) => {
          if (currentRun === runId) {
            handleMessage(data)
          }
        },
        onClose: () => {
          if (currentRun !== runId) {
            return
          }
          if (status.value === 'done' || status.value === 'error' || status.value === 'cancelled') {
            return
          }
          // 连接被服务端/网关中途断开时，结果可能不完整；不能当成成功
          if (!sawCompleted) {
            errorMessage.value =
              hasData.value || errors.value.length
                ? '匹配流在收到完成信号前中断，已返回的结果可能不完整，请重试'
                : '匹配流已结束，但未返回任何结果'
            finish('error')
            return
          }
          finish('done')
        },
      })
    } catch (cause) {
      if (currentRun !== runId) {
        return
      }
      if (isAbortError(cause)) {
        finish('cancelled')
      } else {
        errorMessage.value = toErrorMessage(cause)
        finish('error')
      }
    } finally {
      if (controller.value === currentController) {
        controller.value = null
      }
    }
  }

  function handleMessage(raw: string) {
    let event: MatchEvent
    try {
      event = JSON.parse(raw) as MatchEvent
    } catch {
      return
    }

    if (status.value !== 'error' && status.value !== 'done' && status.value !== 'cancelled') {
      status.value = 'streaming'
    }

    switch (event.module) {
      case 'personnel':
        upsert(personnelResults.value, event.data)
        return
      case 'qualification':
        upsert(qualificationResults.value, event.data)
        return
      case 'error':
        errors.value.push(event.message ?? '匹配失败')
        return
      case 'completed':
        sawCompleted = true
        finish('done')
        return
    }
  }

  function finish(next: MatchStreamStatus) {
    if (status.value === 'done' || status.value === 'error' || status.value === 'cancelled') {
      return
    }
    status.value = next
    finishedAt.value = Date.now()
  }

  function cancel() {
    controller.value?.abort()
  }

  function reset() {
    runId += 1
    controller.value?.abort()
    status.value = 'idle'
    errorMessage.value = ''
    errors.value = []
    personnelResults.value = []
    qualificationResults.value = []
    startedAt.value = null
    finishedAt.value = null
    sawCompleted = false
  }

  onScopeDispose(() => controller.value?.abort())

  return {
    status,
    errorMessage,
    errors,
    personnelResults,
    qualificationResults,
    startedAt,
    finishedAt,
    isRunning,
    receivedCount,
    hasData,
    start,
    cancel,
    reset,
  }
}

function upsert<T extends { requirementId?: number }>(list: T[], next: T) {
  const id = next.requirementId
  if (id == null) {
    list.push(next)
    return
  }
  const index = list.findIndex((item) => item.requirementId === id)
  if (index === -1) {
    list.push(next)
  } else {
    list.splice(index, 1, next)
  }
}
