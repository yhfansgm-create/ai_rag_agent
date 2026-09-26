# 项目进度

> 更新于 2026-09-26。设计背景见 `docs/`，关键决策与踩坑原因已写进相关代码注释。

## 代码结构（2026-09-25 重整，纯结构调整、无行为变更）

包结构按职责重排，依赖方向改为**严格单向**，并补上了代码地图：

- 应用层从 `permission` 拆出为 **`org.agent.api`**（`BankAssistant` + `SessionController` + 对话 DTO）
  —— 原先把门面和 Controller 放在 `permission` 里，造成了 `permission ↔ rag`、
  `permission ↔ memory` 两个**循环依赖**，拆开后成环问题消失
- `org.agent.rag` 由 20 个类平铺拆为 **`ingest/` · `retrieve/` · `guard/` · `config/` · `web/`**；
  `TemporalGate` 因入库/检索共用，留在 `rag` 根
- `org.agent.App` → **`org.agent.playground`**（包名大写违反 Java 约定，且与生产代码混在一起）
- 删除 **`org.agent.advisor`**（`MyLoggerAdvisor` / `ReReadingAdvisor` 全项目零引用的教程残留）
- 新增 **`org.agent.support`**（合并 3 份逐字相同的 `answerText` + 2 份 `str`）
- 每个包新增 `package-info.java` 作为代码地图；根目录新增 `README.md` 含**学习路径**
- 测试包同步镜像主包结构（包级私有成员被单测访问，包位置必须对齐）

**2026-09-26 记忆阶段 2 新增的包**（同样是单向依赖，`audit` 不依赖 `memory`）：

- **`org.agent.audit`** —— 通用哈希链（`AuditHashChain`）。**刻意不依赖记忆模块**，
  因为 RAG-04 #13 要复用它；`package-info.java` 里写了这个理由
- `org.agent.memory` 新增 `model/`（词表与读侧模型）· `distill/`（两个 seam 接口 + LLM 实现）·
  `service/`（策略出口与事务边界）· `config/`（executor 与两个 Properties）；
  `dao/` 里补了三张新表的 Row/DAO
- **`org.agent.config`**（2026-09-26 修数据源装配时新增，与记忆阶段无关）—— 全局装配决定，
  目前只有 `DataSourceConfig`：**两套数据源谁做主**。修的是「向量库那个 PG 抢走唯一的
  `DataSource`、业务表被写到 PG 上」的 bug，见下面那一节

**验证**：重构前后跑同一命令，均为 `Tests run: 196, Failures: 0, Errors: 0`。
命令：`mvn test -Dtest='!PermissionModuleContextTest,!ChatMessageDaoContextTest,!RagAppTest,!LoveAppTest'`

> ⚠️ 若从旧版本升级，注意**类名全部未变、只是包名变了**，但 `docs/银行RAG-06`、`07` 里引用的
> `类名.java:行号` 已失效。

## 状态：RAG 入库链路已打通

「文档 → 切分 → 向量化 → 写入 PGVector」端到端跑通。
**检索链路（`RetrievalAdvisor`）尚未用真实数据验证。**

## 状态：记忆功能 L1（阶段 0 + 阶段 1 已落地）

> L2 与会话摘要在下一节；本节只讲 L1 这一层。

**现在的记忆 = 「这个会话里最近 3 轮说过什么」，仅此一项**，再往外的都没做。

**能做到**：

- 同一 `session_id` 内多轮能接上（最近 3 轮注入 prompt；第 4 轮还看得到第 1 轮，**第 5 轮时第 1 轮滑出**）。
  **历史本身换会话即忘** —— 会话级；跨会话的那一层是 L2 的事实（下一节），两者不互相替代
- 拒答轮**落库**（审计要留痕）但**不进 prompt**；不这么做会把拒答文案喂回模型、强化它的拒答倾向。
  代价：「拒答后紧接着问『那它呢』」会失去指代对象
- 权限降级后读不到旧轮的高密级内容（阶段 1）。关掉 `rag.memory.clearance-filter-enabled` 可**复现漏洞**，
  正反两个方向都有全链路打桩测试锁着
- 历史里只有**答案 + 引文指针**，没有检索原文 —— 「用记忆洗密级」这条通道被堵死
- 记忆读写失败一律降级成「无历史」，不影响本次回答（记忆是体验层，不是权限层）

**没做到**（按归属阶段）：

