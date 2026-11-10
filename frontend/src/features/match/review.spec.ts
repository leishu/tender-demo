import { describe, expect, it } from 'vitest'
import { cleanList, summarizeManualReview } from './review'

describe('summarizeManualReview', () => {
  it('collects flagged results from both modules into one list', () => {
    const summary = summarizeManualReview(
      [
        {
          requirementId: 1,
          role: '项目经理',
          requiresManualReview: true,
          unverifiedRequirements: ['社保记录满 6 个月', '未担任其他在建工程项目经理'],
        },
        { requirementId: 2, role: '技术负责人', requiresManualReview: false },
      ],
      [
        {
          requirementId: 8,
          requiredType: '建筑工程施工总承包',
          requiresManualReview: true,
        },
        { requirementId: 9, requiredType: 'ISO9001', requiresManualReview: false },
      ],
    )

    expect(summary.total).toBe(2)
    expect(summary.unverifiedCount).toBe(2)
    expect(summary.listText).toBe('项目经理、建筑工程施工总承包')
    expect(summary.items.map((item) => item.module)).toEqual(['personnel', 'qualification'])
  })

  it('returns an empty summary when nothing needs manual review', () => {
    const summary = summarizeManualReview(
      [{ requirementId: 1, role: '项目经理', requiresManualReview: false }],
      [{ requirementId: 8, requiredType: 'ISO9001' }],
    )

    expect(summary.total).toBe(0)
    expect(summary.unverifiedCount).toBe(0)
    expect(summary.listText).toBe('')
  })

  it('falls back to requirement id when the label is missing', () => {
    const summary = summarizeManualReview(
      [{ requirementId: 3, requiresManualReview: true }],
      [{ requiresManualReview: true }],
    )

    expect(summary.items.map((item) => item.label)).toEqual(['人员要求 #3', '资质要求 #未知'])
  })
})

describe('cleanList', () => {
  it('drops blank entries so the page does not render empty bullets', () => {
    expect(cleanList([' 社保记录 ', '', '   ', '在建项目'])).toEqual(['社保记录', '在建项目'])
    expect(cleanList(undefined)).toEqual([])
  })
})
