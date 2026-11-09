---
name: qualification-match-review
description: 诊断 qualification-matcher 子 Agent 的 Langfuse Trace，重点检查企业资质匹配中的强制性资质 UNMATCHED 风险、证书有效期判断、等级识别、日期格式转换问题，并输出结构化改进建议。当用户提供 qualification-matcher 的 trace JSON 并提到"资质匹配/资质诊断/资质复盘/资质分析"时触发。依赖 langfuse-trace-review 共享引擎。
---

# 资质匹配 Trace 诊断（业务规则包）

## 流程

1. 先调用 `langfuse-trace-review` 共享引擎：解析 JSON、还原时间线、执行 R1（格式）和 R4（性能）通用检查。
2. 加载 `references/qualification-rules.md`，对最终 GENERATION 及所有含 `entityType=qualificationMatch` 输出的 GENERATION 执行 R2/R3 业务检查。
3. 按共享引擎的 JSON schema 输出报告，`category` 仅可为 `FORMAT_VIOLATION | BUSINESS_RISK | DATA_QUALITY | PERFORMANCE`，`ruleViolated` 使用 `R2-*` / `R3-*` 编号。

## 必须检查的高危信号

- 强制性资质（建筑工程施工总承包、市政公用工程施工总承包、安全生产许可证）`status=UNMATCHED` 且无 `requiresManualReview` 字段 → HIGH
- `reason` 出现"企业不具备""企业未找到"等确定性表述 → MEDIUM
- `validUntil` 从数组格式（如 `[2027,6,30]`）被 LLM 手工转为字符串 → MEDIUM
- 等级从 `name` 解析错误（如"贰级"识别为"一级"）→ HIGH

## 约束

- 只诊断，不改代码。
- 业务规则不覆盖其他 Agent（人员匹配走 `personnel-match-review`）。
- 若 trace 中无 `entityType=qualificationMatch` 输出，报告 `warnings` 注明"未发现资质匹配输出，可能不是完整匹配任务"。