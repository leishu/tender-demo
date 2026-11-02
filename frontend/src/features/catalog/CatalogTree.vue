<script setup lang="ts">
import type { CatalogNode } from '@/api/types'

withDefaults(
  defineProps<{
    nodes: CatalogNode[]
    level?: number
  }>(),
  { level: 0 },
)
</script>

<template>
  <ul class="catalog-tree">
    <li v-for="(node, index) in nodes" :key="node.chapter_no ?? `${level}-${index}`">
      <div class="catalog-node" :style="{ paddingLeft: `${level * 22}px` }">
        <span class="chapter-no mono">{{ node.chapter_no ?? '-' }}</span>
        <span class="chapter-title">{{ node.title || '未命名章节' }}</span>
        <el-tag
          v-for="id in node.scoring_item_ids ?? []"
          :key="id"
          size="small"
          type="info"
          effect="plain"
        >
          {{ id }}
        </el-tag>
      </div>
      <p
        v-if="node.content_hint"
        class="chapter-hint"
        :style="{ marginLeft: `${level * 22 + 30}px` }"
      >
        {{ node.content_hint }}
      </p>
      <CatalogTree v-if="node.children?.length" :nodes="node.children ?? []" :level="level + 1" />
    </li>
  </ul>
</template>

<style scoped>
.catalog-tree {
  margin: 0;
  padding: 0;
  list-style: none;
}

.catalog-tree li {
  margin: 0;
}

.catalog-node {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 7px;
  padding-bottom: 7px;
  border-bottom: 1px dashed #eef0f3;
}

.chapter-no {
  flex: none;
  min-width: 54px;
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
}

.chapter-title {
  font-size: 14px;
  font-weight: 500;
}

.chapter-hint {
  margin-top: 0;
  margin-bottom: 8px;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.6;
}
</style>
