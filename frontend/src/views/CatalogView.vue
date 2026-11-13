<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Check, FolderOpened, MagicStick, View } from '@element-plus/icons-vue'
import CatalogTree from '@/features/catalog/CatalogTree.vue'
import { useAsyncTask } from '@/composables/useAsyncTask'
import { tenderApi } from '@/api/tender'
import { newIdempotencyKey } from '@/utils/idempotency'
import { forgetSessionId, getSessionId, rememberSessionId } from '@/utils/session'
import { useWorkflowStore } from '@/stores/workflow'
import type { CatalogNode } from '@/api/types'

interface CatalogTab {
  name: string
  label: string
  nodes: CatalogNode[]
}

const router = useRouter()
const route = useRoute()
const workflow = useWorkflowStore()

const { loading: generateLoading, error: generateError, run: runGenerate } = useAsyncTask()
const { loading: loadLoading, error: loadError, run: runLoad } = useAsyncTask()
const { loading: adjustLoading, error: adjustError, run: runAdjust } = useAsyncTask()
const { loading: saveLoading, error: saveError, run: runSave } = useAsyncTask()

const adjustPrompt = ref('')
const activeTab = ref('merged')
const jsonVisible = ref(false)

/**
 * 幂等键：一次生成/调整意图内保持不变。
 * 请求失败时保留，重试会命中同一个任务（服务端已完成则直接返回缓存）而不重复调用模型；
 * 成功后清空，下一次点击视为新的意图。
 */
let generateKey: string | null = null
let adjustKey: string | null = null

watch(adjustPrompt, () => {
  adjustKey = null
})

watch(
  () => workflow.tenderRequirementId,
  () => {
    generateKey = null
    adjustKey = null
  },
)

const catalogTabs = computed<CatalogTab[]>(() => {
  const result = workflow.catalogResult
  if (!result) {
    return []
  }
  if (result.catalog?.length) {
    return [{ name: 'merged', label: '合并目录', nodes: result.catalog }]
  }
  const tabs: CatalogTab[] = []
  if (result.business_catalog?.length) {
    tabs.push({ name: 'business', label: '商务标', nodes: result.business_catalog })
  }
  if (result.technical_catalog?.length) {
    tabs.push({ name: 'technical', label: '技术标', nodes: result.technical_catalog })
  }
  return tabs
})

watch(
  catalogTabs,
  (tabs) => {
    if (!tabs.some((tab) => tab.name === activeTab.value)) {
      activeTab.value = tabs[0]?.name ?? 'merged'
    }
  },
  { immediate: true },
)

const structureDecision = computed(() => workflow.catalogResult?.structure_decision ?? '')
const gaps = computed(() => workflow.catalogResult?.gaps ?? [])
const json = computed(() => JSON.stringify(workflow.catalogResult, null, 2))
const taskError = computed(
  () => generateError.value || loadError.value || adjustError.value || saveError.value,
)

function resetErrors() {
  generateError.value = ''
  loadError.value = ''
  adjustError.value = ''
  saveError.value = ''
}

/**
 * 会话的用法：**生成目录开新会话，调整续写同一会话。**
 *
 * 生成是一次新的推理（哪怕沿用旧会话也只是让模型多背一段无关历史），因此不传 `sessionId`，
 * 由服务端签发；响应里的 ID 记下来，供随后的「调整」原样回传——调整轮才能看到上一轮目录，
 * 不必把整份目录重述一遍。
 */
async function generate() {
  const requirementId = workflow.tenderRequirementId
  if (!requirementId) {
    return
  }
  resetErrors()
  const key = generateKey ?? newIdempotencyKey('catalog')
  generateKey = key
  // 重新生成视为新会话：丢掉上一段的记忆，避免新目录被旧上下文带偏
  forgetSessionId(requirementId, 'catalog')
  const result = await runGenerate((signal) =>
    tenderApi.generateCatalog(
      {
        userId: workflow.userId,
        tenderRequirementId: requirementId,
      },
      signal,
      key,
    ),
  )
  if (!result) {
    // 失败保留幂等键：下次点击是重试，命中同一任务
    return
  }
  generateKey = null
  rememberSessionId(requirementId, 'catalog', result.sessionId)
  workflow.setCatalogResult(result.data)
  workflow.setCatalogRunId(null)
  ElMessage.success('目录生成完成')
}

async function loadSaved() {
  const requirementId = workflow.tenderRequirementId
  if (!requirementId) {
    return
  }
  resetErrors()
  const result = await runLoad((signal) => tenderApi.getCatalog(requirementId, signal))
  if (!result) {
    return
  }
  workflow.setCatalogResult(result)
  workflow.setCatalogRunId(null)
  ElMessage.success('已读取保存的目录')
}

async function adjust() {
  const requirementId = workflow.tenderRequirementId
  const previous = workflow.catalogResult
  const prompt = adjustPrompt.value.trim()
  if (!requirementId || !previous || !prompt) {
    return
  }
  resetErrors()
  const key = adjustKey ?? newIdempotencyKey('catalog-adjust')
  adjustKey = key
  const result = await runAdjust((signal) =>
    tenderApi.adjustCatalog(
      {
        userId: workflow.userId,
        // 续写生成那一段会话：模型能看到上一轮目录与当时的生成规则
        sessionId: getSessionId(requirementId, 'catalog') ?? undefined,
        tenderRequirementId: requirementId,
        previousCatalog: previous,
        customPrompt: prompt,
      },
      signal,
      key,
    ),
  )
  if (!result) {
    // 失败保留幂等键：修改要求（watch adjustPrompt）或重试时才会换键
    return
  }
  adjustKey = null
  rememberSessionId(requirementId, 'catalog', result.sessionId)
  workflow.setCatalogResult(result.data)
  workflow.setCatalogRunId(null)
  adjustPrompt.value = ''
  ElMessage.success('目录已调整，请核对后保存')
}

