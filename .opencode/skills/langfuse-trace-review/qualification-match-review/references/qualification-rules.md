# qualification-matcher 业务规则库

版本：v1.1
基线：session-1793949849919-qualification（traceId b13f3bcaf17c276170ef32b7bceebb45）

---

## R2 业务风险（BUSINESS_RISK）

### R2-1 强制性资质 UNMATCHED（HIGH）

**判定**：`requiredType` 属于以下强制性资格条件之一，且 `status=UNMATCHED`，且输出中**没有**结构化人工复核标记：

强制性资质清单（来自招标文件第二章 2.1 / 2.2）：
- 建筑工程施工总承包（一级及以上）
- 市政公用工程施工总承包（二级及以上）
- 安全生产许可证
- ISO9001 / ISO14001 / ISO45001（三证，投标截止时有效）

**结构化标记要求**：output 中该结果对象须含 `requiresManualReview: true` 或 `risk: "mandatory_qualification_missing"`。仅靠解释文字（"建议核实"）不算。

**证据示例**：`{"requirementId":8,"requiredType":"建筑工程施工总承包","status":"UNMATCHED","candidates":[]}` 且无 `requiresManualReview` 字段。

**建议**：在输出 schema 中为 UNMATCHED 结果强制添加 `requiresManualReview: true`，并在应用层将该字段映射为 UI 高危告警。

### R2-2 措辞确定性风险（MEDIUM）

**判定**：`reason` 含"企业不具备"、"企业未找到"、"未找到类型为…的资质"等确定性表述，但证据仅来自一次工具查询（无二次核实）。

**建议**：统一改为"企业资质库中未查询到该类型资质，需人工核实企业是否实际持有"。

### R2-3 等级识别错误（HIGH）

**判定**：output 中 `candidates[].level` 与 `name` 中的等级字样不一致。等级映射：壹级=一级，贰级=二级，叁级=三级。

**证据示例**：`name="建筑机电安装工程专业承包贰级"` 但 `level="一级"`。

### R2-4 有效期判断错误（HIGH）

**判定**：`validUntil` 早于"当前日期"，但 output 中 `expired=false` 或 `eligible=true`。

**当前日期提取**：优先从 input 的 `## 当前日期` 段提取；该段缺失或格式变化时，回退用 trace 最早 `startTime` 的日期（UTC 转本地日期），并在报告 `warnings` 中注明使用了回退基准。

**证据示例**：`validUntil="2025-03-31"`，当前日期 `2026-11-06`，但 `expired=false`。

---

## R3 数据质量（DATA_QUALITY）

### R3-1 LLM 手工日期转换（MEDIUM）

**判定**：工具返回的 `validUntil` 为数组格式（如 `[2027,6,30]`），而 output 中为字符串 `"2027-06-30"`。说明 LLM 承担了日期格式化。

**建议**：在 `query_qualifications` 工具层统一输出 ISO 字符串，LLM 不做格式转换。

### R3-2 等级字段缺失（LOW）

**判定**：`name` 含等级字样（壹级/贰级/叁级/一级/二级/三级/甲级/乙级），但 output 中 `level=null`。

**建议**：在输出 schema 中从 `name` 正则提取等级，或要求工具层直接返回 `level` 字段。

### R3-3 类型误匹配（HIGH）

**判定**：`requiredType` 与 `candidates[].type` 语义不一致，但 `status=MATCHED`。

**已知易错对**：
- "建筑工程施工总承包" ≠ "建筑机电安装工程专业承包"（施工总承包 vs 专业承包）
- "建筑工程施工总承包" ≠ "建筑装修装饰工程专业承包"
- "市政公用工程施工总承包" ≠ "市政公用工程专业承包"

---

## 已知的"做对了"模式（positiveFindings 候选）

- 按类型查不到时改查全部资质（降级策略正确）
- 正确区分"专业承包"与"施工总承包"，未把"建筑机电安装工程专业承包"误匹配为"建筑工程施工总承包"
- 有效期判断与"当前日期"基准一致
- 并行发起多个 `query_qualifications` 调用，非串行等待
- 输出每条结果都含 `requirementId` 且与输入 id 一一对应

---

## 规则迭代记录

| 日期 | 新增规则 | 触发来源 |
|---|---|---|
| 2026-11-06 | R2-1~R2-4、R3-1~R3-3 初始版本 | session-1793949849919 |
| 2026-11-09 | v1.1：R2-4 当前日期提取增加 trace startTime 回退基准 | trace ebe7faab5efce14377c805d1b899f799 复盘 |