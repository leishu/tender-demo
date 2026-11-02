import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: () => import('@/layouts/AppLayout.vue'),
      children: [
        {
          path: '',
          redirect: '/parse',
        },
        {
          path: 'parse',
          name: 'parse',
          component: () => import('@/views/ParseView.vue'),
          meta: { title: '招标解析' },
        },
        {
          path: 'catalog',
          name: 'catalog',
          component: () => import('@/views/CatalogView.vue'),
          meta: { title: '投标目录' },
        },
        {
          path: 'chapter',
          name: 'chapter',
          component: () => import('@/views/ChapterView.vue'),
          meta: { title: '章节撰写' },
        },
        {
          path: 'knowledge-base',
          name: 'knowledge-base',
          component: () => import('@/views/KnowledgeView.vue'),
          meta: { title: '知识库' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: { title: '页面不存在' },
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : ''
  document.title = title ? `${title} · 投标助手` : '投标助手'
})

export default router
