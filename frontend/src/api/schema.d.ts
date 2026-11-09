/**
 * 手写的 OpenAPI 类型子集，形状与 openapi-typescript 生成结果一致。
 *
 * 后端启动后执行 `pnpm api:generate`，会从
 * http://localhost:8080/v3/api-docs 重新生成并覆盖本文件。
 */
type QualificationRequirement = {
  id?: number
  tenderRequirementId?: number
  type?: string
  level?: string
  validRequirement?: string
  source?: string
  entityType?: string
}

type PerformanceRequirement = {
  id?: number
  tenderRequirementId?: number
  projectType?: string
  quantity?: string
  amount?: string
  timeRange?: string
  source?: string
  entityType?: string
}

type PersonnelRequirement = {
  id?: number
  tenderRequirementId?: number
  role?: string
  quantity?: number
  certificate?: string
  source?: string
  entityType?: string
}

type ScoringItem = {
  id?: number
  tenderRequirementId?: number
  itemId?: string
  name?: string
  score?: number
  criterion?: string
  suggestedChapter?: string
  source?: string
  entityType?: string
}

type TenderRequirements = {
  id?: number
  projectName?: string
  location?: string
  duration?: string
  budget?: string
  qualifications?: QualificationRequirement[]
  performances?: PerformanceRequirement[]
  personnel?: PersonnelRequirement[]
  scoringItems?: ScoringItem[]
  entityType?: string
}

type TenderRequirementSummary = {
  id?: number
  projectName?: string
  location?: string
  duration?: string
  budget?: string
  recordTime?: number
  qualificationCount?: number
  performanceCount?: number
  personnelCount?: number
  scoringItemCount?: number
  hasCatalog?: boolean
}

type CatalogGap = {
  type?: string
  detail?: string
}

type CatalogNode = {
  chapter_no?: string
  title?: string
  scoring_item_ids?: string[]
  content_hint?: string
  children?: CatalogNode[]
}

type CatalogResult = {
  catalog?: CatalogNode[]
  business_catalog?: CatalogNode[]
  technical_catalog?: CatalogNode[]
  structure_decision?: string
  gaps?: CatalogGap[]
}

type KnowledgeSearchHit = {
  id?: string
  content?: string
  createdAt?: number
  score?: number
}

type MatchStatus = 'MATCHED' | 'PARTIAL' | 'UNMATCHED'

type PersonnelMatchCandidate = {
  personnelId?: number
  name?: string
  gender?: string
  yearsOfExperience?: number
  roles?: string[]
  matchedRoles?: string[]
  matchedCertificates?: string[]
  certificateValidUntil?: string
  eligible?: boolean
  reason?: string
}

type PersonnelMatchResult = {
  entityType?: string
  requirementId?: number
  role?: string
  requiredQuantity?: number
  certificate?: string
  source?: string
  status?: MatchStatus
  matchedCount?: number
  candidates?: PersonnelMatchCandidate[]
  reason?: string
}

type QualificationMatchCandidate = {
  qualificationId?: number
  type?: string
  name?: string
  level?: string
  certNo?: string
  validUntil?: string
  expired?: boolean
  eligible?: boolean
  reason?: string
}

type QualificationMatchResult = {
  entityType?: string
  requirementId?: number
  requiredType?: string
  requiredLevel?: string
  validRequirement?: string
  source?: string
  status?: MatchStatus
  matchedCount?: number
  requiresManualReview?: boolean
  risk?: string
  candidates?: QualificationMatchCandidate[]
  reason?: string
}

type MatchEvent = {
  module?: 'personnel' | 'qualification' | 'error' | 'completed'
  data?: PersonnelMatchResult | QualificationMatchResult
  message?: string
}

export interface paths {
  '/tender/parse': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['TenderRequest']
        }
      }
      responses: {
        200: {
          content: {
            'text/event-stream': components['schemas']['TenderParseEvent']
          }
        }
      }
    }
  }
  '/tender/parse/confirm': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['TenderRequirements']
        }
      }
      responses: {
        200: {
          content: {
            'application/json': number
          }
        }
      }
    }
  }
  '/tender/requirements': {
    get: {
      parameters: {
        query?: {
          keyword?: string
          limit?: number
        }
      }
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['TenderRequirementSummary'][]
          }
        }
      }
    }
  }
  '/tender/catalog': {
    get: {
      parameters: {
        query: {
          tenderRequirementId: number
        }
      }
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['CatalogResult']
          }
        }
      }
    }
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['CatalogRequest']
        }
      }
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['CatalogResult']
          }
        }
      }
    }
  }
  '/tender/catalog/adjust': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['CatalogAdjustRequest']
        }
      }
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['CatalogResult']
          }
        }
      }
    }
  }
  '/tender/catalog/save': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['CatalogSaveRequest']
        }
      }
      responses: {
        200: {
          content: {
            'application/json': number
          }
        }
      }
    }
  }
  '/tender/match': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['MatchRequest']
        }
      }
      responses: {
        200: {
          content: {
            'text/event-stream': components['schemas']['MatchEvent']
          }
        }
      }
    }
  }
  '/tender/chapter': {
    post: {
      requestBody: {
        content: {
          'application/json': components['schemas']['ChapterRequest']
        }
      }
      responses: {
        200: {
          content: {
            'text/plain': string
          }
        }
      }
    }
  }
  '/knowledge/{knowledgeBaseName}': {
    post: {
      parameters: {
        path: {
          knowledgeBaseName: string
        }
      }
      requestBody: {
        content: {
          'application/json': string[]
        }
      }
      responses: {
        200: {
          content: {
            'application/json': number
          }
        }
      }
    }
  }
  '/knowledge/search': {
    get: {
      parameters: {
        query: {
          knowledgeBaseName?: string[]
          query: string
          topK?: number
        }
      }
      responses: {
        200: {
          content: {
            'application/json': components['schemas']['KnowledgeSearchHit'][]
          }
        }
      }
    }
  }
}

export interface components {
  schemas: {
    TenderRequest: {
      userId?: string
      tenderText?: string
    }
    TenderParseEvent: {
      module?: string
      data?: unknown
      message?: string
    }
    TenderRequirements: TenderRequirements
    TenderRequirementSummary: TenderRequirementSummary
    QualificationRequirement: QualificationRequirement
    PerformanceRequirement: PerformanceRequirement
    PersonnelRequirement: PersonnelRequirement
    ScoringItem: ScoringItem
    CatalogRequest: {
      userId?: string
      tenderRequirementId?: number
    }
    CatalogAdjustRequest: {
      userId?: string
      tenderRequirementId?: number
      previousCatalog?: CatalogResult
      customPrompt?: string
    }
    CatalogSaveRequest: {
      userId?: string
      tenderRequirementId?: number
      catalogResult?: CatalogResult
    }
    CatalogResult: CatalogResult
    CatalogNode: CatalogNode
    CatalogGap: CatalogGap
    ChapterRequest: {
      userId?: string
      sessionId?: string
      chapterTitle?: string
      scoringPointIds?: string
    }
    KnowledgeSearchHit: KnowledgeSearchHit
    MatchRequest: {
      userId?: string
      tenderRequirementId?: number
    }
    MatchStatus: MatchStatus
    PersonnelMatchCandidate: PersonnelMatchCandidate
    PersonnelMatchResult: PersonnelMatchResult
    QualificationMatchCandidate: QualificationMatchCandidate
    QualificationMatchResult: QualificationMatchResult
    MatchEvent: MatchEvent
  }
}

export type webhooks = Record<string, never>
export type operations = Record<string, never>
