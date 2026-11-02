<script setup lang="ts">
import { computed, ref } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import type { TenderRequirements } from '@/api/types'

const requirements = defineModel<TenderRequirements>({ required: true })
const activeTab = ref('project')

const qualifications = computed(() => requirements.value.qualifications ?? [])
const performances = computed(() => requirements.value.performances ?? [])
const personnel = computed(() => requirements.value.personnel ?? [])
const scoringItems = computed(() => requirements.value.scoringItems ?? [])

function addQualification() {
  requirements.value.qualifications ??= []
  requirements.value.qualifications.push({ type: '', level: '', validRequirement: '', source: '' })
}

function addPerformance() {
  requirements.value.performances ??= []
  requirements.value.performances.push({
    projectType: '',
    quantity: '',
    amount: '',
    timeRange: '',
    source: '',
  })
}

function addPersonnel() {
  requirements.value.personnel ??= []
  requirements.value.personnel.push({ role: '', quantity: 1, certificate: '', source: '' })
}

function addScoringItem() {
  requirements.value.scoringItems ??= []
  requirements.value.scoringItems.push({
    itemId: '',
    name: '',
    score: 0,
    criterion: '',
    suggestedChapter: '',
    source: '',
  })
}

function removeAt<T>(list: T[] | undefined, index: number) {
  list?.splice(index, 1)
}
</script>

<template>
  <el-tabs v-model="activeTab" class="requirements-editor">
    <el-tab-pane label="项目概况" name="project">
      <el-form label-width="92px" class="project-form">
        <el-row :gutter="16">
          <el-col :xs="24" :md="12">
            <el-form-item label="项目名称">
              <el-input
                v-model="requirements.projectName"
                placeholder="如 某市公共文化服务中心建设项目"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="建设地点">
              <el-input v-model="requirements.location" placeholder="如 某市新区文化路 88 号" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="工期要求">
              <el-input v-model="requirements.duration" placeholder="如 540 日历天" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="预算 / 上限">
              <el-input v-model="requirements.budget" placeholder="如 人民币 28600 万元" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </el-tab-pane>

    <el-tab-pane :label="`资质要求 (${qualifications.length})`" name="qualification">
      <div class="table-toolbar">
        <span class="muted">解析出的资质要求，可修改、删除或补充。</span>
        <el-button size="small" :icon="Plus" @click="addQualification">新增资质</el-button>
      </div>
      <el-table :data="qualifications" border size="small" empty-text="暂无资质要求">
        <el-table-column label="资质类型" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.type" placeholder="如 施工总承包" />
          </template>
        </el-table-column>
        <el-table-column label="等级" width="130">
          <template #default="{ row }">
            <el-input v-model="row.level" placeholder="如 壹级" />
          </template>
        </el-table-column>
        <el-table-column label="有效期要求" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.validRequirement" placeholder="原文未提供时填写“未明确”" />
          </template>
        </el-table-column>
        <el-table-column label="原文出处" min-width="220">
          <template #default="{ row }">
            <el-input v-model="row.source" placeholder="原文片段" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="64" fixed="right">
          <template #default="{ $index }">
            <el-button
              link
              type="danger"
              :icon="Delete"
              @click="removeAt(requirements.qualifications, $index)"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-tab-pane>

    <el-tab-pane :label="`业绩要求 (${performances.length})`" name="performance">
      <div class="table-toolbar">
        <span class="muted">类似项目类型、数量、金额与时间范围。</span>
        <el-button size="small" :icon="Plus" @click="addPerformance">新增业绩</el-button>
      </div>
      <el-table :data="performances" border size="small" empty-text="暂无业绩要求">
        <el-table-column label="类似项目类型" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.projectType" placeholder="如 公共建筑工程施工总承包" />
          </template>
        </el-table-column>
        <el-table-column label="数量" width="110">
          <template #default="{ row }">
            <el-input v-model="row.quantity" placeholder="如 1 个" />
          </template>
        </el-table-column>
        <el-table-column label="金额下限" width="150">
          <template #default="{ row }">
            <el-input v-model="row.amount" placeholder="如 ≥ 1.5 亿元" />
          </template>
        </el-table-column>
        <el-table-column label="时间范围" width="160">
          <template #default="{ row }">
            <el-input v-model="row.timeRange" placeholder="如 近五年" />
          </template>
        </el-table-column>
        <el-table-column label="原文出处" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.source" placeholder="原文片段" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="64" fixed="right">
          <template #default="{ $index }">
            <el-button
              link
              type="danger"
              :icon="Delete"
              @click="removeAt(requirements.performances, $index)"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-tab-pane>

    <el-tab-pane :label="`人员要求 (${personnel.length})`" name="personnel">
      <div class="table-toolbar">
        <span class="muted">关键岗位、人数与证书要求。</span>
        <el-button size="small" :icon="Plus" @click="addPersonnel">新增人员</el-button>
      </div>
      <el-table :data="personnel" border size="small" empty-text="暂无人员要求">
        <el-table-column label="岗位" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.role" placeholder="如 项目经理" />
          </template>
        </el-table-column>
        <el-table-column label="人数" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="0" controls-position="right" />
          </template>
        </el-table-column>
        <el-table-column label="证书 / 职称" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.certificate" placeholder="如 一级建造师" />
          </template>
        </el-table-column>
        <el-table-column label="原文出处" min-width="220">
          <template #default="{ row }">
            <el-input v-model="row.source" placeholder="原文片段" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="64" fixed="right">
          <template #default="{ $index }">
            <el-button
              link
              type="danger"
              :icon="Delete"
              @click="removeAt(requirements.personnel, $index)"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-tab-pane>

    <el-tab-pane :label="`评分点 (${scoringItems.length})`" name="scoringItem">
      <div class="table-toolbar">
        <span class="muted">评分点会用于目录生成与章节撰写，请确认编号与分值。</span>
        <el-button size="small" :icon="Plus" @click="addScoringItem">新增评分点</el-button>
      </div>
      <el-table :data="scoringItems" border size="small" empty-text="暂无评分点">
        <el-table-column label="编号" width="120">
          <template #default="{ row }">
            <el-input v-model="row.itemId" placeholder="SC-01" />
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.name" placeholder="如 施工组织设计" />
          </template>
        </el-table-column>
        <el-table-column label="分值" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.score" :min="0" controls-position="right" />
          </template>
        </el-table-column>
        <el-table-column label="评审标准" min-width="240">
          <template #default="{ row }">
            <el-input
              v-model="row.criterion"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 3 }"
            />
          </template>
        </el-table-column>
        <el-table-column label="建议章节" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.suggestedChapter" placeholder="如 施工组织设计" />
          </template>
        </el-table-column>
        <el-table-column label="原文出处" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.source" placeholder="原文片段" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="64" fixed="right">
          <template #default="{ $index }">
            <el-button
              link
              type="danger"
              :icon="Delete"
              @click="removeAt(requirements.scoringItems, $index)"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-tab-pane>
  </el-tabs>
</template>

<style scoped>
.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.project-form {
  max-width: 900px;
}
</style>
