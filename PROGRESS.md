# 项目进度

> 更新于 2026-09-25。设计背景见 `docs/`，关键决策与踩坑原因已写进相关代码注释。

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

**验证**：重构前后跑同一命令，均为 `Tests run: 196, Failures: 0, Errors: 0`。
命令：`mvn test -Dtest='!PermissionModuleContextTest,!ChatMessageDaoContextTest,!RagAppTest,!LoveAppTest'`

> ⚠️ 若从旧版本升级，注意**类名全部未变、只是包名变了**，但 `docs/银行RAG-06`、`07` 里引用的
> `类名.java:行号` 已失效。

## 状态：RAG 入库链路已打通

「文档 → 切分 → 向量化 → 写入 PGVector」端到端跑通。
**检索链路（`RetrievalAdvisor`）尚未用真实数据验证。**

## 环境（local profile）

| 组件 | 位置 |
|---|---|
| 会话表 | MySQL `127.0.0.1:3306/fan_ai_agent` |
| 向量库 | 阿里云 RDS PG `pgm-bp1r1p7m7d9vm33mdo...:5432/rag_kb`（账号 `fanyh_new`） |
| Embedding | 硅基流动 `BAAI/bge-m3`，1024 维 |

入库入口：`POST http://localhost:8123/kb/ingest`（**无末尾斜杠**，手动触发，不会自动跑）。
扫描目录由 `rag.kb.dir` 决定（**只扫该层，不递归**）。local 当前指向 `./kb-test-data/valid`
（16 篇覆盖各密级/切块/代码识别/时效的样例）；跑真实知识库时改这一行即可。

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
- [ ] 将 `kb-test-data/` 的样例全量跑一遍，排查还有没有同类「类型假设」错误
- [ ] 生产化：PG 密码 / API Key 改环境变量注入；业务库与系统库分离已做（`rag_kb`）