- ~~**L2 语义记忆 / 跨会话记忆**（阶段 2）~~ → **已落地，见下一节**（本节的滑窗仍是 L1 唯一的行为）
- ~~语义召回、摘要压缩~~ → **摘要已落地（见下一节）；语义召回仍不做**（RAG-07 §七的既有决策）
- 指代消解（RAG-04 #8）：摘要只做到「定位」，**按指针回原文取**要在检索层闭环，仍未做
- **资料「事后升密」堵不住**（阶段 4）：记忆里存的是**快照**，堵得住「用户被降级」，堵不住「引用的制度被升密」
- 用户可查/可删 + 坏记忆巡检（阶段 4）。~~`memory_change_log` 哈希链~~ → 已落地（`org.agent.audit.AuditHashChain`）
- 到期清理：`expires_at`（默认 30 天）写了，但**没有任何任务去删/归档**
- 并发同一 session 两次 `chat()` 会撞 `turn_no` 唯一键 → 被失败隔离吞掉 = **静默丢一轮**（阶段 3b 幂等键）
- 流式写回未实测（主链路只走 `.call()`）

> ⚠️ **一个很容易读错的地方**：`MemoryAdvisor` 把历史写进了 `context`（`CONTEXT_HISTORY_TURNS`），
> 但**全项目没有任何地方读这个键**，`QueryExpander.expand(query)` 至今只接收当前 query 一个入参。
> 所以**指代消解（RAG-04 #8）并没有实现** —— 第 2 轮检索用的仍然是「那它呢」这三个字。
> 阶段 0 做完的是它的**前置**（数据到位、消费方缺席），不要再写成「已解锁」。
> 判断依据：`grep -rn CONTEXT_HISTORY_TURNS src/main/java/` 只有定义与写入两处。

## 状态：记忆功能 L2 + 会话摘要（阶段 2 + §5.5 已落地）

**这一层补的是 L1 的两块空白**：换会话就忘（L2 存**跨会话的稳定事实**）、第 5 轮时第 1 轮整段消失
（摘要把滑出窗口的那一段压成**定位信息**继续留在上下文里）。

> **起之前先手工建表**（无 Flyway，house style）：
> `mysql -u root -p fan_ai_agent < sql/user_memory.sql` / `sql/memory_change_log.sql` / `sql/session_summary.sql`。
> 三份 DDL 的文件头都写了「为什么这么设计」，文末留**注释形式**的升级 ALTER（照 `sql/chat_message.sql` 惯例）。

**能做到**：

- **跨会话的用户画像**：用户说「我主要做对公业务」⇒ 落 `user_memory`，**换一个会话照样注入**。
  目前四个键全部可自动写：`business_line`（365 天）/ `preferred_org`（180 天）/
  `focus_topic`（90 天，唯一允许带 `ref_doc_id`）/ `preferred_product`（180 天）
- **三道闸**（本层的卖点，通用记忆方案 MemGPT/Mem0/Zep 都没有权限概念，抄不到）：
  1. **无源不写** —— 每条记忆指回具体某轮的 L1 行（`source_session_id` + `source_turn_no`）
  2. **模型只能复述、不能推测** —— 候选事实的要素必须能在**该轮用户原话**里找到支撑，
     验证在 Java 侧（`FactGrounding`），且**真的能失败**（见下面的「让模型说谎」）
  3. **密级继承内容** —— `clearance_level` 取该轮资料密级（**不重算、不做 min**），
     读取时再按**本轮实况信封**过滤一遍
- **双时态**：`valid_to` 答「历史上它何时为真」（被取代时写），`expires_at` 答「到什么时候还能不问就信」
  （写入时按 TTL 预算）。到期**不删**、只是不再召回
- **三种写入形态**：无有效行 → INSERT；同值 → **REFRESH**（续期，不改 `source_*`）；
  异值 → SUPERSEDE（旧行标 `valid_to` + `superseded_by`，审计留两行）
- **会话摘要**：窗口滑出的轮次压成一句定位摘要，注入时显式声明「**不是事实来源**」；
  摘要**版本化**（append-only + supersede），所以事后能复现「当时模型看到的是哪一版」
- **哈希链审计**：每次记忆变更进 `memory_change_log`，`org.agent.audit.AuditHashChain` 提供
  `hash` / `verify`。**建在通用包**（不依赖记忆模块）是为 RAG-04 #13 预备 —— 那份待做项的实现已经就位，只差消费方

**没做到**（按归属阶段）：

