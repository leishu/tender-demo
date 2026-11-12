import { computed, onScopeDispose, ref, shallowRef } from 'vue'
import type {
  ParseLogEntry,
  ParseModule,
  TenderParseEvent,
  TenderRequirements,
  TenderTaskView,
} from '@/api/types'
import { tenderApi } from '@/api/tender'
import { identityHeaders } from '@/api/identity'
import { streamSse } from './useSse'
import { applyParseEvent, createEmptyRequirements } from '@/features/parse/parseReducer'
import { isAbortError, toErrorMessage } from '@/utils/async'

export type ParseStatus =
  | 'idle'
  | 'connecting'
  | 'streaming'
  | 'reconnecting'
  | 'done'
  | 'error'
  | 'cancelled'

const MODULE_LABELS: Record<ParseModule, string> = {
  project: '项目概况',
  qualification: '资质要求',
  performance: '业绩要求',
  personnel: '人员要求',
  scoringItem: '评分点',
  error: '错误',
  completed: '完成',
}

/** 事件流断开后的重连次数上限；超过后改为查询任务状态，避免无限重试 */
const MAX_RECONNECT = 3

/** 重连退避基数：600ms、1.2s、2.4s */
const RECONNECT_BASE_DELAY = 600

/**
 * 解析任务的前端视图。
 *
 * 解析不再是“一次 POST 拉一条流”，而是“建任务 → 订阅事件”：
 *
 * 1. `start()` 先 POST 建任务拿到 taskId（同一个幂等键重复提交只会得到一个任务）；
 * 2. 再订阅 `GET /tender/tasks/{id}/events`，事件带递增 id，前端记住最后一条 id；
 * 3. 连接断开时带 lastEventId 退避重连，服务端会先回放缺失的事件再续上实时流，
 *    因此网络抖动、网关超时不会让已经解析出来的内容白费；
 * 4. 页面刷新后用 `resume(taskId)` 从头发起回放，把结果重建出来。
 */
