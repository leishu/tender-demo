# code-to-doc-generator（项目本地版）

本目录是 `code-to-doc-generator` 的项目级覆盖版本。OpenCode 按 ID 选择 skill，项目级 `.opencode/skills/` 的优先级高于全局 `~/.config/opencode/skills/`，因此在本项目中会加载这一版，全局版本保持不动。

## 与全局版（v1）的差异

| 维度 | 全局版 | 本版 |
|---|---|---|
| 组织轴 | 文档类型 | 类型 × 范围 × 受众 × 叙事 |
| 章节来源 | `document-types.md` 内的固定模板 | `composition-matrix.md` 的矩阵 |
| 章节顺序 | 模板固定 | `narrative` 决定（`engineering` / `review` / `acceptance`） |
| 受众 | 写死在类型定义里 | `audience-profiles.md` 的 4 档渲染配置 |
| 证据 | 两套标注规范 | `evidence.md` 单一事实源，三级闭合枚举 |
| 质量门禁 | 定性检查表 | `scripts/validate_doc.py` + 人工复核 |
| 类型覆盖 | 11 种（K 缺模板） | 11 种，每种一个文件 |
| 章节命名 | `document-types.md` 混排 | `references/types/<id>.md` |

## 目录

```
.opencode/skills/code-to-doc-generator/
├── SKILL.md                     主控：模式、参数、四轮、收尾
├── README.md                    本文件（人类阅读，不加载）
├── references/
│   ├── composition-matrix.md    类型 × 范围 → 章节；narrative → 顺序
│   ├── audience-profiles.md     受众 → 渲染档位
│   ├── evidence.md              标注与锚点
│   ├── exploration-strategy.md  代码探索
│   ├── reverse-sync.md          反向同步规程
│   └── types/                   11 种类型定义
├── dimensions/
│   └── operations-manual.md     运营手册七维度
└── scripts/
    └── validate_doc.py          文档校验
```

## 使用

在项目里直接说：

```
用 code-to-doc-generator 生成 LLD 详细设计，范围 project，受众 business
```

或只给类型和范围，受众与叙事按默认值推断（会在参数卡里声明）。

叙事可显式指定，覆盖推断值：

```
用 code-to-doc-generator 生成 LLD，范围 project，受众 business，叙事 review
```

### 叙事取值

| narrative | 适用 | 组织方式 |
|---|---|---|
| `engineering` | 开发 / 架构 / 运维接手 | 按实现路径：是什么 → 怎么搭 → 数据 → 接口 → 异常 → 问题 |
| `review` | 业务评审 / 交付验收 | 两段式：业务主线 → 技术保障 → 附录；技术章压缩 |
| `acceptance` | UAT / 合同交付 | 按验收项逐条成节，给验证方法与不通过后果 |

默认规则：`business → review`，其余 `→ engineering`。详见 `references/composition-matrix.md` 第六节。

---

## v2.1 变更（相对 v2.0）

针对 tender-demo 双版本对照实验暴露的四个缺口，仅改规则文件，不改源码。

| # | 文件 | 变更 | 解决的问题 |
|---|---|---|---|
| 1 | `references/audience-profiles.md` | `business` 增加两条硬规则：正文不引用证据编号；未测量指标必须给验收后果 | 客户版正文出现 20 处 `见 E-xx`，业务读者被指向附录 |
| 2 | `references/evidence.md` | 正文引用规则按受众分叉：`dev`/`architect`/`operator` 可写 `见 E-01`，`business` 只写章节交叉引用 | 原规则对所有受众统一要求"见 E-01"，对客户版是错的 |
| 3 | `references/composition-matrix.md` | 新增第六节「叙事变体」：三个取值、三条不变量、`lld × project × review` 两段式 14 章骨架、第 9/10 章合计 ≤15% 压缩规则 | 受众只管措辞不管叙事，客户版只能是"删了类名的内部版" |
| 4 | `references/composition-matrix.md` | `package` 模式强制 `00-口径对照.md`，给模板与 P0 ↔ R-xx 映射规则 | 两版编号体系不通用，结论一致性靠人脑 |
| 5 | `references/types/lld.md` | 新增「内部评审版附加规则」：待裁决项单列、正文 OBSERVE 回指 P 编号、重复表只定义一次、统计口径自证 | P0 混入待裁决、正文与第 11 章无映射、附录重复表 |
| 6 | `SKILL.md` | 参数卡增加 `narrative`；Step 1 骨架阶段同时确定叙事；硬规则新增第 8 条 | 叙事维度未进入主流程 |
| 7 | `scripts/validate_doc.py` | 新增 `evidence-ref-body`（business 正文引 E 编号 → error）、`p0-pending`（P0 行含"待裁决" → warn）；`density` 改为统计正文锚点 | 规则只能靠人眼守 |

### 升级影响

- 已有 `business` 文档若正文含 `见 E-xx`，升级后校验报 error，需按 `narrative=review` 重新生成或改写为章节引用。
- 已有内部版若 P0 表含"待裁决"，升级后报 warn，需拆出「待裁决清单」。
- `engineering` 文档不受影响。

## 兼容性

- 旧的 `〔FACT｜文件:行号〕` 与 `〔FACT｜文件:行号-行号〕` 仍被接受。
- 用户显式给出的章节列表优先于参数矩阵。
- 全局版的文件未被修改；如需回退，删除本目录即可。

## 校验脚本

```bash
python3 .opencode/skills/code-to-doc-generator/scripts/validate_doc.py \
  docs/tender-demo/详细设计-tender-service-v1.md \
  --root . --profile lld --audience business
```

退出码：`0` 通过，`1` 有错误，`2` 参数错误。加 `--json` 输出机器可读结果。
