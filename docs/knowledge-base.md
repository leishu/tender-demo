# 知识库：向量检索 + 全文检索（Elasticsearch）

> 本文是知识库能力的历史设计与变更记录。项目级完整设计按读者分两份：[客户评审版](tender-demo/详细设计-tender-service-project-business.md)、[内部评审版](tender-demo/详细设计-tender-service-project-dev.md)；开发者向 LLD 保留在 [详细设计-tender-service.md](tender-demo/详细设计-tender-service.md)，旧版综合设计归档于 [详细设计-tender-service-v1.md](tender-demo/详细设计-tender-service-v1.md)。
>
> 阅读提示：早期文档中的接口示例（如 `POST /knowledge`）已被 `POST /knowledge/{knowledgeBaseName}` 取代；`pom.xml` 仍保留 `<skip>true</skip>` 且缺少 `spring-boot-maven-plugin`，构建阻断见内部评审版 P0-01；代码实际使用 `ik_max_word`/`ik_smart`，需安装 IK 插件，见内部评审版 2.3 节与第 13 章。

## 一、需求

> 我要实现向量检索+全文检索的简单的知识库功能，外接es。帮我完成service以及他访问的各类，输入是List<String>，把这些数据保存到es，然后再实现一个查询接口，返回最符合条件的10条数据

---

## 二、实现结果

基于项目现有的 Spring Boot 4 + WebFlux 技术栈，接入了 Elasticsearch 9.x + 阿里云 DashScope `text-embedding-v3`，实现了“向量 + 全文”混合检索的知识库。

### 实现方案

- **写入**：`List<String>` → 过滤空白 → 批量生成 embedding → Bulk 写入 ES（`_id` 用 UUID，重复写入天然幂等）
- **检索**：先分别执行 BM25 全文检索和 kNN 向量检索，各召回 50 条，再在 Java 侧用 **RRF（Reciprocal Rank Fusion）** 融合排序，取前 10 条
- **降级**：未配置 embedding key 或查询向量生成失败时，自动退化为纯全文检索
- **中文分词**：当前代码使用 `ik_max_word`（写入）/`ik_smart`（检索），部署时必须安装 IK 插件；早期版本曾使用 `standard tokenizer + cjk_bigram`，该描述已不适用（见详细设计 I-18）。

> 注意：ES 自带的 `rank.rrf` 需要商业 License，当前 Basic License 会报
> `current license is non-compliant for [Reciprocal Rank Fusion (RRF)]`，所以融合放在了应用侧。

### 新增/修改的文件

| 文件 | 说明 |
|---|---|
| `pom.xml` | 新增 `elasticsearch-java`、`elasticsearch-rest5-client` 9.3.3 |
| `src/main/resources/application.yml` | 新增 `knowledge-base` 配置段 |
| `config/KnowledgeBaseProperties.java` | ES / Embedding 配置绑定 |
| `config/ElasticsearchConfig.java` | ES 客户端（支持 Basic Auth / API Key） |
| `client/EmbeddingClient.java` | 向量化接口 |
| `client/DashScopeEmbeddingClient.java` | DashScope 实现（批量、document/query 两种 text_type） |
| `client/EmbeddingException.java` | 向量化异常 |
| `model/knowledge/KnowledgeDocument.java` | ES 文档模型 |
| `model/knowledge/KnowledgeSearchHit.java` | 检索结果模型 |
| `repo/KnowledgeBaseRepository.java` | 索引创建、Bulk 写入、双路检索、RRF 融合 |
| `service/KnowledgeBaseService.java` | 业务编排（向量化、保存、降级检索） |
| `controller/KnowledgeBaseController.java` | HTTP 接口 |

### 接口

#### 1. 批量保存（输入即 `List<String>`）

```bash
curl -X POST http://localhost:8080/knowledge \
  -H 'Content-Type: application/json' \
  -d '[
    "投标人须具有独立法人资格，具备有效的营业执照。",
    "投标人应具备建筑机电安装工程专业承包二级及以上资质。",
    "投标保证金金额为人民币五万元整。"
  ]'
```

返回按入参顺序生成的文档 ID：

```json
["52f883fd-669f-4615-95ba-0a23e758b8b8", "2a9496d9-...", "..."]
```

#### 2. 查询（默认返回最符合的 10 条）

```bash
curl -G http://localhost:8080/knowledge/search \
  --data-urlencode 'query=投标人需要具备什么资质' \
  --data-urlencode 'topK=10'
```

返回结果（`score` 为 RRF 融合分）：

```json
[
  {
    "id": "2a9496d9-d32b-4e89-905d-b586d6f09099",
    "content": "投标人应具备建筑机电安装工程专业承包二级及以上资质。",
    "createdAt": 1793063139439,
    "score": 0.03252247488101534
  }
]
```

### 配置