export function useTenderParse() {
  const status = ref<ParseStatus>('idle')
  const errorMessage = ref('')
  const requirements = ref<TenderRequirements>(createEmptyRequirements())
  const logs = ref<ParseLogEntry[]>([])
  const startedAt = ref<number | null>(null)
  const finishedAt = ref<number | null>(null)
  const taskId = ref<number | null>(null)
  const lastEventId = ref(0)
  const controller = shallowRef<AbortController | null>(null)
  let logId = 0
  let runId = 0
  // 只有收到 completed 事件才算正常结束；连接关闭本身不代表解析成功
  let sawCompleted = false
  // 用户主动取消：停止后续重连，也不要把状态改回 error
  let aborted = false

  const isRunning = computed(
    () =>
      status.value === 'connecting' ||
      status.value === 'streaming' ||
      status.value === 'reconnecting',
  )

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

  /** 新建解析任务并订阅事件 */
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
      // 每次主动解析都用新的幂等键：失败后重新解析才算“新的一次”
      const task = await tenderApi.createParseTask(
        { userId, tenderText },
        currentController.signal,
        newIdempotencyKey(),
      )
      if (currentRun !== runId) {
        return
      }
      if (!task.taskId) {
        throw new Error('服务端未返回任务ID')
      }
      taskId.value = task.taskId
      await subscribe(task.taskId, 0, currentRun)
    } catch (cause) {
      if (currentRun !== runId) {
        return
      }
      if (isAbortError(cause) || aborted) {
        finish('cancelled')
        return
      }
      errorMessage.value = toErrorMessage(cause)
      finish('error')
    } finally {
      if (controller.value === currentController) {
        controller.value = null
      }
    }
  }

  /**
   * 续看已有任务：从 0 开始回放全部事件，重建界面状态。
   * 页面刷新、切走再回来都走这里，不产生新的模型调用。
   */
  async function resume(id: number) {
    reset()
    const currentRun = runId
    taskId.value = id
    status.value = 'connecting'
    startedAt.value = Date.now()
    const currentController = new AbortController()
    controller.value = currentController

    try {
      await subscribe(id, 0, currentRun)
    } catch (cause) {
      if (currentRun !== runId) {
        return
      }
      errorMessage.value = toErrorMessage(cause)
      finish('error')
    } finally {
      if (controller.value === currentController) {
        controller.value = null
      }
    }
  }

  /**
   * 订阅事件流，断线自动续传。
   *
   * 每次重连都带上本地已收到的最大 seq，服务端只补发缺的那一段；
   * 重连次数用尽后查询一次任务状态，避免把“任务仍在服务端执行”误报成解析失败。
   */
  async function subscribe(id: number, from: number, run: number) {
    let cursor = from
    let attempt = 0

    while (true) {
      let closedCleanly = false
      try {
        await streamSse(tenderApi.taskEventsUrl(id, cursor), {
          method: 'GET',
          // 事件订阅没有请求体，身份只能靠请求头；漏带会被服务端当成别人的任务而回放不到事件
          headers: identityHeaders(),
          signal: controller.value?.signal,
          onMessage: (data, message) => {
            const seq = Number(message.id)
            if (Number.isFinite(seq) && seq > cursor) {
              cursor = seq
              lastEventId.value = seq
            }
            if (run === runId) {
              handleMessage(data)
            }
          },
        })
        // 服务端在任务收尾后主动关闭流，属于正常结束，不是网络故障
        closedCleanly = true
      } catch (cause) {
        if (run !== runId || aborted) {
          return
        }
        if (isAbortError(cause)) {
          return
        }
        // 其它错误（网关 502、连接被重置等）交给下面的重连逻辑处理
      }

      if (run !== runId || aborted) {
        return
      }
      if (sawCompleted || status.value === 'error' || status.value === 'cancelled') {
        return
      }

      // 流被服务端正常关闭却没收到 completed：说明任务已经收尾（失败/取消），
      // 先查一次任务状态直接收尾，省掉 3 次无谓的重连等待
      if (closedCleanly) {
        const task = await fetchTask(id)
        if (run !== runId) {
          return
        }
        if (task && finishFromTask(task)) {
          return
        }
      }

      if (attempt >= MAX_RECONNECT) {
        await settleAfterDisconnect(id, run)
        return
      }

      attempt += 1
      status.value = 'reconnecting'
      await delay(RECONNECT_BASE_DELAY * 2 ** (attempt - 1))
      if (run !== runId || aborted) {
        return
      }
    }
  }

  /** 查询任务状态；失败返回 null，表示“无法判断”，由调用方按断线处理 */
  async function fetchTask(id: number): Promise<TenderTaskView | null> {
    try {
      return await tenderApi.getTask(id)
    } catch {
      return null
    }
  }

  /** 任务已到终态就按终态收尾，返回 true 表示不需要再等事件 */
  function finishFromTask(task: TenderTaskView): boolean {
    if (task.status === 'succeeded') {
      sawCompleted = true
      finish('done')
      return true
    }
    if (task.status === 'cancelled') {
      finish('cancelled')
      return true
    }
    if (task.status === 'failed') {
      errorMessage.value = task.errorMessage ?? '解析失败'
      finish('error')
      return true
    }
    return false
  }

  /**
   * 事件流彻底断开后的兜底：用任务状态代替“连接关闭”做判断。
   * 任务还在跑不算失败，只是本次没能续上，提示用户稍后刷新继续看。
   */
  async function settleAfterDisconnect(id: number, run: number) {
    const task = await fetchTask(id)
    if (run !== runId) {
      return
    }
    if (task && finishFromTask(task)) {
      return
    }
    errorMessage.value = task
      ? '与解析服务的连接已中断，任务仍在服务端执行；稍后刷新本页可继续查看进度'
      : '解析连接已中断，且无法确认任务状态，请稍后刷新重试'
    finish('error')
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
      sawCompleted = true
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

  /**
   * 取消解析：断开本地流，同时让服务端停止上游模型调用，避免“点了取消还在烧 token”。
   */
  async function cancel() {
    aborted = true
    const id = taskId.value
    controller.value?.abort()
    finish('cancelled')
    if (id != null) {
      try {
        await tenderApi.cancelTask(id)
      } catch {
        // 任务可能刚好自然结束，取消失败不影响本地状态
      }
    }
  }

  function reset() {
    runId += 1
    aborted = false
    controller.value?.abort()
    status.value = 'idle'
    errorMessage.value = ''
    requirements.value = createEmptyRequirements()
    logs.value = []
    startedAt.value = null
    finishedAt.value = null
    taskId.value = null
    lastEventId.value = 0
    logId = 0
    sawCompleted = false
  }

  /**
   * 编辑器直接改 requirements，提交前做一次深拷贝，避免把 Vue 响应式代理传给 fetch。
   */
  function toRequirements(): TenderRequirements {
    return JSON.parse(JSON.stringify(requirements.value)) as TenderRequirements
  }

  onScopeDispose(() => {
    aborted = true
    controller.value?.abort()
  })

  return {
    status,
    errorMessage,
    requirements,
    logs,
    startedAt,
    finishedAt,
    taskId,
    lastEventId,
    isRunning,
    hasData,
    counts,
    start,
    resume,
    cancel,
    reset,
    toRequirements,
  }
}

export function moduleLabel(module: ParseModule): string {
  return MODULE_LABELS[module]
}

function newIdempotencyKey(): string {
  const uuid = globalThis.crypto?.randomUUID?.()
  return uuid ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
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
