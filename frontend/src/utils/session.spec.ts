import { beforeEach, describe, expect, it } from 'vitest'
import {
  clearSessions,
  forgetSessionId,
  getSessionId,
  isServerSessionId,
  rememberSessionId,
} from './session'

/**
 * 会话记忆：服务端签发、客户端记住并原样回传。
 *
 * 客户端不得自造 ID，所以这里重点验证两件事：没记过的时候返回 null（调用方据此“不传”），
 * 记住过的服务端 ID 能原样取回。
 */
describe('会话记忆', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('没有记录时返回 null，让调用方不传 sessionId', () => {
    expect(getSessionId(8, 'catalog')).toBeNull()
  })

  it('记住服务端下发的 ID 后原样取回', () => {
    rememberSessionId(8, 'catalog', '2026-11-13-09-3')
    expect(getSessionId(8, 'catalog')).toBe('2026-11-13-09-3')
  })

  it('不同任务类型、不同招标互不干扰', () => {
    rememberSessionId(8, 'match', '2026-11-13-09-1')
    rememberSessionId(8, 'catalog', '2026-11-13-09-2')
    rememberSessionId(9, 'catalog', '2026-11-13-09-3')

    expect(getSessionId(8, 'match')).toBe('2026-11-13-09-1')
    expect(getSessionId(8, 'catalog')).toBe('2026-11-13-09-2')
    expect(getSessionId(9, 'catalog')).toBe('2026-11-13-09-3')
  })

  it('没有招标要求时不记也不取（章节可以脱离招标单独试写）', () => {
    rememberSessionId(null, 'chapter', '2026-11-13-09-3')
    expect(getSessionId(null, 'chapter')).toBeNull()
  })

  it('只认服务端形态的 ID，客户端自造值一律忽略', () => {
    expect(isServerSessionId('2026-11-13-09-3')).toBe(true)
    expect(isServerSessionId('2026-11-13-09-123456789')).toBe(true)
    expect(isServerSessionId('req-8-catalog')).toBe(false)
    expect(isServerSessionId('2026-11-13-3')).toBe(false)
    expect(isServerSessionId('../etc')).toBe(false)
    expect(isServerSessionId('')).toBe(false)
    expect(isServerSessionId(null)).toBe(false)
    expect(isServerSessionId(undefined)).toBe(false)

    rememberSessionId(8, 'catalog', 'req-8-catalog')
    expect(getSessionId(8, 'catalog')).toBeNull()
    rememberSessionId(8, 'catalog', null)
    expect(getSessionId(8, 'catalog')).toBeNull()
  })

  it('忘掉会话后回到“不传”，由服务端重新签发', () => {
    rememberSessionId(8, 'catalog', '2026-11-13-09-3')
    forgetSessionId(8, 'catalog')
    expect(getSessionId(8, 'catalog')).toBeNull()
    // 其它任务类型的会话不受影响
    rememberSessionId(8, 'match', '2026-11-13-09-1')
    forgetSessionId(8, 'catalog')
    expect(getSessionId(8, 'match')).toBe('2026-11-13-09-1')
  })

  it('清空工作流会一并清掉所有会话', () => {
    rememberSessionId(8, 'match', '2026-11-13-09-1')
    rememberSessionId(9, 'chapter', '2026-11-13-09-2')
    clearSessions()
    expect(getSessionId(8, 'match')).toBeNull()
    expect(getSessionId(9, 'chapter')).toBeNull()
  })
})
