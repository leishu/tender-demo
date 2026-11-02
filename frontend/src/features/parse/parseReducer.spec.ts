import { describe, expect, it } from 'vitest'
import { applyParseEvent, createEmptyRequirements } from './parseReducer'

describe('applyParseEvent', () => {
  it('merges project fields into the requirement object', () => {
    const state = applyParseEvent(createEmptyRequirements(), {
      module: 'project',
      data: { projectName: '某市公共文化服务中心', duration: '540 日历天' },
    })

    expect(state.projectName).toBe('某市公共文化服务中心')
    expect(state.duration).toBe('540 日历天')
  })

  it('appends repeated qualification events instead of overwriting', () => {
    let state = createEmptyRequirements()
    state = applyParseEvent(state, {
      module: 'qualification',
      data: { type: '建筑工程施工总承包', level: '壹级' },
    })
    state = applyParseEvent(state, {
      module: 'qualification',
      data: { type: 'ISO9001', level: '认证' },
    })

    expect(state.qualifications).toHaveLength(2)
    expect(state.qualifications?.map((item) => item.type)).toEqual([
      '建筑工程施工总承包',
      'ISO9001',
    ])
  })

  it('keeps existing fields when a later project event omits them', () => {
    const initial = {
      ...createEmptyRequirements(),
      projectName: '已有项目',
      location: '已有地点',
    }
    const state = applyParseEvent(initial, {
      module: 'project',
      data: { budget: '28600 万元' },
    })

    expect(state.projectName).toBe('已有项目')
    expect(state.location).toBe('已有地点')
    expect(state.budget).toBe('28600 万元')
  })

  it('appends scoring items and personnel', () => {
    let state = createEmptyRequirements()
    state = applyParseEvent(state, {
      module: 'scoringItem',
      data: { itemId: 'SC-01', name: '施工组织设计', score: 20 },
    })
    state = applyParseEvent(state, {
      module: 'personnel',
      data: { role: '项目经理', quantity: 1 },
    })

    expect(state.scoringItems).toHaveLength(1)
    expect(state.personnel).toHaveLength(1)
  })
})
