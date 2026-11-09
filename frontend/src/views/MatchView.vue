<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Close, Loading, VideoPlay } from '@element-plus/icons-vue'
import { useTenderMatch } from '@/composables/useTenderMatch'
import { useWorkflowStore } from '@/stores/workflow'
import type { MatchStatus } from '@/api/types'

const router = useRouter()
const workflow = useWorkflowStore()

const {
  status,
  errorMessage,
  errors,
  personnelResults,
  qualificationResults,
  isRunning,
  receivedCount,
  hasData,
  start,
  cancel,
} = useTenderMatch()

const activeTab = ref<'personnel' | 'qualification'>('personnel')
const onlyUnmet = ref(false)

const statusLabel = computed(() => {
  const labels = {
    idle: '待开始',
    connecting: '连接中',
    streaming: '匹配中',
    done: '已完成',
    error: '失败',
    cancelled: '已取消',
  }
  return labels[status.value]
})

const statusType = computed<'success' | 'warning' | 'info' | 'danger'>(() => {
  if (status.value === 'done') {
    return 'success'
  }
  if (status.value === 'error') {
    return 'danger'
  }
  if (status.value === 'streaming') {
    return 'warning'
  }
  return 'info'
})

const progressText = computed(() => {
  switch (status.value) {
    case 'connecting':
      return '正在连接匹配服务…'
    case 'streaming':
      return `已返回 ${receivedCount.value} 条结果：人员 ${personnelResults.value.length} 条、资质 ${qualificationResults.value.length} 条`
    case 'done':
      return `匹配结束，共返回 ${receivedCount.value} 条结果`
    case 'error':
      return errorMessage.value || '匹配失败，请重试'
    case 'cancelled':
      return `已取消，已返回 ${receivedCount.value} 条结果`
    default:
      return '点击开始匹配，结果会随每条要求匹配完成实时出现'
  }
})

const personnelSummary = computed(() => summarize(personnelResults.value))
const qualificationSummary = computed(() => summarize(qualificationResults.value))

const manualReviewItems = computed(() =>
  qualificationResults.value.filter((item) => item.requiresManualReview),
)

const manualReviewListText = computed(() =>
  manualReviewItems.value
    .map((item) => item.requiredType || `要求 #${item.requirementId ?? '未知'}`)
    .join('、'),
)

const personnelFiltered = computed(() =>
  onlyUnmet.value
    ? personnelResults.value.filter((item) => item.status !== 'MATCHED')
    : personnelResults.value,
)

const qualificationFiltered = computed(() =>
  onlyUnmet.value
    ? qualificationResults.value.filter((item) => item.status !== 'MATCHED')
    : qualificationResults.value,
)

watch(
  () => [personnelResults.value.length, qualificationResults.value.length] as const,
  ([personnel, qualification]) => {
    if (activeTab.value === 'personnel' && personnel === 0 && qualification > 0) {
      activeTab.value = 'qualification'
    } else if (activeTab.value === 'qualification' && qualification === 0 && personnel > 0) {
      activeTab.value = 'personnel'
    }
  },
)

function summarize(list: Array<{ status?: MatchStatus }>) {
  return {
    total: list.length,
    matched: list.filter((item) => item.status === 'MATCHED').length,
    partial: list.filter((item) => item.status === 'PARTIAL').length,
    unmatched: list.filter((item) => item.status === 'UNMATCHED').length,
  }
}

function matchStatusMeta(value?: MatchStatus): {
  label: string
  type: 'success' | 'warning' | 'danger' | 'info'
} {
  switch (value) {
    case 'MATCHED':
      return { label: '全部满足', type: 'success' }
    case 'PARTIAL':
      return { label: '部分满足', type: 'warning' }
    case 'UNMATCHED':
      return { label: '未匹配', type: 'danger' }
    default:
      return { label: '未知', type: 'info' }
  }
}

function eligibleMeta(value?: boolean): {
  label: string
  type: 'success' | 'danger' | 'info'
} {
  if (value === true) {
    return { label: '满足', type: 'success' }
  }
  if (value === false) {
    return { label: '不满足', type: 'danger' }
  }
  return { label: '待确认', type: 'info' }
}

