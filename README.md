# tender-demo

基于大模型的投标文件生成后端服务（`tender-service`）：输入招标文件文本，由多 Agent 协作完成招标要求解析、投标目录生成和章节撰写，并提供基于 Elasticsearch 的知识库混合检索。

## 技术栈

| 组件 | 版本/说明 |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.1（WebFlux，SSE 流式输出） |
| AgentScope | 2.0.4（Harness Agent + DashScope Chat Model） |
| MyBatis-Plus | 3.5.17 |
| MySQL | 结构化数据存储 |
| Elasticsearch | 9.3.3（BM25 + kNN + RRF 混合检索） |
| Springdoc OpenAPI | 3.1.1（在线接口文档） |
| Langfuse | OTLP/HTTP 链路追踪（可选，`cloud.langfuse.com`） |

## 主要能力

- **招标文件解析**：`POST /tender/parse`，以 SSE 实时推送 project / qualification / performance / personnel / scoringItem 等结构化事件。
- **解析结果入库**：`POST /tender/parse/confirm`，主表 + 资质/业绩/人员/评分点四张子表持久化。
- **已解析文件列表**：`GET /tender/requirements`，返回已入库招标要求的项目概况、各类要求条数与是否已生成目录，
  支持按项目名称模糊搜索（`keyword`）与条数上限（`limit`，默认 50、最大 200）。
- **投标目录生成**：`POST /tender/catalog`，输出最多三级的目录 JSON，支持商务/技术分列。
- **章节撰写**：`POST /tender/chapter`，结合评分点生成章节正文。
- **人员与企业资质匹配**：`POST /tender/match`，并发调度 `personnel-matcher` 与 `qualification-matcher`
  两个子 Agent，匹配完一条就通过 SSE 推送一条，全部结束后推送 `completed`。
  首轮缺少结果时只针对缺失的 `requirementId` 自动补跑，次数由 `MATCHING_MAX_RETRIES` 控制（默认 1，0 表示关闭）。
- **知识库**：`POST /knowledge/{knowledgeBaseName}` 批量写入并向量化，`GET /knowledge/search` 混合检索。
- **Agent 编排**：主 Agent 调度 `tender-parser`、`catalog-builder`、`chapter-writer`、
  `personnel-matcher`、`qualification-matcher` 子 Agent，规则见工作区 `AGENTS.md`。

## 快速开始

### 1. 环境准备

- JDK 21
- Maven 3.9+
- MySQL 8+
- Elasticsearch 9.x（知识库功能需要，未配置时解析/目录/章节不受影响）

### 2. 初始化数据库

在 MySQL 中创建库并执行建表脚本：

```bash
mysql -u root -p -e "CREATE DATABASE tender_demo DEFAULT CHARSET utf8mb4;"
mysql -u root -p tender_demo < src/main/db/tender_requirements.sql
mysql -u root -p tender_demo < src/main/db/enterprise_data.sql
mysql -u root -p tender_demo < src/main/db/knowledge_base.sql
mysql -u root -p tender_demo < src/main/db/bid_catalog.sql
```

企业资质与人员的测试数据（可选，仅测试环境使用，会先清空这四张表）：

```bash
mysql -u root -p tender_demo < src/main/db/test-data-qualification-personnel.sql
```

### 3. 修改配置

配置位于 `src/main/resources/application.yml`，主要项：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/tender_demo?useUnicode=true&characterEncoding=utf8
    username: <用户名>
    password: <密码>

agentscope:
  model: glm-5.2
  # 由环境变量注入，勿写入配置文件
  api-key: ${AGENTSCOPE_API_KEY:}

