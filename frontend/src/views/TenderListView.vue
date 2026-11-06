<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Files, Plus, Refresh, Search, UserFilled } from '@element-plus/icons-vue'
import { tenderApi } from '@/api/tender'
import { useAsyncTask } from '@/composables/useAsyncTask'
import { useWorkflowStore } from '@/stores/workflow'
import type { TenderRequirementSummary } from '@/api/types'

const router = useRouter()
const workflow = useWorkflowStore()

const keyword = ref('')
const appliedKeyword = ref('')
const rows = ref<TenderRequirementSummary[]>([])
const { loading, error, run } = useAsyncTask()

const totalRequirements = computed(() =>
  rows.value.reduce((sum, row) => sum + requirementCount(row), 0),
)

function requirementCount(row: TenderRequirementSummary) {
  return (
    (row.qualificationCount ?? 0) +
    (row.performanceCount ?? 0) +
    (row.personnelCount ?? 0) +
    (row.scoringItemCount ?? 0)
  )
}

function isCurrent(row: TenderRequirementSummary) {
  return row.id != null && row.id === workflow.tenderRequirementId
}

async function load() {
  const data = await run((signal) =>
    tenderApi.listRequirements({ keyword: appliedKeyword.value || undefined, limit: 100 }, signal),
  )
  if (data === undefined) {
    return
  }
  rows.value = data
}

function search() {
  appliedKeyword.value = keyword.value.trim()
  void load()
}

function resetSearch() {
  keyword.value = ''
  appliedKeyword.value = ''
  void load()
}

function startNewParse() {
  workflow.clearParsed()
  void router.push('/parse')
}

/** 跳转前把选中的招标要求写进工作流，匹配页直接使用该 id */
function openMatch(row: TenderRequirementSummary) {
  if (row.id == null) {
    return
  }
  workflow.clearParsed()
  workflow.setTenderRequirementId(row.id)
  void router.push('/match')
}

/**
 * 生成目录：已有目录时带上 load=saved，目录页会自动读取已保存的目录；
 * 未生成过则停留空状态，由用户点击生成。
 */
function openCatalog(row: TenderRequirementSummary) {
  if (row.id == null) {
    return
  }
  workflow.clearParsed()
  workflow.setTenderRequirementId(row.id)
  void router.push(row.hasCatalog ? { path: '/catalog', query: { load: 'saved' } } : '/catalog')
}

function rowClassName({ row }: { row: TenderRequirementSummary }) {
  return isCurrent(row) ? 'is-current-row' : ''
}

function display(value?: string) {
  return value && value.trim() ? value : '—'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-intro">
      <div>
        <h1>已解析的招标文件</h1>
        <p class="muted">
          这里汇总所有确认入库的招标文件。可以直接对某一份发起人员/资质匹配或生成投标目录，也可以从这里开始一份新的解析。
        </p>
      </div>
      <el-button type="primary" :icon="Plus" @click="startNewParse">开始新的解析</el-button>
    </div>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="card-header">
          <span>文件列表</span>
          <span class="muted">
            共 {{ rows.length }} 份 · {{ totalRequirements }} 条结构化要求
          </span>
        </div>
      </template>

      <div class="toolbar">
        <el-input
          v-model="keyword"
          class="search-input"
          placeholder="按项目名称搜索"
          clearable
          :prefix-icon="Search"
          @keyup.enter="search"
          @clear="resetSearch"
        />
        <el-button :icon="Search" :loading="loading" @click="search">查询</el-button>
        <el-button :disabled="loading" @click="resetSearch">重置</el-button>
        <div class="spacer" />
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mb" />

      <el-table
        v-loading="loading"
        :data="rows"
        :row-class-name="rowClassName"
        row-key="id"
        class="tender-table"
      >
        <el-table-column label="项目名称" min-width="280">
          <template #default="{ row }">
            <div class="project-cell">
              <div class="project-name">
                <span>{{ display(row.projectName) }}</span>
                <el-tag v-if="isCurrent(row)" size="small" type="success" effect="plain">
                  当前
                </el-tag>
              </div>
              <div class="project-meta muted">
                <span class="mono">#{{ row.id }}</span>
                <span>{{ display(row.location) }}</span>
                <span>{{ display(row.duration) }}</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="结构化要求" min-width="300">
          <template #default="{ row }">
            <div class="requirement-tags">
              <el-tag size="small" effect="plain">资质 {{ row.qualificationCount ?? 0 }}</el-tag>
              <el-tag size="small" effect="plain" type="success">
                业绩 {{ row.performanceCount ?? 0 }}
              </el-tag>
              <el-tag size="small" effect="plain" type="warning">
                人员 {{ row.personnelCount ?? 0 }}
              </el-tag>
              <el-tag size="small" effect="plain" type="danger">
                评分点 {{ row.scoringItemCount ?? 0 }}
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="预算" min-width="150">
          <template #default="{ row }">
            <span class="muted">{{ display(row.budget) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="投标目录" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.hasCatalog" size="small" type="success" effect="plain">
              已生成
            </el-tag>
            <span v-else class="muted">未生成</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" :icon="UserFilled" @click="openMatch(row)">
                匹配
              </el-button>
              <el-button link type="primary" :icon="Files" @click="openCatalog(row)">
                {{ row.hasCatalog ? '查看目录' : '生成目录' }}
              </el-button>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty
            :description="
              appliedKeyword ? `没有匹配「${appliedKeyword}」的招标文件` : '还没有已解析的招标文件'
            "
          >
            <el-button v-if="appliedKeyword" @click="resetSearch">清空搜索</el-button>
            <el-button v-else type="primary" :icon="Plus" @click="startNewParse">
              去解析一份招标文件
            </el-button>
          </el-empty>
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.search-input {
  max-width: 280px;
}

.tender-table {
  width: 100%;
}

.project-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.project-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  line-height: 1.5;
}

.project-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
}

.requirement-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.row-actions {
  display: flex;
  gap: 4px;
}

.tender-table :deep(.is-current-row) {
  --el-table-tr-bg-color: #f2f8f4;
}

@media (max-width: 768px) {
  .toolbar {
    flex-wrap: wrap;
  }

  .search-input {
    max-width: none;
    flex: 1 1 160px;
  }
}
</style>
