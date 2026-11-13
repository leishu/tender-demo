<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, MagicStick } from '@element-plus/icons-vue'
import { useAsyncTask } from '@/composables/useAsyncTask'
import { tenderApi } from '@/api/tender'
import { newIdempotencyKey } from '@/utils/idempotency'
import { getSessionId, rememberSessionId } from '@/utils/session'
import { useWorkflowStore } from '@/stores/workflow'
import type { CatalogNode } from '@/api/types'

const workflow = useWorkflowStore()
const { loading, error, run, cancel } = useAsyncTask()

const chapterTitle = ref('')
const scoringPointIds = ref<string[]>([])
const content = ref('')

/**
 * 幂等键：同一章节 + 同一评分点组合内保持不变。
 * 失败重试会命中同一任务，不重复调用模型；改动章节或评分点后自动换新键。
 */
let chapterKey: string | null = null

watch([chapterTitle, scoringPointIds], () => {
  chapterKey = null
})

function flatten(nodes: CatalogNode[] | undefined, acc: CatalogNode[] = []): CatalogNode[] {
  for (const node of nodes ?? []) {
    acc.push(node)
    flatten(node.children, acc)
  }
  return acc
}

const allNodes = computed(() => {
  const result = workflow.catalogResult
  if (!result) {
    return []
  }
  if (result.catalog?.length) {
    return flatten(result.catalog)
  }
  return [...flatten(result.business_catalog), ...flatten(result.technical_catalog)]
})

const chapterOptions = computed(() => {
  const seen = new Set<string>()
  const options: Array<{ value: string; label: string }> = []
  for (const node of allNodes.value) {
    if (!node.title || seen.has(node.title)) {
      continue
    }
    seen.add(node.title)
    options.push({
      value: node.title,
      label: [node.chapter_no, node.title].filter(Boolean).join(' '),
    })
  }
  return options
})

const scoringOptions = computed(() =>
  (workflow.requirements?.scoringItems ?? [])
    .map((item) => ({
      value: item.itemId || item.name || '',
      label: [item.itemId, item.name].filter(Boolean).join(' · '),
    }))
    .filter((option) => option.value),
)

const wordCount = computed(() => content.value.replace(/\s/g, '').length)

async function generate() {
  const title = chapterTitle.value.trim()
  if (!title) {
    ElMessage.warning('请先填写章节标题')
    return
  }
  const key = chapterKey ?? newIdempotencyKey('chapter')
  chapterKey = key
  // 同一份招标的章节续写同一段会话：后写的章节能看到前文，术语与行文保持一致。
  // 还没有历史会话（首次撰写、或换了一份招标）时不传，由服务端签发。
  const requirementId = workflow.tenderRequirementId
  const result = await run((signal) =>
    tenderApi.generateChapter(
      {
        userId: workflow.userId,
        sessionId: getSessionId(requirementId, 'chapter') ?? undefined,
        chapterTitle: title,
        scoringPointIds: scoringPointIds.value.join(','),
      },
      signal,
      key,
    ),
  )
  if (result === undefined) {
    // 失败保留幂等键：下次点击是重试
    return
  }
  chapterKey = null
  rememberSessionId(requirementId, 'chapter', result.sessionId)
  content.value = result.data
  ElMessage.success('章节生成完成')
}

async function copyContent() {
  if (!content.value) {
    return
  }
  try {
    await navigator.clipboard.writeText(content.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动选择文本')
  }
}
</script>

<template>
  <div class="page">
    <div class="page-intro">
      <div>
        <h1>章节撰写</h1>
        <p class="muted">
          选择目录章节并关联评分点，由模型生成章节正文。生成结果可复制到 Word 或继续人工修订。
        </p>
      </div>
      <el-tag v-if="workflow.tenderRequirementId" type="success" effect="plain">
        招标要求 #{{ workflow.tenderRequirementId }}
      </el-tag>
    </div>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="card-header">
          <span>撰写参数</span>
          <span class="muted">目录与评分点来自当前工作流</span>
        </div>
      </template>

      <el-form label-width="96px" class="chapter-form">
        <el-form-item label="章节标题">
          <el-select
            v-model="chapterTitle"
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="从目录选择，或直接输入章节标题"
            class="full-width"
          >
            <el-option
              v-for="option in chapterOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="关联评分点">
          <el-select
            v-model="scoringPointIds"
            multiple
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="选择评分点；也可以直接输入评分点 ID 后回车"
            class="full-width"
          >
            <el-option
              v-for="option in scoringOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <div class="form-actions">
        <span class="muted">
          {{
            chapterOptions.length
              ? `可选章节 ${chapterOptions.length} 个`
              : '尚未生成目录，可直接输入章节标题'
          }}
        </span>
        <div class="spacer" />
        <el-button v-if="loading" @click="cancel">取消生成</el-button>
        <el-button
          type="primary"
          :icon="MagicStick"
          :loading="loading"
          :disabled="!chapterTitle.trim()"
          @click="generate"
        >
          生成章节
        </el-button>
      </div>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mt" />
    </el-card>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="card-header">
          <span>生成结果</span>
          <div class="header-actions">
            <span v-if="content" class="muted">{{ wordCount }} 字</span>
            <el-button :icon="CopyDocument" :disabled="!content" @click="copyContent">
              复制
            </el-button>
          </div>
        </div>
      </template>

      <el-empty v-if="!content" description="生成结果会显示在这里" />
      <pre v-else class="chapter-output">{{ content }}</pre>
    </el-card>
  </div>
</template>

<style scoped>
.chapter-form {
  max-width: 820px;
}

.full-width {
  width: 100%;
}

.chapter-output {
  max-height: 640px;
  overflow: auto;
  padding: 16px;
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: #fafafa;
  font-size: 14px;
  line-height: 1.9;
}
</style>
