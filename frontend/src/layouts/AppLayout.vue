<script setup lang="ts">
import { computed, markRaw } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Document,
  EditPen,
  Files,
  FolderOpened,
  RefreshLeft,
  Search,
  UserFilled,
} from '@element-plus/icons-vue'
import { useWorkflowStore } from '@/stores/workflow'

const route = useRoute()
const router = useRouter()
const workflow = useWorkflowStore()

const navItems = [
  { path: '/tenders', label: '招标文件', icon: markRaw(FolderOpened) },
  { path: '/parse', label: '招标解析', icon: markRaw(Document) },
  { path: '/match', label: '人员与资质匹配', icon: markRaw(UserFilled) },
  { path: '/catalog', label: '投标目录', icon: markRaw(Files) },
  { path: '/chapter', label: '章节撰写', icon: markRaw(EditPen) },
  { path: '/knowledge-base', label: '知识库', icon: markRaw(Search) },
]

const pageTitle = computed(() => {
  const title = route.meta.title
  return typeof title === 'string' ? title : '投标助手'
})

async function handleReset() {
  try {
    await ElMessageBox.confirm('将清空当前招标要求、解析结果和目录，确定继续？', '重置会话', {
      type: 'warning',
      confirmButtonText: '重置',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  workflow.reset()
  ElMessage.success('会话已重置')
  await router.push('/parse')
}
</script>

<template>
  <el-container class="app-shell">
    <el-aside width="220px" class="sidebar">
      <div class="brand">
        <div class="brand-mark">T</div>
        <div>
          <div class="brand-title">投标助手</div>
          <div class="brand-sub">Tender Copilot</div>
        </div>
      </div>

      <el-menu :default-active="route.path" router class="nav">
        <el-menu-item v-for="item in navItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>

      <div class="sidebar-footer">
        <div class="muted">当前用户</div>
        <div class="user-id mono">{{ workflow.userId }}</div>
      </div>
    </el-aside>

    <el-container class="content">
      <el-header class="topbar">
        <div class="topbar-title">{{ pageTitle }}</div>
        <div class="topbar-actions">
          <el-tag v-if="workflow.tenderRequirementId" type="success" effect="plain">
            招标要求 #{{ workflow.tenderRequirementId }}
          </el-tag>
          <el-tag v-else type="info" effect="plain">未确认解析结果</el-tag>
          <el-button text :icon="RefreshLeft" @click="handleReset">重置会话</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <router-view v-slot="{ Component }">
          <component :is="Component" :key="workflow.sessionKey" />
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-shell {
  height: 100vh;
}

.sidebar {
  display: flex;
  flex-direction: column;
  background: var(--app-sidebar-bg);
  color: var(--app-sidebar-text);
  overflow: hidden;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 18px 16px;
}

.brand-mark {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 10px;
  background: linear-gradient(135deg, #3b82f6, #8b5cf6);
  color: #fff;
  font-weight: 700;
}

.brand-title {
  color: #f8fafc;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.brand-sub {
  color: #94a3b8;
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.nav {
  flex: 1;
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #cbd5e1;
  --el-menu-active-color: #ffffff;
  --el-menu-hover-bg-color: rgba(255, 255, 255, 0.08);
  --el-menu-item-height: 46px;
}

.nav :deep(.el-menu-item) {
  margin: 2px 10px;
  border-radius: 8px;
}

.nav :deep(.el-menu-item.is-active) {
  background: linear-gradient(90deg, rgba(59, 130, 246, 0.9), rgba(139, 92, 246, 0.75));
}

.sidebar-footer {
  padding: 16px 18px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 12px;
}

.user-id {
  margin-top: 4px;
  color: #e2e8f0;
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.content {
  min-width: 0;
}

.topbar {
  display: flex;
  height: 58px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--app-border);
  background: #fff;
}

.topbar-title {
  font-size: 15px;
  font-weight: 600;
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.main {
  padding: 20px 24px 40px;
  overflow: auto;
}

@media (max-width: 900px) {
  .sidebar {
    width: 72px !important;
  }

  .brand > div:last-child,
  .nav :deep(.el-menu-item span),
  .sidebar-footer {
    display: none;
  }

  .nav :deep(.el-menu-item) {
    justify-content: center;
  }
}
</style>
