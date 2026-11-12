import { computed, onScopeDispose, ref, shallowRef } from 'vue'
import type {
  MatchEvent,
  PersonnelMatchResult,
  QualificationMatchResult,
  TenderTaskView,
} from '@/api/types'
import { tenderApi } from '@/api/tender'
import { newIdempotencyKey } from '@/utils/idempotency'
import { streamSse } from './useSse'
import { isAbortError, toErrorMessage } from '@/utils/async'

export type MatchStreamStatus =
  | 'idle'
  | 'connecting'
  | 'streaming'
  | 'reconnecting'
  | 'done'
  | 'error'
  | 'cancelled'

/** 事件流断开后的重连次数上限；超过后改为查询任务状态，避免无限重试 */
const MAX_RECONNECT = 3

/** 重连退避基数：600ms、1.2s、2.4s */
const RECONNECT_BASE_DELAY = 600

/**
 * 人员/资质并发匹配的前端视图。
 *
 * 与解析同构：匹配也走“建任务 → 订阅事件”，不再是“一次 POST 拉一条流”。
 *
 * 1. `start()` 先 `POST /tender/match` 建任务拿到 taskId（同一幂等键不会重复跑模型）；
 * 2. 再订阅 `GET /tender/tasks/{id}/events`，两路事件交错到达，按 requirementId 归并；
 * 3. 连接断开时带 lastEventId 退避重连，服务端先回放已匹配的条目再接实时流；
 * 4. 页面刷新后用 `resume(taskId)` 从头发起回放，把结果重建出来，不重新匹配。
 *
 * 注意：匹配流里的 `error` 事件表示某一路失败或缺条，**不是终止信号**，另一路仍会继续；
 * 只有 `completed` 才算正常结束。断线重连用尽后查一次任务状态，避免把“任务仍在跑”误报为失败。
 */
export function useTenderMatch() {
  const status = ref<MatchStreamStatus>('idle')
  const errorMessage = ref('')
  const errors = ref<string[]>([])
  const personnelResults = ref<PersonnelMatchResult[]>([])
  const qualificationResults = ref<QualificationMatchResult[]>([])
  const startedAt = ref<number | null>(null)
  const finishedAt = ref<number | null>(null)
  const taskId = ref<number | null>(null)
  const lastEventId = ref(0)
  /**
   * 尚未落成任务号的幂等键：同一次“匹配意图”复用同一个键。
   *
   * 匹配要跑两路模型，是这里最贵的动作。POST 超时但其实服务端已建任务时，用户再点一次
   * 必须带同一个键才能拿回原任务；拿到 taskId 后键即作废（用户的“重新匹配”应是新的一次）。
   * 页面侧会把它和 taskId 一起持久化，刷新后重试同样不会重复跑模型。
   */
  const idempotencyKey = ref<string | null>(null)
  /** 服务端是否已确认任务到达终态（失败/取消/成功）；断线但任务仍在跑时为 false */
  const taskSettled = ref(false)
  const controller = shallowRef<AbortController | null>(null)
  let runId = 0
  // 只有收到 completed 事件才算正常结束；连接关闭本身不代表匹配成功
  let sawCompleted = false
  // 用户主动取消：停止后续重连，也不要把状态改回 error
  let aborted = false

  const isRunning = computed(
    () =>
      status.value === 'connecting' ||
      status.value === 'streaming' ||
      status.value === 'reconnecting',
  )
  const receivedCount = computed(
    () => personnelResults.value.length + qualificationResults.value.length,
  )
  const hasData = computed(
    () => personnelResults.value.length > 0 || qualificationResults.value.length > 0,
  )

  /** 新建匹配任务并订阅事件 */
  async function start(tenderRequirementId: number, userId?: string) {
    if (!tenderRequirementId) {
      errorMessage.value = '缺少招标要求 ID，请先完成解析并确认入库'
      status.value = 'error'
      return
    }

    // 只重置界面状态，幂等键跟着“匹配意图”走，不能在这里丢掉
    reset()
    const currentRun = runId
    status.value = 'connecting'
    startedAt.value = Date.now()
    const currentController = new AbortController()
    controller.value = currentController

    // 上次没拿到 taskId 就复用同一个键：POST 超时但服务端已建任务时，这次拿到的是原任务
    const key = idempotencyKey.value ?? newIdempotencyKey('match')
    idempotencyKey.value = key

    try {
      const task = await tenderApi.createMatchTask(
        { userId, tenderRequirementId },
        currentController.signal,
        key,
      )
      if (currentRun !== runId) {
        return
      }
      if (!task.taskId) {
        throw new Error('服务端未返回任务ID')
      }
      taskId.value = task.taskId
      // 意图已经落成任务号：之后以 taskId 为唯一凭据，再点“重新匹配”就是新的一次
      idempotencyKey.value = null
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
      // 没有 taskId 时保留幂等键：可能是请求超时但服务端已建任务，下次重试还能拿回它
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
    // 已有任务号，不需要再靠幂等键兜底
    idempotencyKey.value = null
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

  /** 订阅事件流，断线自动续传；重连次数用尽后以任务状态兜底 */
  async function subscribe(id: number, from: number, run: number) {
    let cursor = from
    let attempt = 0

    while (true) {
      let closedCleanly = false
      try {
        await streamSse(tenderApi.taskEventsUrl(id, cursor), {
          method: 'GET',
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

      // 流被服务端正常关闭却没收到 completed：终止 error 和“某一路失败”的 error
      // 在事件里分不出来，先查一次任务状态；已到终态就直接收尾，省掉无谓的重连等待
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
      taskSettled.value = true
      finish('done')
      return true
    }
    if (task.status === 'cancelled') {
      taskSettled.value = true
      finish('cancelled')
      return true
    }
    if (task.status === 'failed') {
      taskSettled.value = true
      errorMessage.value = task.errorMessage ?? '匹配失败'
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
      ? '与匹配服务的连接已中断，任务仍在服务端执行；稍后刷新本页可继续查看进度'
      : '匹配连接已中断，且无法确认任务状态，请稍后刷新重试'
    finish('error')
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
        // 非终止：某一路失败/缺条时另一路仍会继续，最终以 completed 或任务状态收尾
        errors.value.push(event.message ?? '匹配失败')
        return
      case 'completed':
        sawCompleted = true
        taskSettled.value = true
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

  /** 取消匹配：断开本地流，同时让服务端停止两路子 Agent，避免继续烧 token */
  async function cancel() {
    aborted = true
    const id = taskId.value
    controller.value?.abort()
    taskSettled.value = true
    finish('cancelled')
    if (id != null) {
      try {
        await tenderApi.cancelTask(id)
      } catch {
        // 任务可能刚好自然结束，取消失败不影响本地状态
      }
    }
  }

  /** 重置界面状态；幂等键与它一起走的是“匹配意图”，由 start/resume 显式维护 */
  function reset() {
    runId += 1
    aborted = false
    controller.value?.abort()
    status.value = 'idle'
    errorMessage.value = ''
    errors.value = []
    personnelResults.value = []
    qualificationResults.value = []
    startedAt.value = null
    finishedAt.value = null
    taskId.value = null
    lastEventId.value = 0
    sawCompleted = false
    taskSettled.value = false
  }

  onScopeDispose(() => {
    aborted = true
    controller.value?.abort()
  })

  return {
    status,
    errorMessage,
    errors,
    personnelResults,
    qualificationResults,
    startedAt,
    finishedAt,
    taskId,
    lastEventId,
    idempotencyKey,
    taskSettled,
    isRunning,
    receivedCount,
    hasData,
    start,
    resume,
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

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
