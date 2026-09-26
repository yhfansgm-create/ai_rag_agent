# fan-ai-agent —— 银行制度问答 RAG 助手

面向**银行内部制度问答**场景的 RAG 助手：文档入库 → 向量检索 → 权限/时效两层门禁
→ 生成答案 → 输出侧数字一致性校验。

它不是"接个大模型就完事"的 demo。这个项目真正在做的，是把**银行场景下不能出错的那几件事**
落到代码里：谁有权看哪份文件、制度过期了怎么按当时的版本作答、答案里的金额比例能不能在原文找到出处、
审计要留什么痕。设计取舍与踩坑记录见 [`docs/`](docs/)，代码里关键决策都写在类注释的"为什么"里。

---

## 一、快速开始

### 环境

| 组件 | 用途 | 说明 |
|---|---|---|
| **MySQL** `127.0.0.1:3306/fan_ai_agent` | 会话表 + 记忆表 | 建表见 `sql/*.sql` |
| **PostgreSQL**（需 pgvector） | 向量库 | 业务库与系统库分离。**扩展要先手工装**，见 `PROGRESS.md` |
| **DeepSeek API Key** | 对话模型 | 环境变量 `DEEPSEEK_API_KEY` |
| **硅基流动 API Key** | Embedding（`BAAI/bge-m3`，1024 维） | 环境变量或 `config/local-secrets.yml` |

机密一律走环境变量或 `config/local-secrets.yml`（已 gitignore，模板见 `config/local-secrets.example.yml`），
**代码与配置文件里没有明文密钥**。

> 换机器 / 重装前**务必先读 [`PROGRESS.md`](PROGRESS.md) 的「环境坑」一节** ——
> pgvector 扩展的安装权限、阿里云 PG 的库级 ACL 问题都在那里，不读会卡很久。

### 跑起来

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local    # 端口 8123，默认 profile 已是 local
```

⚠️ **路由没有 `/api` 前缀**（项目未配置 `server.servlet.context-path`）。
`docs/` 下部分文档写的 `/api/session/chat` 是过时写法，照它发请求会 404 —— 以 `kb.http` 为准。

```bash
# 建会话 → 拿到 sessionId + sessionToken（token 仅此一次明文返回）
curl -X POST http://localhost:8123/session -H 'Content-Type: application/json' -d '{"userId":"10086"}'

# 对话（带权限校验）
curl -X POST http://localhost:8123/session/chat -H 'Content-Type: application/json' \
     -d '{"sessionId":"...","sessionToken":"...","message":"大额取现要什么材料"}'

# 入库（手动触发，只扫 rag.kb.dir 这一层、不递归）
curl -X POST http://localhost:8123/kb/ingest

# Swagger UI
open http://localhost:8123/swagger-ui.html
```

### 跑测试

```bash
# 纯单测（不需要任何数据库/网络）
mvn test -Dtest='!*ContextTest,!*ChainTest,!RagAppTest,!LoveAppTest'

