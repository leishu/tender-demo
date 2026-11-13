/**
 * 会话 ID 的本地记录。
 *
 * 会话 ID 只由服务端签发，形如 `2026-11-13-09-3`（年-月-日-时-流水号）。客户端不自己造 ID，
 * 只有两种动作：
 *
 * - **开始新会话**：请求里不传 `sessionId`，由服务端生成；
 * - **续写旧会话**：把上一次响应里的 ID 原样回传（服务端会读回 `t_agent_state` 里的历史消息）。
 *
 * 因此客户端只做一件事：把服务端下发过的 ID 记下来。自造 ID 有两个坑——可能与服务端的
 * 按小时计数器撞号，以及命名规则一变就让历史会话失联；服务端也只接受这种形态，其它一律 400。
 *
 * 按「招标要求 + 任务类型」分桶（而不是整条流程共用一个），原因有两条：
 * - 匹配/目录/章节的提示词差异大，混在一段会话里容易互相干扰；
 * - 这几类任务可能在多个标签页里同时跑，而同一个 `(userId, sessionId)` 在服务端是
 *   “后写覆盖”，共用一个 ID 会互相覆盖、丢消息。
 *
 * 存在 `sessionStorage`（按标签页隔离）而不是 `localStorage`：会话是「当前这段工作」的上下文，
 * 换标签页/重开浏览器就该从新会话开始，而不是继承一段来历不明的历史。
 */

/** 招标流程里的任务类型：每一类各用一段独立会话，互不串味。 */
export type SessionTask = 'match' | 'catalog' | 'chapter'

/** 会话 ID 的响应头名，与后端 `RuntimeContextBuilder.SESSION_HEADER` 保持一致 */
export const SESSION_HEADER = 'X-Session-Id'

/**
 * 服务端签发的会话 ID 形态：年-月-日-时-流水号。
 *
 * 这里只做“像不像服务端发的”判断，避免把脏值写进存储后再原样回传（那会被 400 拒掉）；
 * 权威校验在服务端，前端这份只是防御性过滤。
 */
const SERVER_SESSION_ID = /^\d{4}-\d{2}-\d{2}-\d{2}-\d{1,10}$/

const STORAGE_PREFIX = 'tender-demo:session'

export function isServerSessionId(value: string | null | undefined): value is string {
  return typeof value === 'string' && SERVER_SESSION_ID.test(value)
}

function storageKey(tenderRequirementId: number, task: SessionTask): string {
  return `${STORAGE_PREFIX}:${tenderRequirementId}:${task}`
}

/**
 * 取回本段会话：没有记录（或记录已失效）时返回 `null`，
 * 调用方应当“不传 sessionId”，让服务端开一段新的。
 */
export function getSessionId(
  tenderRequirementId: number | null | undefined,
  task: SessionTask,
): string | null {
  if (tenderRequirementId == null) {
    return null
  }
  try {
    const raw = sessionStorage.getItem(storageKey(tenderRequirementId, task))
    return isServerSessionId(raw) ? raw : null
  } catch {
    // 隐私模式下 sessionStorage 可能不可用：读不到就当没有历史会话
    return null
  }
}

/**
 * 记住服务端这次用的会话。
 *
 * 非服务端形态（响应头缺失、空串）直接忽略：那说明本次没有可用会话，
 * 下次仍旧“不传”，由服务端再发一个。
 */
export function rememberSessionId(
  tenderRequirementId: number | null | undefined,
  task: SessionTask,
  sessionId: string | null | undefined,
): void {
  if (tenderRequirementId == null || !isServerSessionId(sessionId)) {
    return
  }
  try {
    sessionStorage.setItem(storageKey(tenderRequirementId, task), sessionId)
  } catch {
    // 配额/隐私模式异常都不该影响主流程：最坏结果是下次重新开一段会话
  }
}

/** 忘掉某段会话：下一次调用会由服务端开新会话 */
export function forgetSessionId(tenderRequirementId: number, task: SessionTask): void {
  try {
    sessionStorage.removeItem(storageKey(tenderRequirementId, task))
  } catch {
    // 同上
  }
}

/**
 * 清空本应用记录的全部会话（切换/清空工作流时用）。
 *
 * 会话属于“这一轮工作”，工作流被重置后留着它们只会让下一次生成莫名继承旧上下文。
 */
export function clearSessions(): void {
  try {
    const stale: string[] = []
    for (let i = 0; i < sessionStorage.length; i += 1) {
      const key = sessionStorage.key(i)
      if (key?.startsWith(`${STORAGE_PREFIX}:`)) {
        stale.push(key)
      }
    }
    for (const key of stale) {
      sessionStorage.removeItem(key)
    }
  } catch {
    // 同上
  }
}
