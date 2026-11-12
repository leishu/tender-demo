/**
 * 幂等键：同一“用户意图”内复用同一个键。
 *
 * 用法是“操作开始前取键、成功后清空、失败后保留”：
 * - 失败重试复用原键 → 服务端复用同一个任务（已完成则直接返回缓存结果），不会重复调用模型；
 * - 成功后再操作换新键 → 视为新的一次请求，能得到新结果。
 *
 * 前缀用于区分任务类型，避免不同类型的任务撞到同一个键（服务端会做类型校验）。
 */
export function newIdempotencyKey(scope = 'ui'): string {
  const uuid = globalThis.crypto?.randomUUID?.()
  const value = uuid ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `${scope}-${value}`
}
