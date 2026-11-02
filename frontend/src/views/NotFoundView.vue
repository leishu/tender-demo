<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="not-found">
    <el-result icon="warning" title="404" sub-title="页面不存在或已被移动">
      <template #extra>
        <el-button type="primary" @click="router.push('/parse')">返回招标解析</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.not-found {
  display: grid;
  min-height: 100vh;
  place-items: center;
  background: #f5f7fa;
}
</style>
