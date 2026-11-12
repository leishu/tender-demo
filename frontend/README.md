# tender-demo 前端

Vue 3 + TypeScript + Vite 的投标文件生成工作台，对接仓库根目录的 Spring Boot 服务。

## 技术栈

| 能力 | 选型                                        |
| ---- | ------------------------------------------- |
| 框架 | Vue 3.5（`<script setup lang="ts">`）       |
| 构建 | Vite 7                                      |
| 路由 | Vue Router 5                                |
| 状态 | Pinia 4（setup store）                      |
| UI   | Element Plus 2                              |
| 请求 | openapi-fetch + OpenAPI 类型                |
| SSE  | @microsoft/fetch-event-source（GET + SSE，带 lastEventId 续传） |
| 测试 | Vitest + jsdom                              |

## 启动

```bash
cd frontend
pnpm install
pnpm dev
```

开发服务器默认 `http://localhost:5173`，`/tender/` 与 `/knowledge/` 通过 Vite 代理转发到
`http://localhost:8080`，无需后端额外配置 CORS。前端知识库页面使用 `/knowledge-base`
路由，避免与后端 `/knowledge` 接口前缀冲突。

如果后端不在 8080，修改 `vite.config.ts` 的 `server.proxy`，或在 `.env.local` 中设置：

```bash
VITE_API_BASE_URL=http://localhost:8080
```

> 注意：`VITE_API_BASE_URL` 会让浏览器直连后端，需要后端开启 CORS；默认留空走 Vite 代理。

## 页面

| 路由              | 页面           | 对接接口                                                                        |
| ----------------- | -------------- | ------------------------------------------------------------------------------- |
| `/parse`          | 招标解析       | `POST /tender/parse`（建任务）、`GET /tender/tasks/{id}/events`（SSE 续传）、`POST /tender/parse/confirm` |
| `/match`          | 人员与资质匹配 | `POST /tender/match`（SSE）                                                     |
| `/catalog`        | 投标目录       | `POST /tender/catalog`、`/catalog/adjust`、`GET /catalog`、`POST /catalog/save` |
| `/chapter`        | 章节撰写       | `POST /tender/chapter`                                                          |
| `/knowledge-base` | 知识库         | `POST /knowledge/{name}`、`GET /knowledge/search`                               |

解析页的流程：输入文本 → `POST /tender/parse` 建任务拿到 `taskId` → 订阅
`GET /tender/tasks/{taskId}/events` 增量解析 → 结构化结果可编辑 → 确认入库拿到
`tenderRequirementId` → 跳转目录页。工作流 ID、解析结果、目录结果与 `taskId` 会写入
`sessionStorage`，刷新后按 `taskId` 回到事件流续看（服务端先回放已落库事件），不会重新解析。

匹配页读取已确认入库的 `tenderRequirementId`，并发消费人员与资质两路 SSE；
结果按 `requirementId` 归并，支持按“只看未满足”筛选，单路失败不影响另一路。

## 目录结构

```
src/
├── api/                 # OpenAPI 类型、client、领域 API
│   ├── schema.d.ts      # 手写契约子集，可被 openapi-typescript 覆盖
│   ├── types.ts         # 从 schema 导出的领域类型 + SSE 判别联合
│   ├── client.ts        # openapi-fetch 实例与 ApiError
│   ├── tender.ts
│   └── knowledge.ts
├── composables/
│   ├── useSse.ts        # SSE 通用封装（不自动重连，交给上层按游标重连）
│   ├── useTenderParse.ts# 建任务、订阅事件、断线续传、取消
│   ├── useTenderMatch.ts# 人员/资质双路匹配、按 requirementId 归并
│   └── useAsyncTask.ts  # 长任务 loading/error/取消
├── utils/
│   └── idempotency.ts   # 一次性幂等键：失败重试复用、成功换新
├── features/
│   ├── parse/           # 解析 reducer、编辑器、示例文本
│   └── catalog/         # 递归目录树
├── layouts/AppLayout.vue
├── router/index.ts
├── stores/workflow.ts   # 跨页面工作流状态（sessionStorage 持久化）
├── styles/main.css
└── views/               # 五个业务页 + 404
```

## OpenAPI 类型生成

后端启动后执行：

```bash
pnpm api:generate
```

会从 `http://localhost:8080/v3/api-docs` 生成并覆盖 `src/api/schema.d.ts`。
当前文件是按后端 DTO 手写的同形状子集，保证后端未启动时也能开发；生成后
`src/api/types.ts` 仍从 `components['schemas']` 取类型，无需改动。

## 脚本

```bash
pnpm dev            # 开发
pnpm build          # vue-tsc 类型检查 + 生产构建
pnpm typecheck      # 仅类型检查
pnpm lint           # ESLint
pnpm format         # Prettier
pnpm test           # Vitest
pnpm api:generate   # 从后端 OpenAPI 重新生成类型
```

## 实现约定

- **解析是“建任务 + 订阅事件”**：`POST /tender/parse` 只返回 `taskId`，事件由
  `GET /tender/tasks/{id}/events` 推送。每条事件带递增 `id`，前端记住最后一条；
  连接断开时带 `lastEventId` 退避重连（最多 3 次），服务端只补发缺失的一段。
  重连用尽后查一次 `GET /tender/tasks/{id}`，任务仍在执行就提示稍后刷新，而不是误报解析失败。
- **SSE 用 GET + fetch-event-source**：解析事件流是幂等的 GET，浏览器/网关断开后可以安全重连；
  `useSse.ts` 自身不自动重连，重连策略（次数、退避、游标）由 `useTenderParse` 控制。
  匹配流仍是 `POST /tender/match`。
- **事件按 `module` 分派**：`project` / `qualification` / `performance` /
  `personnel` / `scoringItem` / `error` / `completed`，其中 `completed` 是结束信号，
  `error` 是 HTTP 200 下的业务错误。匹配流同理，`personnel` 与 `qualification`
  两路事件交错到达，按 `requirementId` 覆盖归并。
- **长任务可取消**：目录生成、目录调整、章节撰写都可能耗时几十秒，统一走
  `useAsyncTask`，带 180s 超时和 `AbortController`。
- **Pinia 只放跨页面状态**：当前 `tenderRequirementId`、解析结果、目录结果、解析任务号；
  表单草稿、弹窗开关留在组件内。
- **幂等键只在一次意图内复用**：`utils/idempotency.ts` 生成键，请求失败保留、成功后清空；
  目录/章节重试因此能命中服务端已完成的任务，直接拿缓存结果，不重复调用模型。
- **保存是覆盖语义**：同一招标要求只保留一份目录，UI 已提示“保存会覆盖旧目录”。

## 后续可补

- 目录树拖拽排序 / 改名后回写 `CatalogResult`
- 章节正文 Markdown 渲染与导出 Word
- 解析结果与原文出处联动高亮
- Playwright E2E：解析完成、解析 error、解析断线续传、目录 502、保存后重新查询