# 全量：538 个用例（需要 MySQL 在跑；RagAppTest 还需 PG + embedding）
mvn test
```

需要真基础设施的只有三类，用通配符排除比列举类名省事（这类测试还在增加）：
`*ContextTest`（DAO 的列映射/约束，要 MySQL）、`*ChainTest`（全链路打桩，要 MySQL）、
`RagAppTest`（要 PG + embedding 网络）。`LoveAppTest` 由 `DEEPSEEK_API_KEY` 守卫、
未配置时自跳过（全量里那 2 个 skipped 就是它）。

---

## 二、包结构地图

```
org.agent
├── YuAiAgentApplication          启动类（⚠️ 它的包位置是承重的：组件扫描 + MyBatis Mapper 发现的根）
├── api/                          应用层：HTTP 入口 + 对话门面
│   ├── BankAssistant             主链路门面，装配 advisor 链 —— 从它的类注释开始读
│   └── SessionController         路由：/session、/session/chat、/session/{id}/close
├── permission/                   权限内核（底座，不反向依赖任何业务包）
├── rag/
│   ├── TemporalGate              时效门禁（入库/检索共用，所以放根）
│   ├── ingest/                   文档 → 切分 → 版本收敛 → 写向量库
│   ├── retrieve/                 查询扩展 → 多路召回 → 融合 → 两层门禁 → 拼引用
│   ├── guard/                    答案里的数字回原文核验
│   ├── config/  web/             向量库装配 / 入库路由
├── memory/                       记忆层：L1 情景记忆（会话内最近 N 轮）+ L2 语义记忆（跨会话事实）
│   │                              + 会话摘要（滑出窗口的定位块）+ 哈希链审计写入
│   ├── model/ distill/ service/ config/ dao/   词表与读侧模型 / 两个 seam 接口与 LLM 实现 /
│   │                                            策略出口与事务边界 / executor 与配置 / 三张新表的 DAO
├── audit/                        通用哈希链（append-only 审计，**不依赖 memory**，供 RAG-04 #13 复用）
├── config/                       全局装配决定：**两套数据源谁做主**（业务库标 @Primary）
└── playground/                   学习与评测工具，不在主链路上
```

**依赖方向严格单向**：`api → {permission, rag, memory}`，`rag.* → permission`，
`memory → {permission, rag.retrieve, audit}`，`audit → 无`（纯函数，谁都不依赖），
`permission` 是底座。这是刻意维持的 —— 成环之后"谁能改谁"就没有答案了。
`audit` 之所以单独成包，就是为了让 RAG-04 #13（审计查询接口）能直接复用它，
而不用从记忆模块里把哈希链抠出来。

每个包都有 `package-info.java`，在 IDE 里点开包名就能看到该包**负责什么 / 不负责什么 /
被谁依赖 / 从哪个类开始读**，以及那些"看着位置奇怪其实有原因"的类为什么在那儿。

---

## 三、学习路径

排序原则：**跟着"一次请求的生命周期"往外扩**，不按目录字母序。

先读 `permission` 是刻意的 —— `rag` 和 `memory` 都依赖它。不先读它，
后面出现的 `accessFilter`、`SessionEnvelope`、`authVersion` 全是天书。

| # | 读什么 | 带着什么问题读 |
|---|---|---|
| 0 | [`PROGRESS.md`](PROGRESS.md) → [`docs/银行RAG-06`](docs/银行RAG-06-已实现功能导览.md) §一 | 三个数据源怎么配？一次对话经过几个 advisor？顺序由什么决定？ |
| 1 | `api/BankAssistant` 类注释 | 四个 advisor 的 order 各是多少？为什么鉴权在最外层、数字校验在最内层？ |
| 2 | `permission/`：`PermissionAdvisor` → `SessionAuthService` → `SessionEnvelope` | 三层校验是哪三层？`accessFilter` 长什么样、谁消费？为什么 fail-closed？ |
| 3 | `rag/retrieve/RetrievalAdvisor.decide()`，按其五步展开 | 为什么截断必须在两层门禁**之后**？拒答为什么不调模型？ |
| 4 | `rag/ingest/RagDocumentIngestService` 的两阶段 | 为什么任一文档不合法就整体中止？旧版本为什么不删、靠时效挡？ |
| 5 | `rag/guard/` | 它怎么拿到"出处池"？模型已经调完了，怎么还能拒答？ |
| 6 | `memory/MemoryAdvisor` 类注释（**最值得读的一段**） | 记忆层为什么 fail-open 而权限 fail-closed？`getOrder()` 为什么必须是默认值？ |
| 7 | `api/` 与各 advisor 的 context key | 三个 advisor 怎么传数据而不互相引用？谁生产、谁消费？ |
| 8 | `playground/RagApp` | 调 topK / 换 embedding 时怎么单独看召回质量（它不调 LLM） |

**验收自测**：读完第 6 层，试着回答"**为什么把 `MemoryAdvisor` 挪到 `RetrievalAdvisor`
后面是安全事故，而不是风格问题**"。答得出来，说明 advisor 的 order 机制真读懂了 ——
这是全项目最容易在 code review 里被误改的一处。

---

## 四、`package-info.java` 怎么读

**别当源码读 —— 它们是 Javadoc，要读渲染视图。**

文件里是 `{@link org.agent.permission}`、`<h2>`、`<ul>` 这类标记，当纯文本看会很吵；
而且 `{@link}` 在源码视图里显示的是一长串全限定名，**比不加链接还难认**。
渲染之后才是它本来的样子：`{@link}` 变成可点的超链接，`<h2>` 变成标题，`<ul>` 变成项目符号。

### 方式 1：IDE 里看（日常用这个，零配置）

IntelliJ IDEA：

- **光标停在类名/方法名上按 `Ctrl+Q`** —— 弹出渲染后的文档。看 `package-info`
  就打开该文件、光标停在 `package` 那行再按。
- 或者把鼠标**悬停**在类名上，也有渲染后的浮窗。

这条对**所有**类注释都适用，不止 `package-info` —— 本项目注释的信息量比代码大，
用 `Ctrl+Q` 读比在源码里滚注释高效得多。

### 方式 2：生成 HTML 站点（想整包通读、或想全局搜时）

```bash
bash scripts/gen-javadoc.sh
# 然后浏览器打开 target/site/apidocs/index.html
```

为什么不用 `mvn javadoc:javadoc`：`maven-javadoc-plugin` 本地仓库里没有，离线下载不到。
脚本改用「`maven-dependency-plugin` 导 classpath + JDK 自带 `javadoc`」两步，都能离线跑。

> 脚本里三个编码参数（`-encoding`/`-docencoding`/`-charset`）**不能省**，否则中文网页乱码。
> 命令行里 javadoc 自身的提示仍可能是 GBK 乱码（Windows 中文 locale 的控制台问题），
> 不影响生成的 HTML。

HTML 版的好处是**能全局搜**、能看到继承树和"被谁引用"；读单个类的注释仍推荐方式 1。

### 方式 3：纯文本入口（不想开工具时）

本文件（`README.md`）就是为此存在的 —— 纯 Markdown，没有标记噪音。
`package-info` 是**就地**的补充（读到哪个包看哪个包），不是必读项。

---

## 五、文档索引

`docs/` 下的文档**按状态分工**，别混着维护：

| 文档 | 状态 | 性质 |
|---|---|---|
| [银行RAG-01 需求地图](docs/银行RAG-01-需求地图.md) | 背景 | 需求从哪来 |
| [银行RAG-02 技术亮点](docs/银行RAG-02-技术亮点.md) | 背景 | 亮点清单 |
| [银行RAG-03 监管约束与技术选型](docs/银行RAG-03-监管约束与技术选型.md) | 背景 | 为什么这么选 |
| [银行RAG-04 待做功能优先级](docs/银行RAG-04-待做功能优先级.md) | **未做** | 会频繁变动，做完一个划掉一个 |
| [银行RAG-05 平台内核与场景特化](docs/银行RAG-05-平台内核与场景特化.md) | 背景 | 哪些能套用、哪些不能 |
| [银行RAG-06 已实现功能导览](docs/银行RAG-06-已实现功能导览.md) | **已做** | 复习提纲，只增不改 |
| [银行RAG-07 记忆分层与断点续传设计](docs/银行RAG-07-记忆分层与断点续传设计.md) | **已设计·大部分实现** | 实现完的章节迁去 06。已落地：L1（阶段 0/1）、L2（阶段 2）、会话摘要（§5.5）；未落地：阶段 3a/3b 断点续传、阶段 4 治理 |

---

## 六、已知的"注释与实现不一致"（读代码时留意）

这些是核实过的落差，保留在此以免被注释误导：

1. **`docs/` 与部分注释写的 `/api/...` 路由不存在**。项目未配置 `server.servlet.context-path`，
   实际路由是 `/session/chat`、`/kb/ingest`。已在相关类注释中标注，文档待改。
2. ~~**记忆层的权限过滤尚未实现**。~~ ✅ **已消解（阶段 1）**：原先 `MemoryAdvisor` 的类注释
   把对策写成"存指针不存内容 **+ 读取时按实况信封过滤**"，而后者当时还没做，两半只落地了一半。
   现在两半都落地了 —— 写入侧 `MemoryWriteGuard` 给每轮算 `clearance_level`，
   读取侧 `ChatMemoryService` 按**本轮实况信封**过滤，`MessageChatMemoryAdvisor` 造成的旁路已堵住。
   正反两个方向都有全链路测试锁着（`L1ClearanceChainTest` 拦住 / `L1ClearanceFilterOffChainTest` 复现漏洞）。
   仍敞开的边界：存的是**快照**，堵不住"引用的制度事后升密"，归阶段 4。详见 RAG-06 §九。
3. **`pom.xml` 里有若干未被引用的依赖**（`kryo`、`jsoup`、`itext-core`；
   ~~`jsonschema-generator`~~ ✅ **已被用上**：阶段 2 的 `LlmFactDistiller` / `LlmSummaryWriter`
   通过 `.call().entity(...)` 做结构化输出，走的就是它 —— 所以那条"未引用"的判断已过期）。
   其中 `itext` 在 `RagDocumentIngestService` 注释里标注为"计划支持 pdf"，属预留，未删。
4. ~~**`local` profile 下跑起来的应用演示不了记忆功能**~~ ✅ **已修**：`RagVectorStoreConfig`
   的 `ragVectorDataSource` 是个用户定义的 `DataSource` bean，会让 `DataSourceAutoConfiguration`
   整体退让 ⇒ `spring.datasource.*`（MySQL）被忽略、**MyBatis 绑到 PG**。之所以测试全绿也看不见，
   是因为每个上下文测试都设了 `rag.vectorstore.enabled=false` —— 测试与"跑起来的应用"不是同一套装配。
   修法：`org.agent.config.DataSourceConfig` 显式声明业务库并标 `@Primary`（MyBatis /
   `JdbcTemplate` / 事务管理器三个的判据都是 `@ConditionalOnSingleCandidate`，靠它选主），
   向量库那一侧的消费方写死 `@Qualifier("ragVectorDataSource")`。
   `DataSourceWiringTest` **离线**锁住这条装配：摘掉 `@Primary` 它立刻报
   `NoUniqueBeanDefinitionException`（已实测）。