```yaml
knowledge-base:
  index-name: knowledge_base
  es:
    uris: ${ES_URIS:http://127.0.0.1:9200}
    username: ${ES_USERNAME:}
    password: ${ES_PASSWORD:}
    api-key: ${ES_API_KEY:}
  embedding:
    base-url: ${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com}
    api-key: "${DASHSCOPE_API_KEY:${agentscope.api-key:}}"
    model: text-embedding-v3
    dimension: 1024
    batch-size: 10
```

### 已验证

在本机 Elasticsearch 9.2.3（关闭 security）+ DashScope 下做了端到端测试：

| 查询 | Top1 结果 |
|---|---|
| 投标人需要具备什么资质 | 建筑机电安装工程专业承包二级及以上资质 ✅ |
| 项目经理的执业资格要求 | 机电工程专业一级建造师 ✅ |
| 保证金要交多少钱 | 投标保证金五万元整 ✅ |
| 投标文件什么时候递交 | 2026年11月20日9时30分 ✅ |

同时验证了：空白文本过滤、空查询返回 400、`topK` 上限 50、保存后立即能查到（Bulk 使用 `Refresh.WaitFor`）。

### 注意事项

1. **ES 版本**：客户端锁定 9.3.3，适配 ES 9.x；若外接的是 8.x 集群，需要把客户端换成 8.19.x 左右。
2. **向量维度**：`dimension` 必须与已有索引的 `dense_vector dims` 一致，修改后需删除索引重建（维度在首次访问时自动建索引）。
3. **无 embedding key**：系统仍可启动，但只做全文检索；已入库的文档不会补生成向量。
4. 测试产生的索引已删除、临时启动的应用和 ES 进程已停止，不会占用 8080/9200 端口。

---

## 三、变更记录

### v2 — 2026-10-27

#### 修改原因

1. **中文分词效果差**：最初使用 `standard tokenizer + cjk_bigram`，大量无意义噪声 bigram（如"证金"、"包二"）稀释 BM25 精度，专业术语（保证金、建造师、机电安装）被拆成碎片
2. **依赖外部 DashScope**：向量化强依赖阿里云 DashScope API Key，有网络延迟和调用费用，且 DashScope text-embedding-v3 的 1024 维向量对轻量场景偏高

#### 修改项

**1. 中文分词：从 cjk_bigram 切换到 IK Analyzer 插件**

- 安装 IK 9.2.3 插件：`elasticsearch-plugin install https://get.infini.cloud/elasticsearch/analysis-ik/9.2.3`
- `KnowledgeBaseRepository.createIndex()` 中移除自定义 `zh_text` analyzer（`standard + cjk_bigram`），改为直接使用 IK：
  - `analyzer("ik_max_word")` — 写入时细粒度切分，确保所有可能词元被索引
  - `searchAnalyzer("ik_smart")` — 查询时智能粗粒度切分，更精准匹配
- ES 服务器侧：新增 `config/jvm.options.d/heap.options` 限制 JVM 堆内存 `-Xms2g -Xmx2g`；`elasticsearch.yml` 关闭 security 并设置 `discovery.type: single-node`

| 方案 | `"投标保证金金额为人民币五万元整"` 分词结果 |
|---|---|
| cjk_bigram | 投标,标保,保证,证金,金金,金额,为人,民币,币五,五万,万元,元整（含噪声） |
| **ik_smart** | 投标,保证金,金额,为,人民币,五万元,整 ✅ |

**2. 向量化：从 DashScope 切换到 ES 内置 multilingual-e5-small**

- 新增 `client/EsInferenceEmbeddingClient.java`：通过 ES 9.x 自带的 `/_inference` API 调用内置 `multilingual-e5-small` 模型，完全本地化，零外部依赖
- 原有 `DashScopeEmbeddingClient` 保留，通过 `@ConditionalOnProperty(havingValue="dashscope")` 条件加载
- `KnowledgeBaseProperties.Embedding` 新增 `provider` 字段（默认 `"es"`）、`esEndpointId` 字段；`dimension` 默认值改为 **384**（multilingual-e5-small 输出维度）；`batchSize` 默认 100
- 删除 DashScope 专用的 `text_type=document/query` 逻辑（ES inference 不支持）

**切换方式**：在 `application.yml` 改一行即可，旧索引需删除重建（维度不同）：

```yaml
# 使用 ES 内置模型（默认，384 维，完全本地）
embedding:
  provider: es
  dimension: 384

# 切回 DashScope（1024 维，需 api-key）
embedding:
  provider: dashscope
  dimension: 1024
  api-key: "你的 DashScope key"
```

**3. Maven 编译修复**

- `pom.xml` 原 `maven-compiler-plugin` 配置 `<skip>true</skip>`，Lombok 注解处理器在 Maven 构建时不生效
- 改为显式配置 `<annotationProcessorPaths>`，包含 Lombok，Maven 和 IDEA 编译行为一致

