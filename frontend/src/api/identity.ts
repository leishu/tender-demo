/**
 * 调用者身份：前后端约定的唯一身份来源。
 *
 * 后端只认请求头 `X-User-Id`（缺失时才回退到请求体 userId），而任务查询、事件订阅、
 * 取消这几个接口没有请求体——身份必须由前端主动带上，否则会被当成匿名用户而查不到自己的任务。
 *
 * 当前是演示环境：ID 由浏览器生成后存在 localStorage。接入真实登录后，
 * 只需把 {@link getUserId} 换成“登录态里的用户 ID”，其余调用点不用改。
 */
export const USER_ID_HEADER = 'X-User-Id'

/** 与 workflow store 共用的存储键；改这里就够，不要在别处硬编码 */
const USER_KEY = 'tender-demo:user-id'

function createUserId(): string {
  return `web-${Math.random().toString(36).slice(2, 10)}`
}

/** 读取（必要时生成）当前用户 ID；隐私模式下写入失败也不影响本次会话使用 */
export function getUserId(): string {
  try {
    const existing = localStorage.getItem(USER_KEY)
    if (existing) {
      return existing
    }
    const created = createUserId()
    localStorage.setItem(USER_KEY, created)
    return created
  } catch {
    return createUserId()
  }
}

/**
 * 身份请求头。
 *
 * `fetch-event-source` 走的是 fetch 而不是原生 EventSource，可以自定义请求头，
 * 因此 SSE 订阅同样能带身份。
 */
export function identityHeaders(): Record<string, string> {
  return { [USER_ID_HEADER]: getUserId() }
}