function goParse() {
  router.push('/parse')
}

function goTenders() {
  router.push('/tenders')
}

async function runMatch() {
  const requirementId = workflow.tenderRequirementId
  if (!requirementId) {
    ElMessage.warning('请先完成招标解析并确认入库')
    return
  }
  await start(requirementId, workflow.userId)
  if (status.value === 'done') {
    if (errors.value.length) {
      ElMessage.warning(`匹配完成，但有 ${errors.value.length} 条错误，请查看详情`)
    } else {
      ElMessage.success(`匹配完成，共返回 ${receivedCount.value} 条结果`)
    }
  }
}
</script>

<template>
  <div class="page">
    <div class="page-intro">
      <div>
        <h1>人员与资质匹配</h1>
        <p class="muted">
          基于已确认入库的招标要求，并发匹配企业人员与企业资质；每条要求匹配完成即实时返回，两路结果可能交错到达。
        </p>
      </div>
      <el-tag v-if="workflow.tenderRequirementId" type="success" effect="plain">
        招标要求 #{{ workflow.tenderRequirementId }}
      </el-tag>
    </div>

    <el-card v-if="!workflow.tenderRequirementId" shadow="never" class="panel">
      <el-empty description="请先在「招标文件」中解析并确认入库，或在列表中选择一份已有文件">
        <el-button type="primary" @click="goTenders">从列表选择</el-button>
        <el-button @click="goParse">去解析招标文件</el-button>
      </el-empty>
    </el-card>

    <template v-else>
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="card-header">
            <span>匹配任务 · 招标要求 #{{ workflow.tenderRequirementId }}</span>
            <div class="header-actions">
              <el-tag :type="statusType" effect="plain">{{ statusLabel }}</el-tag>
              <el-button v-if="isRunning" :icon="Close" @click="cancel">取消匹配</el-button>
              <el-button
                type="primary"
                :icon="VideoPlay"
                :loading="status === 'connecting'"
                :disabled="isRunning"
                @click="runMatch"
              >
                {{ hasData ? '重新匹配' : '开始匹配' }}
              </el-button>
            </div>
          </div>
        </template>

        <div class="progress-row">
          <el-icon v-if="isRunning" class="is-loading"><Loading /></el-icon>
          <span class="muted">{{ progressText }}</span>
        </div>

        <el-alert
          v-if="errorMessage"
          :title="errorMessage"
          type="error"
          show-icon
          :closable="false"
          class="mb"
        />
        <el-alert
          v-for="(message, index) in errors"
          :key="index"
          :title="message"
          type="error"
          show-icon
          :closable="false"
          class="mb"
        />
      </el-card>

      <div v-if="hasData" class="summary-grid">
        <div class="summary-card">
          <div class="summary-title">
            <span>人员要求</span>
            <span class="muted">共 {{ personnelSummary.total }} 条</span>
          </div>
          <div class="summary-values">
            <span
              ><strong>{{ personnelSummary.matched }}</strong
              ><span class="muted">满足</span></span
            >
            <span
              ><strong>{{ personnelSummary.partial }}</strong
              ><span class="muted">部分满足</span></span
            >
            <span
              ><strong>{{ personnelSummary.unmatched }}</strong
              ><span class="muted">未匹配</span></span
            >
          </div>
        </div>
        <div class="summary-card">
          <div class="summary-title">
            <span>企业资质</span>
            <span class="muted">共 {{ qualificationSummary.total }} 条</span>
          </div>
          <div class="summary-values">
            <span
              ><strong>{{ qualificationSummary.matched }}</strong
              ><span class="muted">满足</span></span
            >
            <span
              ><strong>{{ qualificationSummary.partial }}</strong
              ><span class="muted">部分满足</span></span
            >
            <span
              ><strong>{{ qualificationSummary.unmatched }}</strong
              ><span class="muted">未匹配</span></span
            >
          </div>
        </div>
      </div>

      <el-card v-if="!hasData && !isRunning" shadow="never" class="panel">
        <el-empty :description="status === 'error' ? '匹配失败，请重试' : '还没有匹配结果'">
          <el-button type="primary" :icon="VideoPlay" @click="runMatch">开始匹配</el-button>
        </el-empty>
      </el-card>

      <el-card v-if="hasData" shadow="never" class="panel">
        <template #header>
          <div class="card-header">
            <span>匹配结果</span>
            <el-switch v-model="onlyUnmet" active-text="只看未满足" />
          </div>
        </template>

        <el-alert
          v-if="manualReviewItems.length"
          type="error"
          show-icon
          :closable="false"
          class="mb"
          :title="`有 ${manualReviewItems.length} 项资质要求需人工复核，可能影响投标资格`"
        >
          <template #default>
            <span>请优先核对：{{ manualReviewListText }}</span>
          </template>
        </el-alert>

        <el-tabs v-model="activeTab">
          <el-tab-pane :label="`人员匹配 (${personnelResults.length})`" name="personnel">
            <el-empty
              v-if="!personnelFiltered.length"
              :description="onlyUnmet ? '没有未满足的人员要求' : '暂无人员匹配结果'"
            />
            <div v-else class="match-list">
              <article
                v-for="(item, index) in personnelFiltered"
                :key="item.requirementId ?? index"
                class="match-item"
              >
                <header class="match-item-head">
                  <div class="match-title">
                    <el-tag :type="matchStatusMeta(item.status).type" size="small" effect="dark">
                      {{ matchStatusMeta(item.status).label }}
                    </el-tag>
                    <strong>{{ item.role || '未命名岗位' }}</strong>
                    <span v-if="item.requiredQuantity != null" class="muted">
                      需 {{ item.requiredQuantity }} 人
                    </span>
                    <span class="muted">命中 {{ item.matchedCount ?? 0 }} 人</span>
                  </div>
                  <span v-if="item.source" class="source mono" :title="item.source">
                    {{ item.source }}
                  </span>
                </header>

                <div class="match-meta">
                  <span>证书/职称：{{ item.certificate || '无特别要求' }}</span>
                </div>

                <el-alert
                  v-if="item.reason"
                  :title="item.reason"
                  type="info"
                  show-icon
                  :closable="false"
                  class="reason"
                />

                <div v-if="item.candidates?.length" class="candidate-list">
                  <div
                    v-for="(candidate, candidateIndex) in item.candidates"
                    :key="candidate.personnelId ?? candidateIndex"
                    class="candidate-row"
                  >
                    <div class="candidate-main">
                      <strong>{{ candidate.name || '未知人员' }}</strong>
                      <el-tag
                        :type="eligibleMeta(candidate.eligible).type"
                        size="small"
                        effect="plain"
                      >
                        {{ eligibleMeta(candidate.eligible).label }}
                      </el-tag>
                      <span v-if="candidate.gender" class="muted">{{ candidate.gender }}</span>
                      <span v-if="candidate.yearsOfExperience != null" class="muted">
                        {{ candidate.yearsOfExperience }} 年经验
                      </span>
                    </div>

                    <div class="candidate-tags">
                      <el-tag
                        v-for="role in candidate.matchedRoles ?? []"
                        :key="`role-${role}`"
                        size="small"
                        type="success"
                        effect="plain"
                      >
                        岗位：{{ role }}
                      </el-tag>
                      <el-tag
                        v-for="certificate in candidate.matchedCertificates ?? []"
                        :key="`cert-${certificate}`"
                        size="small"
                        type="warning"
                        effect="plain"
                      >
                        证书：{{ certificate }}
                      </el-tag>
                      <span v-if="candidate.certificateValidUntil" class="muted">
                        有效期至 {{ candidate.certificateValidUntil }}
                      </span>
                    </div>

                    <div v-if="candidate.reason" class="candidate-reason muted">
                      {{ candidate.reason }}
                    </div>
                  </div>
                </div>
                <p v-else class="muted no-candidate">暂无候选人</p>
              </article>
            </div>
          </el-tab-pane>

          <el-tab-pane :label="`资质匹配 (${qualificationResults.length})`" name="qualification">
            <el-empty
              v-if="!qualificationFiltered.length"
              :description="onlyUnmet ? '没有未满足的资质要求' : '暂无资质匹配结果'"
            />
            <div v-else class="match-list">
              <article
                v-for="(item, index) in qualificationFiltered"
                :key="item.requirementId ?? index"
                class="match-item"
              >
                <header class="match-item-head">
                  <div class="match-title">
                    <el-tag :type="matchStatusMeta(item.status).type" size="small" effect="dark">
                      {{ matchStatusMeta(item.status).label }}
                    </el-tag>
                    <el-tag
                      v-if="item.requiresManualReview"
                      type="danger"
                      size="small"
                      effect="dark"
                    >
                      需人工复核
                    </el-tag>
                    <strong>{{ item.requiredType || '未命名资质' }}</strong>
                    <span class="muted">等级：{{ item.requiredLevel || '不限' }}</span>
                    <span class="muted">候选 {{ item.matchedCount ?? 0 }} 项</span>
                  </div>
                  <span v-if="item.source" class="source mono" :title="item.source">
                    {{ item.source }}
                  </span>
                </header>

                <div class="match-meta">
                  <span>有效期要求：{{ item.validRequirement || '无特别要求' }}</span>
                  <span v-if="item.risk" class="muted">风险标识：{{ item.risk }}</span>
                </div>

                <el-alert
                  v-if="item.reason"
                  :title="item.reason"
                  type="info"
                  show-icon
                  :closable="false"
                  class="reason"
                />

                <div v-if="item.candidates?.length" class="candidate-list">
                  <div
                    v-for="(candidate, candidateIndex) in item.candidates"
                    :key="candidate.qualificationId ?? candidateIndex"
                    class="candidate-row"
                  >
                    <div class="candidate-main">
                      <strong>{{ candidate.name || candidate.type || '未知资质' }}</strong>
                      <el-tag
                        :type="eligibleMeta(candidate.eligible).type"
                        size="small"
                        effect="plain"
                      >
                        {{ eligibleMeta(candidate.eligible).label }}
                      </el-tag>
                      <el-tag v-if="candidate.level" size="small" effect="plain">
                        {{ candidate.level }}
                      </el-tag>
                      <el-tag v-if="candidate.expired" type="danger" size="small" effect="plain">
                        已过期
                      </el-tag>
                    </div>

                    <div class="candidate-tags">
                      <span v-if="candidate.certNo" class="muted mono">
                        证书编号：{{ candidate.certNo }}
                      </span>
                      <span v-if="candidate.validUntil" class="muted">
                        有效期至 {{ candidate.validUntil }}
                      </span>
                    </div>

                    <div v-if="candidate.reason" class="candidate-reason muted">
                      {{ candidate.reason }}
                    </div>
                  </div>
                </div>
                <p v-else class="muted no-candidate">暂无候选资质</p>
              </article>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.progress-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  line-height: 1.6;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.summary-card {
  padding: 14px 16px;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  background: #fff;
}

.summary-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-weight: 600;
}

.summary-values {
  display: flex;
  align-items: baseline;
  gap: 22px;
}

.summary-values > span {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.summary-values strong {
  font-size: 22px;
  line-height: 1.2;
}

.match-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.match-item {
  padding: 14px 16px;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: #fff;
}

.match-item-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.match-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  line-height: 1.6;
}

.source {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--app-muted);
  font-size: 12px;
}

.match-meta {
  margin-top: 6px;
  color: var(--app-muted);
  font-size: 13px;
  line-height: 1.6;
}

.reason {
  margin-top: 10px;
}

.candidate-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
}

.candidate-row {
  padding: 10px 12px;
  border: 1px dashed #e5e7eb;
  border-radius: 8px;
  background: #fafafa;
}

.candidate-main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.candidate-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 12px;
}

.candidate-reason {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
}

.no-candidate {
  margin: 10px 0 0;
  font-size: 13px;
}

@media (max-width: 768px) {
  .summary-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .match-item-head {
    flex-direction: column;
  }

  .source {
    max-width: 100%;
  }
}
</style>