**4. 降级机制调整**

- 原来 DashScope 依赖 `api-key` 是否存在判断 `isEnabled()`，无 key 时降级为纯全文检索
- 现在 ES 内置模型 `isEnabled()` 始终返回 `true`（只要 ES 可达），降级场景仅为 ES inference 调用异常
- 若 ES 未启用 Trial / License 不支持 inference，应用启动时会在 `EsInferenceEmbeddingClient` 构造阶段暴露问题

#### 修改文件清单

| 文件 | 变更类型 | 说明 |
|---|---|---|
| `client/EsInferenceEmbeddingClient.java` | 新增 | ES 内置 inference 向量化实现 |
| `client/DashScopeEmbeddingClient.java` | 修改 | 加 `@ConditionalOnProperty(havingValue="dashscope")` 条件加载 |
| `config/KnowledgeBaseProperties.java` | 修改 | `Embedding` 加 `provider`、`esEndpointId`；`dimension` 默认 384；`batchSize` 默认 100 |
| `repo/KnowledgeBaseRepository.java` | 修改 | `createIndex()` 移除 cjk_bigram 自定义 analyzer，改用 `ik_max_word` / `ik_smart` |
| `src/main/resources/application.yml` | 修改 | `embedding` 段加 `provider: es`、`es-endpoint-id`；`dimension: 384`；`batch-size: 100` |
| `pom.xml` | 修改 | `maven-compiler-plugin` 开启编译并配置 Lombok annotationProcessorPaths |
| ES 服务器 `/config/elasticsearch.yml` | 新增 | `discovery.type: single-node`、`xpack.security.enabled: false` |
| ES 服务器 `/config/jvm.options.d/heap.options` | 新增 | `-Xms2g -Xmx2g` |
| ES 服务器 `/plugins/analysis-ik/` | 新增 | IK 9.2.3 插件 |

#### 注意事项

1. **ES Trial License**：ES 9.x 的 inference API（内置 embedding）需要 **Platinum License**。Basic License 调用会返回 `current license is non-compliant for [inference]`。开发环境可通过 `POST /_license/start_trial?acknowledge=true` 激活 30 天 Trial。
2. **multilingual-e5-small 模型加载**：首次调用 inference 时 ES 会从 `ml-models.elastic.co` 下载约 412MB 的模型包并初始化，后续调用秒级响应。
3. **向量维度变更**：从 DashScope 1024 维切换到 ES 384 维后，必须删除旧索引 `DELETE /knowledge_base`，下次应用启动时会用新维度自动重建。
4. **IK 词典扩展**：默认 IK 词典已覆盖投标场景专业术语，如需扩展可编辑 ES 服务器 `config/analysis-ik/IKAnalyzer.cfg.xml` 配置远程/本地词典。

---

### v3 — 2026-10-27

#### 修改原因

1. **缺少"知识库"分组概念**：所有文档写入同一个 ES 索引，无法按业务维度（如"招标文件库"、"资质库"、"业绩库"）隔离管理
2. **缺少元数据管理**：没有表记录知识库的名称、文档数量、embedding 模型等信息，列表展示、按组检索都无法实现

#### 设计决策

- **ES 索引方案**：所有文档仍在同一个索引里，新增 `knowledgeBaseId`（long）字段做 `terms` filter 分组（方案 A），避免多索引管理复杂度
- **元数据存储**：新增 MySQL 表 `t_knowledge_base`，主键自增（`BIGINT AUTO_INCREMENT`）
- **检索方式**：不传 `knowledgeBaseName` 即跨所有库；可重复传参指定多个库做并集检索

#### 修改项

**1. 新增 MySQL 元数据表**

```sql
-- src/main/db/knowledge_base.sql
CREATE TABLE t_knowledge_base (
    id                    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键自增ID',
    name                  VARCHAR(128)  NOT NULL                COMMENT '知识库名称，业务分组标识',
    description           VARCHAR(512)  NULL                    COMMENT '知识库描述',
    document_count        BIGINT        NOT NULL DEFAULT 0      COMMENT '文档总数',
    embedding_dimension   INT           NULL                    COMMENT '向量维度',
    embedding_enabled     TINYINT(1)    NOT NULL DEFAULT 1      COMMENT '是否启用向量化',
    embedding_model       VARCHAR(64)   NULL                    COMMENT 'embedding 提供者/模型',
    created_at            BIGINT        NOT NULL                COMMENT '创建时间(epoch ms)',
    updated_at            BIGINT        NOT NULL                COMMENT '更新时间(epoch ms)',
    deleted               TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '软删除标记',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库元数据';
```

**2. ES 索引 mapping 扩展**

