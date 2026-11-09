---
name: personnel-match-review
description: 诊断 personnel-matcher 子 Agent 的 Langfuse Trace，重点检查关键人员（项目经理、技术负责人、安全负责人、造价负责人）的证书齐全性、证书有效期、B/C 证、职称等级、经验年限、6 个月社保记录，并输出结构化改进建议。当用户提供 personnel-matcher 的 trace JSON 并提到"人员匹配/人员诊断/人员复盘/关键人员分析"时触发。依赖 langfuse-trace-review 共享引擎。
---

# 人员匹配 Trace 诊断（业务规则包）

## 流程

1. 先调用 `langfuse-trace-review` 共享引擎：解析 JSON、还原时间线、执行 R1（格式）和 R4（性能）通用检查。
2. 加载 `references/personnel-rules.md`，对最终 GENERATION 及所有含 `entityType=personnelMatch` 输出的 GENERATION 执行 R2/R3 业务检查。
3. 按共享引擎的 JSON schema 输出报告，`ruleViolated` 使用 `R2-P*` / `R3-P*` 编号。

## 必须检查的高危信号

- 项目经理缺一级建造师证或 B 证，或证书过期 → HIGH
- 技术负责人职称低于高级工程师，或经验 < 10 年 → HIGH
- 安全负责人缺注册安全工程师 / C 证，或经验 < 5 年 → HIGH
- 造价负责人缺一级造价工程师 → MEDIUM
- 任一核心人员社保记录 < 6 个月 → HIGH
- 候选人证书编号格式异常（如建造师证号位数不符）→ LOW

## 约束

- 只诊断，不改代码。
- 业务规则不覆盖资质匹配（走 `qualification-match-review`）。
- 若 trace 中无 `entityType=personnelMatch` 输出，报告 `warnings` 注明"未发现人员匹配输出，可能不是完整匹配任务"。
- **人员信息属敏感数据**：报告中 `evidence` 字段对身份证号、手机号做脱敏（保留前 3 后 4），不得输出完整证件号。
```