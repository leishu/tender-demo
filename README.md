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

## 主要能力

- **招标文件解析**：`POST /tender/parse`，以 SSE 实时推送 project / qualification / performance / personnel / scoringItem 等结构化事件。
- **解析结果入库**：`POST /tender/parse/confirm`，主表 + 资质/业绩/人员/评分点四张子表持久化。
- **投标目录生成**：`POST /tender/catalog`，输出最多三级的目录 JSON，支持商务/技术分列。
- **章节撰写**：`POST /tender/chapter`，结合评分点生成章节正文。
- **知识库**：`POST /knowledge/{knowledgeBaseName}` 批量写入并向量化，`GET /knowledge/search` 混合检索。
- **Agent 编排**：主 Agent 调度 `tender-parser`、`catalog-builder`、`chapter-writer` 子 Agent，规则见工作区 `AGENTS.md`。

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
```

## 目录结构

```
src/main/java/org/example
├── controller/   # TenderController、KnowledgeBaseController
├── service/      # 解析事件转换、解析结果持久化、知识库、工作区初始化
├── repo/         # 仓储层（MyBatis-Plus + ES 检索）
├── mapper/       # MyBatis-Plus Mapper
├── model/        # 请求/响应、招标要求、目录、知识库等模型
├── config/       # Agent 装配、ES 客户端、知识库配置绑定
├── tools/        # Agent 可调用的工具（招标要求、企业数据）
├── middleware/   # 模型调用审计
├── prompts/      # 提示词提供者
└── client/       # Embedding 客户端（ES Inference / DashScope）

src/main/db/                  # 建表脚本
src/main/resources/prompts/   # 提示词模板
src/main/resources/agentscope-workspace/  # Agent 工作区（AGENTS.md、子 Agent 声明）
.agentscope/workspace/        # 运行时工作区（会话、记忆）
docs/tender-demo/             # 详细设计文档
```

## 相关文档

- [详细设计-tender-service](docs/tender-demo/详细设计-tender-service.md)
- [详细设计-tender-service-project-dev](docs/tender-demo/详细设计-tender-service-project-dev.md)
- [数据库 ER 图](docs/db/er-diagram.md)