- **语义召回**（embedding / 相似度）：**明确不做**，RAG-07 §七 的既有决策。单用户几百条，结构化 SQL 足够
- **摘要丢掉的数字无法自动补回**：用户问「刚才那个利率是多少」时摘要只能说「聊过利率」，
  要模型**重新检索**才拿得到。A1 的「数字按指针回原文取」要在**检索层**闭环，依赖 RAG-04 #8 指代消解。
  **这是摘要这个取舍的固有代价，不是实现疏漏** —— 讲的时候要说成取舍
- 可查/可删接口、坏记忆巡检三项、到期清理 job：阶段 4。钩子已具名预留
  （`recallOwnedBy` / `softDeleteByUser`），`ref_doc_id`、`last_hit_at` 也已开始写，那时不用回填
- **DEPT scope 无自动写入路径**（RAG-07 把它写成「需人工确认」，与两档决策冲突）：读分支实现了并有集成测试，
  晋升机制归阶段 4
- 任务级断点续传（`rag_task_run` / `rag_task_step`）：阶段 3a/3b，与**会话级**摘要无关，别混
- 其他缺口（共 14 条）逐条列在计划文件与 RAG-06 里；最该记住的两条：
  简短回答被拒（助手问「对公还是对私」、用户答「对公」缺自述信号 ⇒ **少记**，保守方向的损失，接受）；
  异步队列在进程重启时丢任务，无重放

**两处对计划的偏离**（实现时改的，都记在这儿）：

1. **数值闸门落在 `SessionSummaryService`，不是只落在 `LlmSummaryWriter`**。
   只写在 LLM 实现里的话，它就只是**某个实现的属性** —— 换个实现、或像链路测试那样把实现打桩掉，
   「摘要不许夹带事实性数字」这条约束就消失了。放在策略层，它才是契约，打桩也照样验得动
2. **链路测试的用户隔离用「按方法换会话」而不是「子类覆写 `currentUser()`」**。
   继承来的三个 L1 用例要用默认用户那套实况（密级 4 的资料只有 10087 读得到），
   整类换成密级 2 的柜员会让它们被 `RetrievalAdvisor.passesGate` 拦成拒答、模型根本不被调用。
   于是 `L1ChainScaffold.openSessionAs(String)` 做成按方法调用
3. **RAG-07 §5.5 的 token 规则降级成「只观测」**（越线打 WARN，不触发再压缩）。
   原因：它压的是**已经滑出去的**内容，压完体积不会变小，接成触发器只会换来一个
   **每轮都可能触发的 LLM 调用**。真正生效的触发只有「按轮次滑出窗口」这一条 ——
   已在 RAG-07 §5.5 与 RAG-06 §十一 写明，`MemoryAdvisor.warnIfOverBudget` 的注释里也有

**怎么证明这两道闸不是装饰**：**让模型说谎/不听话**，然后用一个开关把闸门关掉，看漏洞回不回来。
四类测试两两配对（都在 `src/test/java/org/agent/memory/`）：

| | 闸门开着 | 闸门关掉（漏洞现场） |
|---|---|---|
| **L2 复述验证** | `L2SemanticMemoryChainTest`：同一个撒谎的蒸馏 stub，**零行** | `L2GroundingOffChainTest`：同一场景，**落库并被当成用户自述注入下一轮** |
| **摘要数值闸门** | `SummaryChainTest`：合规摘要落库、注入 | `SummaryContainsNumberChainTest`：夹带金额的摘要**整份丢弃** |

关键是前两列用的**是同一个 stub**（模型恒定声称抽到了一条事实）—— 于是「写没写」只可能由 Java 侧的验证决定，
而不是「模型这轮碰巧没提」。只写前半条证明的是「这一轮恰好没写」；两条合起来才排除了所有其它解释。
这个手法与阶段 1 的 `L1ClearanceFilterOffChainTest` 逐字对应：**能关掉一个防护，才证明那个防护真的在起作用**。

## 环境（local profile）

| 组件 | 位置 |
|---|---|
| 会话表 | MySQL `127.0.0.1:3306/fan_ai_agent` |
| 向量库 | 阿里云 RDS PG `pgm-bp1r1p7m7d9vm33mdo...:5432/rag_kb`（账号 `fanyh_new`） |
| Embedding | 硅基流动 `BAAI/bge-m3`，1024 维 |

入库入口：`POST http://localhost:8123/kb/ingest`（**无末尾斜杠**，手动触发，不会自动跑）。
扫描目录由 `rag.kb.dir` 决定（**只扫该层，不递归**）。local 当前指向 `./kb-test-data/valid`
（16 篇覆盖各密级/切块/代码识别/时效的样例）；跑真实知识库时改这一行即可。