async function save() {
  const requirementId = workflow.tenderRequirementId
  const catalogResult = workflow.catalogResult
  if (!requirementId || !catalogResult) {
    return
  }
  resetErrors()
  const runId = await runSave((signal) =>
    tenderApi.saveCatalog(
      { userId: workflow.userId, tenderRequirementId: requirementId, catalogResult },
      signal,
    ),
  )
  if (runId === undefined) {
    return
  }
  workflow.setCatalogRunId(runId)
  ElMessage.success(`目录已保存，批次 ID：${runId}`)
}

function goParse() {
  router.push('/parse')
}

function goTenders() {
  router.push('/tenders')
}

// 从「招标文件列表」点击“查看目录”进入时自动读取已保存的目录
onMounted(() => {
  if (route.query.load === 'saved' && workflow.tenderRequirementId) {
    void loadSaved()
  }
})
</script>

<template>
  <div class="page">
    <div class="page-intro">
      <div>
        <h1>投标目录</h1>
        <p class="muted">
          根据已入库的招标要求生成最多三级目录，支持自然语言调整；保存会覆盖同一招标要求的旧目录。
        </p>
      </div>
      <el-tag v-if="workflow.catalogRunId" type="success" effect="plain">
        已保存批次 #{{ workflow.catalogRunId }}
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
            <span>目录操作 · 招标要求 #{{ workflow.tenderRequirementId }}</span>
            <div class="header-actions">
              <el-button :icon="FolderOpened" :loading="loadLoading" @click="loadSaved">
                读取已保存
              </el-button>
              <el-button
                type="primary"
                :icon="MagicStick"
                :loading="generateLoading"
                @click="generate"
              >
                生成目录
              </el-button>
            </div>
          </div>
        </template>

        <p class="muted operation-hint">
          生成/调整由模型完成，通常需要几十秒；失败时优先看下方错误信息，目录内容可展开 JSON 核对。
        </p>

        <el-alert
          v-if="taskError"
          :title="taskError"
          type="error"
          show-icon
          :closable="false"
          class="mb"
        />
        <el-alert
          v-if="structureDecision"
          :title="structureDecision"
          type="info"
          show-icon
          :closable="false"
        />
      </el-card>

      <el-card v-if="!workflow.catalogResult" shadow="never" class="panel">
        <el-empty description="还没有目录结果">
          <el-button type="primary" :icon="MagicStick" :loading="generateLoading" @click="generate">
            生成目录
          </el-button>
        </el-empty>
      </el-card>

      <template v-else>
        <el-card shadow="never" class="panel">
          <template #header>
            <div class="card-header">
              <span>目录结构</span>
              <div class="header-actions">
                <el-button text :icon="View" @click="jsonVisible = true">查看 JSON</el-button>
                <el-button type="success" :icon="Check" :loading="saveLoading" @click="save">
                  保存目录
                </el-button>
              </div>
            </div>
          </template>

          <el-tabs v-if="catalogTabs.length" v-model="activeTab">
            <el-tab-pane
              v-for="tab in catalogTabs"
              :key="tab.name"
              :label="`${tab.label} (${tab.nodes.length})`"
              :name="tab.name"
            >
              <CatalogTree :nodes="tab.nodes" />
            </el-tab-pane>
          </el-tabs>
          <el-empty v-else description="目录为空，请重新生成" />
        </el-card>

        <el-card v-if="gaps.length" shadow="never" class="panel">
          <template #header>
            <div class="card-header">
              <span>缺口说明</span>
              <span class="muted">{{ gaps.length }} 项</span>
            </div>
          </template>
          <div v-for="(gap, index) in gaps" :key="index" class="gap-row">
            <el-tag size="small" type="warning" effect="plain">{{ gap.type ?? 'gap' }}</el-tag>
            <span>{{ gap.detail ?? '未提供详情' }}</span>
          </div>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>
            <div class="card-header">
              <span>自然语言调整</span>
              <el-button
                type="primary"
                :icon="Check"
                :loading="adjustLoading"
                :disabled="!adjustPrompt.trim()"
                @click="adjust"
              >
                应用调整
              </el-button>
            </div>
          </template>
          <el-input
            v-model="adjustPrompt"
            type="textarea"
            :rows="3"
            placeholder="例如：将“技术方案”拆分为“总体架构”“数据处理”“算法模型”三个二级章节，并把“售后服务体系”提前到第 3 章。"
          />
        </el-card>
      </template>
    </template>

    <el-drawer v-model="jsonVisible" title="目录 JSON" size="50%">
      <pre class="mono json-view">{{ json }}</pre>
    </el-drawer>
  </div>
</template>

<style scoped>
.operation-hint {
  margin: 0 0 12px;
  line-height: 1.7;
}

.gap-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px dashed #eef0f3;
  line-height: 1.6;
}

.gap-row:last-child {
  border-bottom: none;
}

.json-view {
  padding: 4px 8px 24px;
}
</style>
