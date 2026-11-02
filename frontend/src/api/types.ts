import type { components } from './schema'

type Schemas = components['schemas']

export type TenderRequirements = Schemas['TenderRequirements']
export type QualificationRequirement = Schemas['QualificationRequirement']
export type PerformanceRequirement = Schemas['PerformanceRequirement']
export type PersonnelRequirement = Schemas['PersonnelRequirement']
export type ScoringItem = Schemas['ScoringItem']
export type CatalogNode = Schemas['CatalogNode']
export type CatalogGap = Schemas['CatalogGap']
export type CatalogResult = Schemas['CatalogResult']
export type KnowledgeSearchHit = Schemas['KnowledgeSearchHit']

export type TenderRequest = Schemas['TenderRequest']
export type CatalogRequest = Schemas['CatalogRequest']
export type CatalogAdjustRequest = Schemas['CatalogAdjustRequest']
export type CatalogSaveRequest = Schemas['CatalogSaveRequest']
export type ChapterRequest = Schemas['ChapterRequest']

export type ParseModule =
  'project' | 'qualification' | 'performance' | 'personnel' | 'scoringItem' | 'error' | 'completed'

/**
 * SSE 事件的判别联合。后端每条 data 都是
 * `{"module":"...","data":{...}}`，error/completed 不带 data。
 */
export type TenderParseEvent =
  | { module: 'project'; data: TenderRequirements; message?: undefined }
  | { module: 'qualification'; data: QualificationRequirement; message?: undefined }
  | { module: 'performance'; data: PerformanceRequirement; message?: undefined }
  | { module: 'personnel'; data: PersonnelRequirement; message?: undefined }
  | { module: 'scoringItem'; data: ScoringItem; message?: undefined }
  | { module: 'error'; data?: undefined; message?: string }
  | { module: 'completed'; data?: undefined; message?: undefined }

export interface ParseLogEntry {
  id: number
  module: ParseModule
  at: number
  summary: string
}