## 已修的 bug：`local` profile 下 MyBatis/业务表绑到了 PostgreSQL（2026-09-26 修）

**这不是本阶段引入的，是一直在的**，但本阶段让它的后果变得具体，所以记在这儿。
下面「现象 / 证据 / 为什么测试全绿」是**当时**的排查记录，保留着；修法与修完的实测在最后一段。

- **现象**：`rag.vectorstore.enabled=true`（local 的默认值）时，全上下文里唯一的 `DataSource` bean
  是 `RagVectorStoreConfig.ragVectorDataSource`（PG）。Spring Boot 的 `DataSourceAutoConfiguration`
  见到用户自定义的 `DataSource` 就**退让**，于是 `spring.datasource.*`（MySQL `fan_ai_agent`，也就是
  会话表/记忆表所在的那个库）**被完全忽略**。
- **证据**：实际启动探针打出 `PROBE-DS=[ragVectorDataSource]`、`PROBE-URL=jdbc:postgresql://…/rag_kb`。
  代码依据就是 `RagVectorStoreConfig` 里那个 `@Bean DataSource ragVectorDataSource(...)`。
- **影响**：`chat_message` / `user_memory` / `session_summary` / `memory_change_log` 都建在 MySQL 里，
  而跑起来的应用会去 PG 读写它们 ⇒ **修好之前，`local` profile 起的应用演示不了记忆功能（L1 也一样）**。
- **为什么测试全绿**：每个起 Spring 上下文的测试都设了 `rag.vectorstore.enabled=false`（全链路测试的原话是这样），
  于是它们拿到的是 MySQL —— 测试与「跑起来的应用」走的**根本不是同一套装配**。这个差异本身就是这个 bug 藏得深的原因。
- **附带要改的一处注释**：`ChatMessageDaoContextTest` 里写「`RagVectorStoreConfig` 里的 PG 连接是内联
  `new JdbcTemplate(...)`、并没有暴露成 bean，所以自动配置照常为 `spring.datasource` 配一个」——
  那是**照着推断写的**，探针证明推断错了（它确实是个 bean）。修 bug 时顺手改掉。
- **修法（本阶段不动）**：给 PG 那份加 `@Qualifier` + `@Primary` 区分，或按原注释以为的样子改回内联 `JdbcTemplate`。
  **它牵动向量库装配，是独立的一件事**，不该混进记忆层。

**2026-09-26 修完**，走的是上面第一条（`@Primary` 区分），三处改动：

1. 新增 **`org.agent.config.DataSourceConfig`**：显式声明业务库（MySQL）并标 `@Primary`。
   必须显式声明的原因就是上面那条 —— 自动配置已经整体退让，没人替 `spring.datasource.*` 兜底了。
   MyBatis 自动配置、`JdbcTemplateAutoConfiguration`、`DataSourceTransactionManagerAutoConfiguration`
   三个的判据都是 `@ConditionalOnSingleCandidate(DataSource.class)`，多个候选时「唯一候选」正是靠
   `@Primary` 选出来的。
2. `RagVectorStoreConfig.ragVectorStore` 的 PG 参数补 **`@Qualifier("ragVectorDataSource")`** ——
   这一处**不是风格问题**：Spring 解析依赖时「按类型 + `@Primary`」优先于「按参数名匹配」，
   少了限定符，向量就会被写进业务库（同一个 bug 反着犯一遍）。
3. 改掉 `ChatMessageDaoContextTest` 里那段**照推断写、且推断错了**的注释（见上面「附带要改的一处注释」）。

**新增 `DataSourceWiringTest`（离线，不连任何库）**：用 `AnnotationConfigApplicationContext` +
mock 出一个假的 PG 数据源，断言「两个 `DataSource` 同时在时，按类型注入解析到 MySQL」。
Hikari 的池是**第一次 `getConnection()` 才启动**，所以它不需要 MySQL/PG/密钥就能跑 ——
否则它会像其它上下文测试一样被「需要真实环境」劝退，而那些测试**恰恰看不见这个 bug**。
**已实测它真的会响**：临时摘掉 `@Primary` 后，
`NoUniqueBeanDefinitionException: expected single matching bean but found 2: ragVectorDataSource,dataSource`。

**修完的实测（`local` profile 真跑，2026-09-26 23:01）**：

