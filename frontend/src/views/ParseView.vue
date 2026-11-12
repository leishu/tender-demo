<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Check, Close, Delete, Document, Refresh, VideoPlay } from '@element-plus/icons-vue'
import RequirementsEditor from '@/features/parse/RequirementsEditor.vue'
import { SAMPLE_TENDER_TEXT } from '@/features/parse/sample'
import { moduleLabel, useTenderParse } from '@/composables/useTenderParse'
import { useAsyncTask } from '@/composables/useAsyncTask'
import { tenderApi } from '@/api/tender'
import { newIdempotencyKey } from '@/utils/idempotency'
import { useWorkflowStore } from '@/stores/workflow'
import type { ParseModule } from '@/api/types'

const router = useRouter()
const workflow = useWorkflowStore()
const tenderText = ref('')

const {
  status,
  errorMessage,
  requirements,
  logs,
  taskId,
  isRunning,
  hasData,
  counts,
  start,
  resume,
  cancel,
  toRequirements,
} = useTenderParse()

const { loading: confirmLoading, error: confirmError, run: runConfirm } = useAsyncTask()

/**
 * 同一次解析结果复用同一个幂等键：重复点击或失败重试都不会重复入库。
 * 重新解析时清空，避免旧 key 把新结果挡掉。
 */
let confirmIdempotencyKey: string | null = null

// 任务号落进工作流状态（sessionStorage）：刷新页面后据此回到事件流续看，而不是重新解析
watch(taskId, (id) => {
  workflow.setParseTaskId(id)
})

// 解析正常完成/取消后不再需要续看：清掉任务号，
// 避免从目录页返回解析页时又回放一遍事件、覆盖用户手工改过的结果
watch(status, (value) => {
  if (value === 'done' || value === 'cancelled') {
    workflow.setParseTaskId(null)
  }
})

/**
 * 页面刷新/切走回来时续看上一次的解析任务。
 * 服务端会先回放已落库的事件再续实时流，不会重新调用模型。
 */
onMounted(async () => {
  const saved = workflow.parseTaskId
  if (saved) {
    await resume(saved)
  }
})

const step = computed(() => {
  if (workflow.tenderRequirementId) {
    return 3
  }
  if (status.value === 'done') {
    return 2
  }
  if (isRunning.value || hasData.value) {
    return 1
  }
  return 0
})

