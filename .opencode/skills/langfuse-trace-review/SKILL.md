---
name: langfuse-trace-review
description: 诊断 Langfuse Trace JSON 文件的通用引擎，负责解析结构、还原执行时间线、检查输出格式合规性（R1）和性能瓶颈（R4），并根据 agentName 动态加载对应业务规则包。当用户提供 Langfuse 导出的 trace JSON 文件（含 observations / scores 数组）并要求"分析/诊断/复盘/看问题/给改进建议"时触发。不要主动调用 Langfuse API 或 CLI 拉数据，只分析用户提供的文件。
---

# Langfuse Trace 诊断引擎（方法层）

本 Skill 是所有业务 Trace 诊断的共享底座。它只负责**通用方法**：解析结构、还原时间线、检查格式合规与性能、输出统一报告。**业务语义判定不在这里做**，而是根据 `agentName` 动态加载对应业务规则包。

## 输入

用户提供的 Langfuse Trace JSON，顶层结构：

```json
{
  "observations": [
    {
      "id": "...",
      "traceId": "...",
      "parentObservationId": "",
      "type": "AGENT | GENERATION | TOOL | SPAN | EVENT",
      "name": "...",
      "startTime": "ISO8601",
      "endTime": "ISO8601",
      "latency": 0.0,
      "input": "...",
      "output": "...",
      "model": "...",
      "usageDetails": {},
      "providedUsageDetails": {},
      "metadata": { "attributes.gen_ai.agent.name": "...", ... }
    }
  ],
  "scores": [ { "name": "...", "value": 0.0, "observationId": "..." } ]
}
```

**不要调用 Langfuse API/CLI**。如果文件结构不符合上述格式，先列出缺失字段，再决定是否继续（缺 `observations` 直接终止；缺 `scores` 不影响分析）。

## 执行流程

### 第 1 步：解析与时间线还原

1. 校验 `observations` 非空，否则报错终止。
2. 按 `startTime` 升序排序所有 observation。
3. 计算 `totalLatency = max(endTime) - min(startTime)`（秒）。
4. 对每个节点计算 `latencyPct = latency / totalLatency * 100`。
5. 识别顶层 AGENT（`parentObservationId` 为空且 `type=AGENT`）。
6. 用 `parentObservationId` 校验 GENERATION/TOOL 是否挂在正确父节点下；发现孤儿节点（父 id 不存在）时在报告中标记 `DATA_QUALITY`。

### 第 2 步：通用检查（R1 + R4）

读取 `references/diagnostic-method.md`，对所有 `type=GENERATION` 的节点执行 R1 和 R4 检查。**AGENT 节点的 `output` 为 null 属正常，不判格式违规。**

### 第 3 步：加载业务规则包

根据 `metadata["attributes.gen_ai.agent.name"]`（缺失时回退到 `metadata["attributes.gen_ai.agent.id"]` 或顶层 AGENT 的 `name` 解析出的 agent 名）选择规则包：

| agentName | 规则包路径 |
|---|---|
| `qualification-matcher` | `../qualification-match-review/references/qualification-rules.md` |
| `personnel-matcher` | `../personnel-match-review/references/personnel-rules.md` |
| `tender-parser` | `../tender-parser-review/references/tender-parser-rules.md` |
| `catalog-builder` | `../catalog-builder-review/references/catalog-builder-rules.md` |
| `chapter-writer` | `../chapter-writer-review/references/chapter-writer-rules.md` |
| 其他/未识别 | 不加载，报告中注明 `"businessRulesLoaded": false` |

规则包不存在时，只输出 R1/R4 结果，并在报告 `warnings` 中说明。

### 第 4 步：输出报告

严格按 `references/diagnostic-method.md` 中的 JSON schema 输出。**不要在 JSON 前后加解释文字，不要用 Markdown 围栏包裹整个 JSON**（保证下游可直接 `JSON.parse`）。如果用户明确要求 Markdown 格式，才改用 `diagnostic-method.md` 里附带的 Markdown 模板。

## 约束

- **只诊断，不改代码**。不修改任何文件，只输出建议。
- **证据优先**：每条 finding 必须含 `observationId` + `evidence`（原文片段，≤200 字符）。无证据的 finding 不输出。
- **区分模型问题与工程问题**：格式违规优先归因为"提示词约束不够硬 / 缺少输出校验 / 缺少重试"，不是"模型能力不足"。
- **不臆测未出现的字段**：trace 里没有 `scores` 就不在报告里提 score 相关结论。
- **null output 处理**：AGENT 或 SPAN 节点 output 为 null 不判违规；GENERATION output 为 null 判 `CRITICAL`（模型调用未返回内容）。
- **severity 映射**：最终 GENERATION（时间线最后一个）格式违规 → `CRITICAL`；中间 GENERATION 格式违规 → `MEDIUM`；业务规则包定义的 HIGH/CRITICAL 按规则包执行。

## 自查清单（输出前逐项确认）

- [ ] 时间线是否覆盖全部 observations，latencyPct 合计接近 100%？
- [ ] 每条 finding 是否都有 observationId 和原文证据？
- [ ] 是否区分了 FORMAT_VIOLATION / BUSINESS_RISK / DATA_QUALITY / PERFORMANCE？
- [ ] 最终 GENERATION 是否被单独检查？
- [ ] 建议是否指明了具体修改位置（文件/提示词段落/校验层），而非"优化提示词"？
- [ ] 业务规则包是否正确加载，未加载时是否在 warnings 说明？
- [ ] 是否至少列出 1 条 positiveFindings（若确实没有，写空数组并说明原因）？