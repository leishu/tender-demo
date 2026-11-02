import type { TenderParseEvent, TenderRequirements } from '@/api/types'

type DataEvent = Exclude<TenderParseEvent, { module: 'error' | 'completed' }>

export function createEmptyRequirements(): TenderRequirements {
  return {
    projectName: '',
    location: '',
    duration: '',
    budget: '',
    qualifications: [],
    performances: [],
    personnel: [],
    scoringItems: [],
  }
}

/**
 * 纯函数 reducer，便于单测；SSE 每到达一条事件就合并一次。
 */
export function applyParseEvent(current: TenderRequirements, event: DataEvent): TenderRequirements {
  switch (event.module) {
    case 'project':
      return { ...current, ...event.data }
    case 'qualification':
      return {
        ...current,
        qualifications: [...(current.qualifications ?? []), event.data],
      }
    case 'performance':
      return {
        ...current,
        performances: [...(current.performances ?? []), event.data],
      }
    case 'personnel':
      return {
        ...current,
        personnel: [...(current.personnel ?? []), event.data],
      }
    case 'scoringItem':
      return {
        ...current,
        scoringItems: [...(current.scoringItems ?? []), event.data],
      }
    default:
      return current
  }
}
