# 通用诊断方法（方法层规则）
版本：v1.1
适用范围：所有 Agent 的 Langfuse Trace 通用检查（R1 格式、R4 性能）+ 报告 schema。
---

## R1 输出格式合规（FORMAT_VIOLATION）

**判定对象**：所有 `type=GENERATION` 且 `output` 非 null 的节点。重点检查时间线最后一个 GENERATION（最终输出）。output 为 null 的 GENERATION 不在本检查范围，按引擎 SKILL.md 的"null output 处理"约束判定（先排除工具调用轮）。

**检查顺序（先解析，后正则）**：先尝试把 output 按行（或整体）做 `JSON.parse`。若全部解析成功，跳过下表正则检查——避免 JSON 字符串值内合法出现的"首先""汇总"等词被误判为解释性文字。仅当解析失败、或存在 JSON 之外的内容时，才对相应内容执行下表正则。

**违规模式（满足任一即违规）**：

| 模式 | 正则/特征 | severity 基准 |
|---|---|---|
| Markdown 加粗标题 | `\*\*[^*]+\*\*` 出现在行首 | MEDIUM（最终输出 CRITICAL） |
| Markdown 标题 | `^#{1,6}\s` | MEDIUM（最终输出 CRITICAL） |
| Markdown 表格 | `\|\s*-{3,}\s*\|` | MEDIUM（最终输出 CRITICAL） |
| 代码围栏 | ` ``` ` | MEDIUM（最终输出 CRITICAL） |
| 解释性文字 | "现在我已"、"让我"、"首先"、"接下来"、"关键发现"、"汇总"、"建议主 Agent" | MEDIUM（最终输出 CRITICAL） |
| 数组外壳 | output 以 `[{` 开头且含多个 `"entityType"` | MEDIUM |
| 跨角色对话 | "主 Agent"、"用户"、"下一步请" | MEDIUM |

**排除项**：如果 output 本身就是一段纯文本（如 AGENT 的总结），且该 Agent 的系统提示词未要求 JSON 输出，则不判违规。判断依据：该 GENERATION 的 `input` 中 SYSTEM 消息是否含"只输出 JSON"类硬约束。

**归因建议模板**：
1. 在系统提示词末尾追加强约束："输出任何非 JSON 字符（Markdown 标记、解释文字、表格）将导致下游解析失败，任务判定为失败。"
2. 应用层加 `JSON.parse(output)` 校验，失败带错误信息重试一次，仍失败降级为逐条输出。
3. 若多次重试仍违规，把"N 条结果一次生成"拆成"每条结果一次 Generation"。

---

## R4 性能（PERFORMANCE）

**判定对象**：所有 GENERATION 节点 + 整体 trace。

| 指标 | 阈值 | severity | 建议 |
|---|---|---|---|
| 单 GENERATION latency / totalLatency | > 50% | LOW | 评估拆分输出或换非推理模型 |
| reasoning token 占比 `output_reasoning_tokens / (output_tokens + output_reasoning_tokens)` | > 0.3 且 `output_tokens ≥ 200` | LOW | 推理占比偏高，评估简化提示词或压缩 thinking 预算 |
| 第 2+ 次 GENERATION `input_cached_tokens = 0` 且总输入 > 1000 | 命中 | LOW | 缓存未生效，检查缓存配置 |
| totalLatency | > 60s | MEDIUM | 整体过慢，检查是否有串行可并行节点 |

**口径说明（避免歧义）**：
- `output_tokens` 取 `usageDetails.output`（**不含** reasoning），分母为 `output + output_reasoning_tokens`。不要用 metadata 里已含 reasoning 的 `attributes.gen_ai.usage.output_tokens` 做分母，否则会低估占比。
- `output_tokens < 200` 的轮次（如只产生一次工具调用的小决策轮）跳过 reasoning 占比检查，避免噪音；这类轮次的小耗时可在报告中一笔带过，不单独立 finding。
- 缓存检查的"总输入" = `usageDetails.input + usageDetails.input_cached_tokens`（`input` 字段通常只是非缓存部分）。

---

## 报告 JSON Schema

```json
{
  "traceId": "string",
  "agentName": "string",
  "sessionId": "string",
  "totalLatencySeconds": 0.0,
  "businessRulesLoaded": true,
  "warnings": ["string"],
  "timeline": [
    {
      "observationId": "string",
      "type": "AGENT|GENERATION|TOOL|SPAN|EVENT",
      "name": "string",
      "latencySeconds": 0.0,
      "latencyPct": 0.0,
      "summary": "一句话说明该节点做了什么"
    }
  ],
  "findings": [
    {
      "severity": "CRITICAL|HIGH|MEDIUM|LOW",
      "category": "FORMAT_VIOLATION|BUSINESS_RISK|DATA_QUALITY|PERFORMANCE",
      "observationId": "string",
      "evidence": "原文片段（≤200字符）",
      "ruleViolated": "R1/R2/R3/R4 + 规则包内编号",
      "suggestion": "具体到改哪个文件、哪段提示词、加什么校验"
    }
  ],
  "positiveFindings": ["string"],
  "nextActions": ["按优先级排序，最多 5 条"]
}
```

**字段约束**：
- `findings` 按 severity 降序排列（CRITICAL → LOW）。
- `evidence` 必须是 trace 中真实出现的原文，不得改写。
- `timeline` 必须覆盖全部 observations，不得遗漏。
- `warnings` 用于记录"业务规则包未加载""字段缺失但可继续"等非违规提示。

---

## Markdown 报告模板（仅当用户明确要求时使用）

```markdown
# Trace 诊断报告：{traceId}

**Agent**：{agentName}  **Session**：{sessionId}  **总耗时**：{totalLatencySeconds}s

## 时间线

| 顺序 | observationId | 类型 | 名称 | 耗时 | 占比 | 说明 |
|---|---|---|---|---|---|---|

## 发现的问题

### [{severity}] {category} — {ruleViolated}
- **节点**：{observationId}
- **证据**：`{evidence}`
- **建议**：{suggestion}

## 做对的地方
- ...

## 下一步动作
1. ...
```

---

## 规则迭代记录

| 日期 | 变更 | 触发来源 |
|---|---|---|
| 2026-11-06 | 初始版本：R1 格式、R4 性能、报告 schema | session-1793949849919 |
| 2026-11-09 | v1.1：R1 改为先 JSON.parse 后正则；R4 明确 reasoning 占比分母（output+reasoning）并加 output_tokens≥200 门槛、缓存检查改用总输入量；null output 的工具调用轮豁免见引擎 SKILL.md | trace ebe7faab5efce14377c805d1b899f799 复盘 |