knowledge-base:
  es:
    uris: ${ES_URIS:http://127.0.0.1:9200}
    username: ${ES_USERNAME:}
    password: ${ES_PASSWORD:}
  embedding:
    provider: es   # es 或 dashscope
```

ES 连接信息与 DashScope Key 均可通过环境变量覆盖，启动前需设置：

```bash
export AGENTSCOPE_API_KEY=<你的 API Key>   # 必填，模型调用凭据
export ES_URIS=http://127.0.0.1:9200      # 可选
export ES_USERNAME=<es 用户名>            # 可选
export ES_PASSWORD=<es 密码>              # 可选
export ES_API_KEY=<es api key>            # 可选，优先级高于用户名密码
export DASHSCOPE_API_KEY=<key>            # 可选，embedding.provider=dashscope 时使用
```

### 4. 启动服务

```bash
mvn spring-boot:run
```

服务默认监听 `8080`，启动时会把 `classpath:agentscope-workspace/` 下的 `AGENTS.md` 与 `subagents/` 释放到 `.agentscope/workspace`。

### 5. 访问接口文档

- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`

### 6. 调用示例

```bash
curl -N -X POST http://localhost:8080/tender/parse \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"userId":"u1","tenderText":"……招标文件正文……"}'

# 人员 + 企业资质并发匹配（tenderRequirementId 来自 /tender/parse/confirm）
curl -N -X POST http://localhost:8080/tender/match \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"userId":"u1","tenderRequirementId":1234567890}'
```

## Langfuse 链路追踪

项目通过 OpenTelemetry（OTLP/HTTP）把 Agent 执行链路上报到
[cloud.langfuse.com](https://cloud.langfuse.com)。默认关闭；关闭时不会创建
OpenTelemetry SDK，也不会引入 Reactor 全局 Hook。

### 启用方式

1. 在 Langfuse 控制台创建项目，拿到 `pk-lf-...` 与 `sk-lf-...`。
2. 启动服务前设置环境变量：

   ```bash
   export LANGFUSE_ENABLED=true
   export LANGFUSE_PUBLIC_KEY=pk-lf-xxxxxxxx
   export LANGFUSE_SECRET_KEY=sk-lf-xxxxxxxx

   # 可选
   export LANGFUSE_HOST=https://cloud.langfuse.com   # US: https://us.cloud.langfuse.com；JP: https://jp.cloud.langfuse.com
   export LANGFUSE_ENVIRONMENT=dev                   # 写入 deployment.environment.name
   export LANGFUSE_SAMPLE_RATE=1.0                   # 1.0 = 全量采集
   export LANGFUSE_CAPTURE_CONTENT=true              # 是否上报提示词和模型回复
   ```

3. 正常启动服务并调用任意 `/tender/*` 接口，打开 Langfuse 项目即可看到 trace。

`application.yml` 中对应的配置项：

| 配置 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `langfuse.enabled` | `LANGFUSE_ENABLED` | `false` | 是否启用上报 |
| `langfuse.host` | `LANGFUSE_HOST` | `https://cloud.langfuse.com` | Langfuse 服务地址，实际 OTLP 路径为 `/api/public/otel/v1/traces` |
| `langfuse.public-key` | `LANGFUSE_PUBLIC_KEY` | 空 | 项目公钥，启用时必填 |
| `langfuse.secret-key` | `LANGFUSE_SECRET_KEY` | 空 | 项目私钥，启用时必填 |
| `langfuse.service-name` | - | `tender-service` | 写入 `service.name` |
| `langfuse.environment` | `LANGFUSE_ENVIRONMENT` | `local` | 写入 `deployment.environment.name` |
| `langfuse.sample-rate` | `LANGFUSE_SAMPLE_RATE` | `1.0` | 采样率，范围 0~1 |
| `langfuse.capture-content` | `LANGFUSE_CAPTURE_CONTENT` | `true` | 是否上报提示词、模型回复与工具输入输出 |

### 上报内容

| Langfuse 视图 | 对应 AgentScope 事件 |
|---|---|
| `invoke_agent TenderAssistant` | 一次 Agent 调用，子 Agent 调用会作为子 span |
| `chat <model>` | 每次模型调用，含模型名、token 用量、提示词与回复（开启 `capture-content` 时） |
| `execute_tool <tool>` | 每次工具调用，含工具入参与返回文本（开启 `capture-content` 时） |
| User / Session | 来自请求的 `userId` 与 `RuntimeContext.sessionId` |

> 招标文件可能包含敏感信息。生产环境如果不希望把正文上传到 Langfuse，设置
> `LANGFUSE_CAPTURE_CONTENT=false`，此时仍会上报调用链、耗时、token 用量等元数据。

## 前端工作台

前端位于 `frontend/`，技术栈为 Vue 3 + TypeScript + Vite + Element Plus。

```bash
cd frontend
pnpm install
pnpm dev
```

开发服务器默认 `http://localhost:5173`，`/tender/` 与 `/knowledge/` 通过 Vite 代理转发到
`http://localhost:8080`。页面、脚本与接口约定见 [frontend/README.md](frontend/README.md)。

## 目录结构

```
src/main/java/org/example
├── controller/   # TenderController、MatchingController、KnowledgeBaseController
├── matching/     # 人员/企业资质匹配编排、SSE 事件转换与匹配模型
├── service/      # 解析事件转换、解析结果持久化、知识库、工作区初始化
├── repo/         # 仓储层（MyBatis-Plus + ES 检索）
├── mapper/       # MyBatis-Plus Mapper
├── model/        # 请求/响应、招标要求、目录、知识库等模型
├── config/       # Agent 装配、ES 客户端、知识库配置绑定
├── tools/        # Agent 可调用的工具（招标要求、企业数据）
├── filter/        # Web 访问日志
├── middleware/   # 模型调用审计
├── prompts/      # 提示词提供者
└── client/       # Embedding 客户端（ES Inference / DashScope）

src/main/db/                  # 建表脚本
src/main/resources/prompts/   # 提示词模板
src/main/resources/agentscope-workspace/  # Agent 工作区（AGENTS.md、子 Agent 声明）
.agentscope/workspace/        # 运行时工作区（会话、记忆）
docs/tender-demo/             # 详细设计文档
.opencode/skills/code-to-doc-generator/  # 代码逆向生成文档 Skill
```

## 文档生成 Skill：code-to-doc-generator

内置项目级 Skill（`.opencode/skills/code-to-doc-generator/`），从代码与运行产物逆向生成文档，自动加载（项目级优先级高于全局 `~/.config/opencode/skills/`）。

**四轴参数**：类型 × 范围 × 受众 × 叙事

| 轴 | 取值 |
|---|---|
| 类型 | brd-mrd、prd、hld、ddd、lld、coding-guide、test-doc、operations-manual、slo、cicd、admin-quick-reference |
| 范围 | module、subsystem、project、package |
| 受众 | dev、architect、business、operator |
| 叙事 | engineering（按实现路径）、review（业务主线 + 技术保障）、acceptance（按验收项） |

**用法**：只给类型与范围即可，受众与叙事按默认值推断并在参数卡中声明。

```
用 code-to-doc-generator 生成 LLD 详细设计，范围 project，受众 business
```

**两种模式与收尾校验**：

- 生成模式：输出参数卡 → 四轮工作流（项目基线 → 结构扫描 → 逐模块分析 → 骨架/填充）
- 反向同步模式：文档已存在但可能漂移，以代码为准校准（`references/reverse-sync.md`）
- 收尾：证据标注、脱敏、结构一致性由脚本机器校验，未通过不得汇报完成

```bash
python3 .opencode/skills/code-to-doc-generator/scripts/validate_doc.py \
  docs/tender-demo/详细设计-tender-service-v1.md \
  --root . --profile lld --audience business
```

退出码：`0` 通过、`1` 有错误、`2` 参数错误，加 `--json` 输出机器可读结果。入口说明见 [SKILL.md](.opencode/skills/code-to-doc-generator/SKILL.md) 与 [README.md](.opencode/skills/code-to-doc-generator/README.md)。

## 相关文档

- [前端工作台](frontend/README.md)
- [详细设计-tender-service](docs/tender-demo/详细设计-tender-service.md)
- [详细设计-tender-service-project-dev](docs/tender-demo/详细设计-tender-service-project-dev.md)
- [数据库 ER 图](docs/db/er-diagram.md)