const statusLabel = computed(() => {
  const labels = {
    idle: '待开始',
    connecting: '连接中',
    streaming: '解析中',
    reconnecting: '重新连接中',
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
  if (status.value === 'streaming' || status.value === 'reconnecting') {
    return 'warning'
  }
  return 'info'
})

function loadSample() {
  tenderText.value = SAMPLE_TENDER_TEXT
}

function clearText() {
  tenderText.value = ''
}

async function startParse() {
  workflow.clearParsed()
  confirmIdempotencyKey = null
  await start(tenderText.value, workflow.userId)
  if (status.value === 'done') {
    ElMessage.success('解析完成，请核对结果后确认入库')
  }
}

async function confirm() {
  const payload = toRequirements()
  confirmIdempotencyKey ??= newIdempotencyKey()
  const id = await runConfirm((signal) =>
    tenderApi.confirm(payload, signal, confirmIdempotencyKey ?? undefined),
  )
  if (id === undefined) {
    return
  }
  workflow.setRequirements(payload)
  workflow.setTenderRequirementId(id)
  ElMessage.success(`已入库，招标要求 ID：${id}`)
  await router.push('/catalog')
}

function moduleTagType(module: ParseModule): 'success' | 'warning' | 'info' | 'danger' {
  if (module === 'error') {
    return 'danger'
  }
  if (module === 'completed') {
    return 'success'
  }
  if (module === 'scoringItem') {
    return 'warning'
  }
  return 'info'
}

function formatClock(at: number) {
  return new Date(at).toLocaleTimeString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="page">
    <div class="page-intro">
      <div>
        <h1>招标文件解析</h1>
        <p class="muted">
          粘贴招标文件正文，后端会通过 SSE 实时推送结构化要求。解析完成后可逐项修订，再确认入库。
        </p>
      </div>
      <div class="header-actions">
        <el-tag v-if="taskId" type="info" effect="plain" size="small">任务 #{{ taskId }}</el-tag>
        <el-tag v-if="workflow.tenderRequirementId" type="success" effect="plain">
          当前招标要求 #{{ workflow.tenderRequirementId }}
        </el-tag>
      </div>
    </div>

    <el-steps :active="step" finish-status="success" simple class="steps">
      <el-step title="输入招标文件" />
      <el-step title="流式解析" />
      <el-step title="确认入库" />
    </el-steps>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="11">
        <el-card shadow="never" class="panel">
          <template #header>
            <div class="card-header">
              <span>招标文件正文</span>
              <span class="muted">{{ tenderText.length }} 字</span>
            </div>
          </template>

          <el-input
            v-model="tenderText"
            type="textarea"
            :rows="16"
            resize="vertical"
            placeholder="粘贴招标文件正文，建议包含项目概况、资格要求、业绩要求、人员要求、评标办法等章节。"
            :disabled="isRunning"
          />

          <div class="form-actions">
            <el-button :icon="Document" :disabled="isRunning" @click="loadSample">
              填入示例
            </el-button>
            <el-button :icon="Delete" :disabled="!tenderText || isRunning" @click="clearText">
              清空
            </el-button>
            <div class="spacer" />
            <el-button v-if="isRunning" :icon="Close" @click="cancel">取消解析</el-button>
            <el-button
              type="primary"
              :icon="VideoPlay"
              :loading="status === 'connecting'"
              :disabled="!tenderText.trim() || isRunning"
              @click="startParse"
            >
              开始解析
            </el-button>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="13">
        <el-card shadow="never" class="panel">
          <template #header>
            <div class="card-header">
              <span>解析进度</span>
              <el-tag :type="statusType" effect="plain">{{ statusLabel }}</el-tag>
            </div>
          </template>

          <div class="status-grid">
            <div class="status-item">
              <span class="status-value">{{ counts.qualification }}</span>
              <span class="muted">资质要求</span>
            </div>
            <div class="status-item">
              <span class="status-value">{{ counts.performance }}</span>
              <span class="muted">业绩要求</span>
            </div>
            <div class="status-item">
              <span class="status-value">{{ counts.personnel }}</span>
              <span class="muted">人员要求</span>
            </div>
            <div class="status-item">
              <span class="status-value">{{ counts.scoringItem }}</span>
              <span class="muted">评分点</span>
            </div>
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
            v-else-if="status === 'reconnecting'"
            title="与解析服务的连接已断开，正在从断点续传；已解析出来的内容不会丢失"
            type="warning"
            show-icon
            :closable="false"
            class="mb"
          />
          <el-alert
            v-else-if="status === 'cancelled'"
            title="解析已取消，可修改文本后重新开始"
            type="info"
            show-icon
            :closable="false"
            class="mb"
          />

          <div class="stream-log">
            <el-empty v-if="!logs.length" description="等待解析事件" :image-size="64" />
            <div v-for="entry in logs" :key="entry.id" class="log-row">
              <el-tag size="small" :type="moduleTagType(entry.module)">
                {{ moduleLabel(entry.module) }}
              </el-tag>
              <span class="log-summary">{{ entry.summary }}</span>
              <span class="log-time">{{ formatClock(entry.at) }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="hasData" shadow="never" class="panel">
      <template #header>
        <div class="card-header">
          <span>解析结果（可直接修改）</span>
          <div class="header-actions">
            <el-button :icon="Refresh" :disabled="isRunning" @click="startParse">
              重新解析
            </el-button>
            <el-button
              type="primary"
              :icon="Check"
              :loading="confirmLoading"
              :disabled="isRunning"
              @click="confirm"
            >
              确认入库
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="confirmError"
        :title="confirmError"
        type="error"
        show-icon
        :closable="false"
        class="mb"
      />
      <el-alert
        v-if="!isRunning && !requirements.projectName"
        title="未收到项目概况，项目名称/地点/工期/预算入库后会为空，建议重新解析"
        type="warning"
        show-icon
        :closable="false"
        class="mb"
      />

      <RequirementsEditor v-model="requirements" />
    </el-card>
  </div>
</template>

<style scoped>
.steps {
  padding: 14px 18px;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  background: #fff;
}

.steps :deep(.el-step__title) {
  font-size: 13px;
}
</style>
