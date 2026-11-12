import { beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { useTenderMatch } from './useTenderMatch'

/**
 * 幂等键的生命周期：同一次“匹配意图”复用同一个键，拿到 taskId 后作废。
 *
 * 匹配要跑两路模型，是整条链路里最贵的动作：POST 超时（服务端其实已建任务）后
 * 用户再点一次，必须带同一个键才能拿回原任务，而不是把两路匹配重跑一遍。
 */
const mocks = vi.hoisted(() => ({
  createMatchTask: vi.fn(),
  getTask: vi.fn(),
  cancelTask: vi.fn(),
  streamSse: vi.fn(),
}))

vi.mock('@/api/tender', () => ({
  tenderApi: {
    createMatchTask: mocks.createMatchTask,
    getTask: mocks.getTask,
    cancelTask: mocks.cancelTask,
    taskEventsUrl: (id: number, lastEventId: number) =>
      `/tender/tasks/${id}/events?lastEventId=${lastEventId}`,
  },
}))

vi.mock('@/composables/useSse', () => ({ streamSse: mocks.streamSse }))

/** 在独立 scope 里使用 composable，让 onScopeDispose 有归属 */
function newMatch() {
  return effectScope(true).run(() => useTenderMatch())!
}

/** 让事件流立刻推一条 completed 就结束，模拟任务正常收尾 */
function completeImmediately() {
  mocks.streamSse.mockImplementation(
    async (_url: string, options: { onMessage: (data: string, message: unknown) => void }) => {
      options.onMessage(JSON.stringify({ module: 'completed' }), { id: '1' })
    },
  )
}

describe('useTenderMatch 幂等键', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.getTask.mockRejectedValue(new Error('not found'))
    completeImmediately()
  })

  it('拿到任务号后作废幂等键，重新匹配用新键', async () => {
    mocks.createMatchTask.mockResolvedValue({ taskId: 1 })

    const match = newMatch()
    await match.start(8, 'u1')

    expect(match.status.value).toBe('done')
    expect(match.idempotencyKey.value).toBeNull()

    await match.start(8, 'u1')

    const keys = mocks.createMatchTask.mock.calls.map((call) => call[2])
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBeTruthy()
    expect(keys[0]).not.toBe(keys[1])
  })

  it('建任务失败（可能是超时）时保留幂等键，重试复用同一个键', async () => {
    mocks.createMatchTask.mockRejectedValueOnce(new Error('请求超时')).mockResolvedValue({
      taskId: 7,
    })

    const match = newMatch()
    await match.start(8, 'u1')

    expect(match.status.value).toBe('error')
    const pendingKey = match.idempotencyKey.value
    expect(pendingKey).toBeTruthy()

    await match.start(8, 'u1')

    const keys = mocks.createMatchTask.mock.calls.map((call) => call[2])
    expect(keys[1]).toBe(pendingKey)
    expect(match.status.value).toBe('done')
    expect(match.idempotencyKey.value).toBeNull()
  })

  it('续看已有任务时不需要幂等键', async () => {
    const match = newMatch()
    match.idempotencyKey.value = 'stale-key'

    await match.resume(11)

    expect(match.idempotencyKey.value).toBeNull()
    expect(mocks.createMatchTask).not.toHaveBeenCalled()
    expect(match.status.value).toBe('done')
  })
})
