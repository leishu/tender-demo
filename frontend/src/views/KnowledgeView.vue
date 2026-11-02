<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search, Upload } from '@element-plus/icons-vue'
import { useAsyncTask } from '@/composables/useAsyncTask'
import { knowledgeApi } from '@/api/knowledge'
import { formatTime } from '@/utils/async'
import type { KnowledgeSearchHit } from '@/api/types'

const activeTab = ref('search')

const searchForm = reactive({
  knowledgeBaseName: '',
  query: '',
  topK: 10,
})
const hits = ref<KnowledgeSearchHit[]>([])
const { loading: searchLoading, error: searchError, run: runSearch } = useAsyncTask()

const saveForm = reactive({
  knowledgeBaseName: 'tender-docs',
  text: '',
})
const savedId = ref<number | null>(null)
const { loading: saveLoading, error: saveError, run: runSave } = useAsyncTask()

async function search() {
  const query = searchForm.query.trim()
  if (!query) {
    ElMessage.warning('请输入检索内容')
    return
  }
  const names = searchForm.knowledgeBaseName
    .split(',')
    .map((name) => name.trim())
    .filter(Boolean)
  const result = await runSearch((signal) =>
    knowledgeApi.search(
      {
        knowledgeBaseName: names.length ? names : undefined,
        query,
        topK: searchForm.topK,
      },
      signal,
    ),
  )
  if (!result) {
    return
  }
  hits.value = result
  ElMessage.success(`命中 ${result.length} 条`)
}

async function save() {
  const name = saveForm.knowledgeBaseName.trim()
  const contents = saveForm.text
    .split(/\n\s*\n/)
    .map((item) => item.trim())
    .filter(Boolean)
  if (!name) {
    ElMessage.warning('请填写知识库名称')
    return
  }
  if (!contents.length) {
    ElMessage.warning('请至少输入一条文本')
    return
  }
  const id = await runSave((signal) => knowledgeApi.save(name, contents, signal))
  if (id === undefined) {
    return
  }
  savedId.value = id
  ElMessage.success(`写入成功，知识库 ID：${id}`)
}

function resetSearch() {
  searchForm.query = ''
  hits.value = []
}
</script>

<template>
  <div class="page">
    <div class="page-intro">
      <div>
        <h1>知识库</h1>
        <p class="muted">
          基于 Elasticsearch 的 BM25 + kNN 混合检索。写入时自动向量化，检索失败时会降级为全文检索。
        </p>
      </div>
    </div>

    <el-card shadow="never" class="panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="混合检索" name="search">
          <el-form label-width="110px" class="knowledge-form">
            <el-form-item label="知识库名称">
              <el-input
                v-model="searchForm.knowledgeBaseName"
                placeholder="可选，多个用英文逗号分隔；留空则跨所有知识库检索"
                clearable
              />
            </el-form-item>
            <el-form-item label="查询内容">
              <el-input
                v-model="searchForm.query"
                type="textarea"
                :rows="3"
                placeholder="例如：投标人需要具备什么资质？"
                @keydown.ctrl.enter="search"
              />
            </el-form-item>
            <el-form-item label="返回条数">
              <el-input-number v-model="searchForm.topK" :min="1" :max="50" />
            </el-form-item>
          </el-form>

          <div class="form-actions">
            <el-button :icon="Refresh" :disabled="!hits.length" @click="resetSearch">
              清空结果
            </el-button>
            <div class="spacer" />
            <el-button
              type="primary"
              :icon="Search"
              :loading="searchLoading"
              :disabled="!searchForm.query.trim()"
              @click="search"
            >
              检索
            </el-button>
          </div>

          <el-alert
            v-if="searchError"
            :title="searchError"
            type="error"
            show-icon
            :closable="false"
            class="mt"
          />

          <div class="results">
            <el-empty v-if="!hits.length" description="检索结果会显示在这里" />
            <div v-for="hit in hits" :key="hit.id" class="hit-row">
              <div class="hit-head">
                <span class="mono hit-id">{{ hit.id }}</span>
                <el-tag v-if="hit.score !== undefined" size="small" type="success" effect="plain">
                  score {{ hit.score.toFixed(4) }}
                </el-tag>
                <span class="muted">{{ formatTime(hit.createdAt) }}</span>
              </div>
              <p class="hit-content">{{ hit.content }}</p>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="写入文档" name="save">
          <el-form label-width="110px" class="knowledge-form">
            <el-form-item label="知识库名称">
              <el-input v-model="saveForm.knowledgeBaseName" placeholder="如 tender-docs" />
            </el-form-item>
            <el-form-item label="文档内容">
              <el-input
                v-model="saveForm.text"
                type="textarea"
                :rows="12"
                placeholder="粘贴文本；多条文档之间用空行分隔。空白内容会被忽略。"
              />
            </el-form-item>
          </el-form>

          <div class="form-actions">
            <span v-if="savedId !== null" class="muted">最近写入的知识库 ID：{{ savedId }}</span>
            <div class="spacer" />
            <el-button
              type="primary"
              :icon="Upload"
              :loading="saveLoading"
              :disabled="!saveForm.text.trim()"
              @click="save"
            >
              写入知识库
            </el-button>
          </div>

          <el-alert
            v-if="saveError"
            :title="saveError"
            type="error"
            show-icon
            :closable="false"
            class="mt"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.knowledge-form {
  max-width: 860px;
}

.results {
  margin-top: 16px;
}

.hit-row {
  padding: 14px 0;
  border-bottom: 1px dashed #eef0f3;
}

.hit-row:last-child {
  border-bottom: none;
}

.hit-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.hit-id {
  font-size: 12px;
  color: #2563eb;
}

.hit-content {
  margin: 0;
  line-height: 1.8;
  white-space: pre-wrap;
}
</style>
