# 代码探索策略

核心原则：**不逐行读，先找高信息密度位置，再定点精读。**

> 工具名以当前 harness 为准。下文用"列目录""读文件""搜索"描述能力，不绑定具体函数名；脚本片段需按环境调整后再执行。

---

## 一、项目类型识别

列根目录（不递归），按文件特征判断：

| 发现 | 类型 | 后续重点 |
|---|---|---|
| `package.json` + `src/components` | Web 前端 | 路由、页面、i18n |
| `pom.xml` / `go.mod` / `Cargo.toml` / `*.csproj` | 后端服务 | 路由、控制器、数据模型 |
| `package.json` + `server.js` / `app.js` | Node 后端 | 路由注册 |
| `requirements.txt` + `manage.py` | Django | `urls.py`、`views.py`、`models.py` |
| `*.xcodeproj` / `AndroidManifest.xml` / `pubspec.yaml` | 移动端 | 导航、字符串资源、ViewModel |
| `*.sln` + `*.xaml` / `CMakeLists.txt` | 桌面 | 菜单、资源文件、ViewModel |
| 多种并存 | 多端 | 分端提取，按用户路径合并 |

**多模块后端**（Maven/Gradle 多 module）：先读根 `pom.xml` 的 `<modules>`，再逐个模块读；不要把多模块当成多端。

---

## 二、高信息密度位置

### 后端服务

```
必读：
├── 路由/控制器注册         → 接口清单 = 功能骨架
├── 中间件/过滤器/拦截器     → 权限、全局行为、审计
├── 数据模型/ORM 实体/DDL   → 字段、约束、索引
├── 业务逻辑层              → 规则、状态机、公式
├── 配置与默认值            → 可配置项、超时、限额
└── 错误码/异常处理         → 失败边界

选读：
├── 数据库迁移              → 字段演进
├── 测试文件                → 实际行为与覆盖缺口
├── 构建脚本                → 是否能构建、跳过哪些检查
└── 部署配置                → 依赖、端口、健康检查
```

### 前端

```
必读：路由表、i18n、状态管理、API 封装、权限常量
低价值：样式、构建配置、第三方库
```

### 桌面 / 移动端

```
必读：菜单或导航定义、字符串资源、ViewModel、权限声明
注意：桌面端常无 i18n，字符串散落在资源文件
```

---

## 三、脚本辅助（>50 文件时）

片段仅示意，执行前确认本机 `grep` / `find` 行为。

### 接口清单

```bash
# Spring
grep -rn --include='*.java' -E '@(Request|Get|Post|Put|Delete|Patch)Mapping' .

# Go
grep -rn --include='*.go' -E '(GET|POST|PUT|DELETE|PATCH)\(' . | grep -v _test.go

# Node
grep -rn --include='*.js' --include='*.ts' -E '(app|router)\.(get|post|put|delete|patch)\(' .
```

### 中文字符串

```bash
grep -rn --include='*.json' --include='*.ts' -P '[\x{4e00}-\x{9fff}]' . | head -100
```

`grep -P` 不可用时改用 `rg '[\p{Han}]'`。

### 目录规模

```bash
find . -type f -name '*.java' -not -path '*/target/*' | sed 's|/[^/]*$||' | sort | uniq -c | sort -rn | head -20
```

### 数据模型字段

```bash
grep -rn --include='*.java' -E 'private [A-Za-z<>]+ [a-zA-Z]+;' . | grep -v test
```

---

## 四、看不懂时

按顺序：

1. 读注释与文档注释
2. 搜调用方，看上下文
3. 读测试，测试通常描述行为
4. 看枚举在分支里的用法
5. 看迁移文件与提交历史（若有）
6. 仍不懂 → 标 `〔INFER〕` 或 `〔OBSERVE｜缺口｜低｜待裁决〕`，不猜

---

## 五、运行时验证（可选但推荐）

静态阅读会漏掉运行时行为（序列化差异、流式协议、实际错误码）。条件允许时：

1. 启动服务，调一个最小请求
2. 记录实际响应结构与状态码
3. 把结果作为 `〔FACT〕` 的补充证据，锚点写"运行验证 + 日期"

无法启动时不强求，在收尾汇报中说明"未做运行时验证"。

---

## 六、基线采集

生成前必须记录：

```bash
git rev-parse --short HEAD    # Git
git branch --show-current
```

非 Git 项目：记录关键文件哈希或日期快照，例如

```bash
sha256sum pom.xml | cut -c1-8
```

该值写入文档头与每个 `FACT` 锚点，作为行号失效后的重定位依据。
