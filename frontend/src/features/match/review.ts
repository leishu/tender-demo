import type { PersonnelMatchResult, QualificationMatchResult } from '@/api/types'

export interface ManualReviewItem {
  module: 'personnel' | 'qualification'
  requirementId?: number
  label: string
  unverifiedRequirements: string[]
}

export interface ManualReviewSummary {
  items: ManualReviewItem[]
  total: number
  unverifiedCount: number
  listText: string
}

/**
 * 汇总人员与资质两路结果中的人工复核标记。
 *
 * 人员侧除 `requiresManualReview` 外还携带 `unverifiedRequirements`
 * （工具未返回数据、无法核验的招标强制项），这里统一收集，
 * 供页面用同一种提醒样式展示，避免两类结果各用一套颜色。
 */
export function summarizeManualReview(
  personnel: PersonnelMatchResult[],
  qualifications: QualificationMatchResult[],
): ManualReviewSummary {
  const items: ManualReviewItem[] = []

  for (const item of personnel) {
    if (!item.requiresManualReview) {
      continue
    }
    items.push({
      module: 'personnel',
      requirementId: item.requirementId,
      label: item.role || `人员要求 #${item.requirementId ?? '未知'}`,
      unverifiedRequirements: cleanList(item.unverifiedRequirements),
    })
  }

  for (const item of qualifications) {
    if (!item.requiresManualReview) {
      continue
    }
    items.push({
      module: 'qualification',
      requirementId: item.requirementId,
      label: item.requiredType || `资质要求 #${item.requirementId ?? '未知'}`,
      unverifiedRequirements: [],
    })
  }

  const unverifiedCount = items.reduce((sum, item) => sum + item.unverifiedRequirements.length, 0)

  return {
    items,
    total: items.length,
    unverifiedCount,
    listText: items.map((item) => item.label).join('、'),
  }
}

/** 过滤空串，避免页面渲染出空白的待核验条目。 */
export function cleanList(values?: string[]): string[] {
  return (values ?? []).map((value) => value?.trim() ?? '').filter(Boolean)
}