| 步骤 | 观察到的结果 |
|---|---|
| 起服务 | 2.14s 启动，PG 连接池 `HikariPool-1` 正常，全程只有 1 条 WARN（还是我自己手抖发的非 UTF-8 请求） |
| 会话 1 说「我主要做对公业务…」 | `chat_message` 两行**落在 MySQL**（修之前会写进 PG） |
| 异步蒸馏 | 日志：`记忆审计链追加：entity=FACT action=WRITE factUid=8eb5fad4… hash=3ea3bc32…` |
| 查 `user_memory` | `business_line = 对公业务`，`clearance_level=2`（继承该轮资料密级）、`expires_at=2027-09-26`（TTL 365 天）、`source_session_id/source_turn_no` 指回会话 1 第 1 轮 |
| **换一个会话**问「开户需要哪些材料？」 | 该行 `last_hit_at` 由 NULL 变成时间戳 ⇒ **事实被召回并注入新会话的 prompt**（这个字段只在真的注入时更新） |
| 同一会话聊到第 4 轮 | `session_summary` 落一行：`covers 1..2 / source_turn_count=1 / clearance_level=2`，摘要正文**不含金额比例期限**（数值闸门没放走） |
| 审计链 | `memory_change_log` 第 99 行 `hash` = 第 100 行 `prev_hash`，链是接上的 |

## 环境坑（换机器 / 重装必看）

1. **扩展要先手工装**。Spring AI 启动时会依次执行
   `CREATE EXTENSION vector / hstore / "uuid-ossp"`。
   `vector` 是 trusted 扩展，普通账号能装；**`hstore`、`uuid-ossp` 必须用 rds_superuser 账号装**，
   否则应用启动直接失败。

2. **`fanyh_new` 没有库级 CREATE**。而 `CREATE SCHEMA IF NOT EXISTS "public"` 即使 schema 已存在，
   PostgreSQL 也会**先**做库级 ACL 检查 → 必然 `permission denied for database`。
   阿里云控制台授不到这个权限（账号管理只到「读写」）。
   **解法：用该账号自带的 `CREATEDB` 属性自建库 `rag_kb`** —— 创建者即 owner，权限自足。

3. **`initializeSchema` 必须显式设 `true`**（`rag/config/RagVectorStoreConfig`）。
   Spring AI 1.0.0 经 builder 构建时该值为 `false`，不设会静默跳过建表，
   日志只有一行 `Skipping the schema initialization for the table`，共排查成本很高。

## 已修的 bug（均无测试覆盖，见待办）

- `rag/ingest/RagDocumentIngestService`：chunk id 原是 `docId@version#n`，但 PGVector 的 id 列是 UUID
  → 改为 `UUID.nameUUIDFromBytes` 派生确定性 UUID（保持重复入库幂等）
- `rag/ingest/DocMetadataParser.dateOf`：SnakeYAML 把不加引号的日期解析成 `java.util.Date`，
  `String.valueOf` 后 `LocalDate.parse` 必崩 → 增加 Date 分支

## 工具

已装 **CodeGraph**（`@colbymchenry/codegraph`，MIT，索引在 `.codegraph/`，已 gitignore）。
它是全局安装 + MCP 注册在 `~/.claude.json`，**`npm i -g` 那步安装器不会自动做**，
换机器要手动补，否则 MCP 启动报 ENOENT。装了它之后读代码走 `codegraph_explore`，比 grep+Read 省 token。

## 待办

- [ ] 补单测：① `Document.getId()` 须能被 `UUID.fromString()` 解析
      ② front-matter 日期加引号 / 不加引号都能解析出同一个 `LocalDate`
- [ ] 用真实数据验证检索链路（权限过滤 + 代码脱敏）
- [ ] **RAG-04 #8 指代消解**（当前前置最少的一项）：把已就位的 `CONTEXT_HISTORY_TURNS`
      接进 `QueryExpander` / `RetrievalAdvisor` 的取参路径。见上面「记忆功能」那节的边界说明
- [x] ~~修 `ragVectorDataSource` 抢占 `DataSource` 的 bug~~ → **2026-09-26 已修**，
      见上面「已修的 bug」那一节（含 `DataSourceWiringTest` 与真机实测记录）
- [ ] 将 `kb-test-data/` 的样例全量跑一遍，排查还有没有同类「类型假设」错误
- [ ] 生产化：PG 密码 / API Key 改环境变量注入；业务库与系统库分离已做（`rag_kb`）