`KnowledgeBaseRepository.createIndex()` 新增 `knowledgeBaseId`（`long`）字段。已建索引需删除重建：`DELETE /knowledge_base`。

**3. 写入流程变化**

- `saveAll(String knowledgeBaseName, List<String> contents)` 新增 `knowledgeBaseName` 参数
- 知识库不存在时自动创建 `t_knowledge_base` 记录，存在则复用
- ES 文档新增 `knowledgeBaseId` 字段关联 MySQL 主键
- 写入完成后更新 `document_count` 和 `updated_at`
- 返回值从 `List<String>`（文档 UUID 列表）改为 `Long`（知识库自增主键）

**4. 检索流程变化**

- `search(List<String> knowledgeBaseNames, String query, int topK)`：
  - `knowledgeBaseNames` 不传 → 跨所有库检索
  - 传单个 → 限定一个库
  - 传多个 → 指定库的并集检索（`terms` filter）
  - 有名称不存在时抛出 `IllegalArgumentException`
- 全文检索：`bool.filter(terms) + match`
- 向量检索：`knn.filter(terms)`（ES kNN 原生支持 filter）

#### 接口变化

| 操作 | v2 | v3 |
|---|---|---|
| 保存 | `POST /knowledge` + body `["文本1","文本2"]` → `["uuid1","uuid2"]` | `POST /knowledge/{knowledgeBaseName}` + body → `42`（知识库主键） |
| 检索 | `GET /knowledge/search?query=...` | `GET /knowledge/search?knowledgeBaseName=a&knowledgeBaseName=b&query=...` |

**调用示例**

```bash
# 在 "tender-docs" 知识库中写入
curl -X POST http://localhost:8080/knowledge/tender-docs \
  -H 'Content-Type: application/json' \
  -d '["投标人须具有独立法人资格。", "保证金五万元整。"]'
# 返回: 1

# 在 "company-qualifications" 知识库中写入
curl -X POST http://localhost:8080/knowledge/company-qualifications \
  -H 'Content-Type: application/json' \
  -d '["市政公用工程施工总承包一级资质。"]'
# 返回: 2

# 跨所有知识库检索
curl -G http://localhost:8080/knowledge/search \
  --data-urlencode 'query=投标人资质'

# 只在 tender-docs 库检索
curl -G http://localhost:8080/knowledge/search \
  --data-urlencode 'knowledgeBaseName=tender-docs' \
  --data-urlencode 'query=投标人资质'

# 跨 tender-docs + company-qualifications 两个库检索
curl -G http://localhost:8080/knowledge/search \
  --data-urlencode 'knowledgeBaseName=tender-docs' \
  --data-urlencode 'knowledgeBaseName=company-qualifications' \
  --data-urlencode 'query=投标人资质'
```

#### 修改文件清单

| 文件 | 变更类型 | 说明 |
|---|---|---|
| `model/knowledge/KnowledgeBase.java` | **新增** | MySQL 实体，自增主键 |
| `mapper/KnowledgeBaseMapper.java` | **新增** | MyBatis-Plus BaseMapper |
| `src/main/db/knowledge_base.sql` | **新增** | 建表 SQL |
| `model/knowledge/KnowledgeDocument.java` | 修改 | 新增 `knowledgeBaseId` 字段 |
| `repo/KnowledgeBaseRepository.java` | 修改 | `createIndex()` 加 `knowledgeBaseId` mapping；`search` 从单个 `Long` 改为 `List<Long>`，ES filter 从 `term` 改为 `terms` |
| `service/KnowledgeBaseService.java` | 修改 | `saveAll` 加 `knowledgeBaseName` 参数、自动查/建知识库、返回值改 `Long`；`search` 加 `List<String> knowledgeBaseNames` 参数 |
| `controller/KnowledgeBaseController.java` | 修改 | POST 改为路径参数 `/{knowledgeBaseName}`；search 加多值 `knowledgeBaseName` query 参数 |

#### 注意事项

1. **ES 索引必须重建**：旧索引没有 `knowledgeBaseId` mapping，且旧文档该字段为 `null`。执行 `DELETE /knowledge_base` 后应用启动会自动重建。若需保留旧数据，可先手动创建一个知识库记录，再用 `_update_by_query` 给旧文档补上 `knowledgeBaseId`。
2. **`saveAll` 使用 `@Transactional`**：ES 写入和 MySQL 更新在一个事务里——MySQL 失败则整体回滚，ES 是外部系统无法回滚，但 MySQL 的 `document_count` 不会被错误累加。
3. **知识库名称唯一**：`UNIQUE KEY uk_name (name, deleted)` 保证软删除后可以重建同名知识库。
4. **跨库查询传空列表**：`knowledgeBaseNames` 传空列表 `[]` 等同于不传，都是跨所有库。Service 层会判断 `null` 和空列表两种情况。