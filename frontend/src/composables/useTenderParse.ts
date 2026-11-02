import { computed, onScopeDispose, ref, shallowRef } from 'vue'
import type { ParseLogEntry, ParseModule, TenderParseEvent, TenderRequirements } from '@/api/types'
import { streamSse } from './useSse'
import { applyParseEvent, createEmptyRequirements } from '@/features/parse/parseReducer'
import { isAbortError, toErrorMessage } from '@/utils/async'

export type ParseStatus = 'idle' | 'connecting' | 'streaming' | 'done' | 'error' | 'cancelled'

const MODULE_LABELS: Record<ParseModule, string> = {
  project: '项目概况',
  qualification: '资质要求',
  performance: '业绩要求',
  personnel: '人员要求',
  scoringItem: '评分点',
  error: '错误',
  completed: '完成',
}

export function useTenderParse() {
  const status = ref<ParseStatus>('idle')
  const errorMessage = ref('')
  const requirements = ref<TenderRequirements>(createEmptyRequirements())
  const logs = ref<ParseLogEntry[]>([])
  const startedAt = ref<number | null>(null)
  const finishedAt = ref<number | null>(null)
  const controller = shallowRef<AbortController | null>(null)
  let logId = 0
  let runId = 0

  const isRunning = computed(() => status.value === 'connecting' || status.value === 'streaming')

  const hasData = computed(() => {
    const value = requirements.value
    return Boolean(
      value.projectName ||
      value.location ||
      value.duration ||
      value.budget ||
      value.qualifications?.length ||
      value.performances?.length ||
      value.personnel?.length ||
      value.scoringItems?.length,
    )
  })

  const counts = computed(() => ({
    qualification: requirements.value.qualifications?.length ?? 0,
    performance: requirements.value.performances?.length ?? 0,
    personnel: requirements.value.personnel?.length ?? 0,
    scoringItem: requirements.value.scoringItems?.length ?? 0,
  }))

  async function start(tenderText: string, userId?: string) {
    if (!tenderText.trim()) {
      errorMessage.value = '请先输入招标文件文本'
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
      await streamSse('/tender/parse', {
        body: { userId, tenderText },
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
          if (!hasData.value) {
            errorMessage.value = '解析流已结束，但未返回结构化数据'
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
    let event: TenderParseEvent
    try {
      event = JSON.parse(raw) as TenderParseEvent
    } catch {
      return
    }

    if (status.value !== 'error' && status.value !== 'done') {
      status.value = 'streaming'
    }

    logs.value.unshift({
      id: ++logId,
      module: event.module,
      at: Date.now(),
      summary: summarize(event),
    })
    if (logs.value.length > 80) {
      logs.value.length = 80
    }

    if (event.module === 'error') {
      errorMessage.value = event.message ?? '解析失败'
      finish('error')
      return
    }
    if (event.module === 'completed') {
      finish('done')
      return
    }

    requirements.value = applyParseEvent(requirements.value, event)
  }

  function finish(next: ParseStatus) {
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
    requirements.value = createEmptyRequirements()
    logs.value = []
    startedAt.value = null
    finishedAt.value = null
    logId = 0
  }

  /**
   * 编辑器直接改 requirements，提交前做一次深拷贝，避免把 Vue 响应式代理传给 fetch。
   */
  function toRequirements(): TenderRequirements {
    return JSON.parse(JSON.stringify(requirements.value)) as TenderRequirements
  }

  onScopeDispose(() => controller.value?.abort())

  return {
    status,
    errorMessage,
    requirements,
    logs,
    startedAt,
    finishedAt,
    isRunning,
    hasData,
    counts,
    start,
    cancel,
    reset,
    toRequirements,
  }
}

export function moduleLabel(module: ParseModule): string {
  return MODULE_LABELS[module]
}

function summarize(event: TenderParseEvent): string {
  switch (event.module) {
    case 'project':
      return event.data.projectName || '招标总体要求'
    case 'qualification':
      return [event.data.type, event.data.level].filter(Boolean).join(' / ') || '资质要求'
    case 'performance':
      return event.data.projectType || '业绩要求'
    case 'personnel':
      return [event.data.role, event.data.certificate].filter(Boolean).join(' / ') || '人员要求'
    case 'scoringItem':
      return [event.data.itemId, event.data.name].filter(Boolean).join(' · ') || '评分点'
    case 'error':
      return event.message ?? '解析失败'
    case 'completed':
      return '解析完成'
    default:
      return '未知事件'
  }
}
