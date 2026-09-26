# 银行 RAG —— 记忆分层与任务断点续传 · 设计

> **本文是"设计方案"，不是排期表，也不是已实现导览。** 三份文档按**状态**分工：
>
> | 文档 | 状态 | 性质 |
> |---|---|---|
> | RAG-04 待做功能优先级 | 未做 · 待排期 | 决策记录，会频繁变动 |
> | RAG-06 已实现功能导览 | 已做 | 复习提纲，只增不改 |
> | **RAG-07（本文）** | **已设计 · 部分实现**（L1 阶段 0/1 已落地，见下） | 方案定型后，**实现完的章节迁去 RAG-06，并从 RAG-04 表里划掉** |
>
> 本文回答三个问题：**记忆怎么分层**（§二）、**记忆怎么不越权 / 坏了怎么收拾**（§三、§四）、**任务断了怎么从最省时间的点续上**（§五）。

> ## ⚠️ 状态与包结构的更新（2026-09-25）
>
> **① 状态已变：阶段 0 与阶段 1 均已实现**，上表"已设计 · 未实现"已过时。
> 落地代码在 `org.agent.memory`（`MemoryAdvisor` + `ChatMemoryService` + `TurnExtractor`
> + `MemoryWriteGuard` + `dao/ChatMessageDao`），表结构见 `sql/chat_message.sql`，
> 开关是 `rag.memory.enabled` 与 `rag.memory.clearance-filter-enabled`（后者只关读取、不关写入）。
>
> | 章节 | 状态 |
> |---|---|
> | §二 L1（消息流水） | ✅ 已落地（阶段 0） |
> | §三（权限继承） | ✅ **L1 部分已落地（阶段 1）**：密级计算、读取过滤、`MemoryWriteGuard`（只算严、不丢行） |
> | §三 的 L2 部分（`buildAccessFilter` 叠加、scope 维度） | ⏳ 阶段 2 |
> | §二 L0 / L2、§四（坏记忆）、§五（断点续传） | ⏳ 未实现 |
>
> **实现时发现并修正了一处设计错误**：§三 原文的 `min(写入者密级, 资料密级)` 是空操作，
> 已改为 `max(该轮引用资料的密级)`，论证见 §三 的勘误块。另外**阶段 1 收敛为 L1 权限继承**，
> `memory_change_log` 顺延阶段 2（理由见 §六 末）。
>
> 按本文自己的约定，落地完的章节应迁去 RAG-06 —— **这一步已做**：L1 记忆见 RAG-06 §八，
> 权限继承见 §九。本文保留完整设计（含未落地部分），不再重复实现细节。
>
> **② 包结构变更**：本文正文里 `类名.java:行号` 形式的引用（如 `BankAssistant.java:45-57`、
> `RetrievalAdvisor.java:260-289`）**行号已失效**，类名未变。主要变化：
> `BankAssistant` 由 `org.agent.permission` 移入 **`org.agent.api`**；
> `RetrievalAdvisor` 由 `org.agent.rag` 移入 **`org.agent.rag.retrieve`**。
> 完整对照见 RAG-06 开头的「包结构变更说明」。
>
> **③ 一处修正**：§一 等处的 `POST /api/session/chat` 的 `/api` 前缀不存在，
> 实际是 `/session/chat`（端口 8123）。

---

## 一、为什么现在做：先说清缺口

> ⚠️ **本节记录的是立项时（2026-09-23）的状态，不是现状。** 下面三条缺口现在都已填上：
> ① 主链路已接 `MemoryAdvisor`（L1，阶段 0）；② 记忆由我们自己实现，不用 `MessageChatMemoryAdvisor`；
> ③ 那个"记忆 advisor"来了，且 L2（阶段 2）与会话摘要（§5.5）也已落地 —— 见 §六 路线图与 RAG-06 §八–§十一。
> **本节保留作缺口记录**：它说明了"为什么这件事值得做"，而结论（§一 的核心论点）至今仍然成立。

### 缺口不是"体验不好"，是"设计意图与实现之间空着一块"

三件事凑在一起，说明记忆层是**被规划过、但从未落地**的：

1. **主链路完全无状态。** `BankAssistant.chat()` 只有 `chatClient.prompt().user(message)`（`BankAssistant.java:45-57`），不带任何历史。同一个会话里问两次"那它呢？"，第二句是在真空中发生的。

2. **会话表 v2 主动砍掉了记忆字段，并把责任指派了出去。** `sql/rag_assistant_session.sql:8` 的注释写得很明确：

   > `conversation_context_json / context_encrypted / context_message_count`
   > —— 对话记忆由 Spring AI `MessageWindowChatMemory` 负责，会话表不存第二份上下文（避免 PII 重复落库）

   这个决策**是对的**（§二 L0 会沿用它的理由），但它把记忆指派给了一个**从未接入的组件**：全项目只有学习用的 `LoveApp` / `RagApp` 用了 `InMemoryChatMemoryRepository`，主链路一行都没接。

3. **`PermissionAdvisor` 的类注释预留了位置。**

   > `getOrder 取最小优先级，保证先于任何记忆/检索 advisor 执行。`

   顺序语义是预留好的，那个"记忆 advisor"一直没来。

### 核心论点：这个位置**不能**用 `MessageChatMemoryAdvisor` 填

这是全篇最重要的一段，也是为什么必须自己设计而不是"加个 advisor 就完事"。

`MessageChatMemoryAdvisor` 存的是**消息本身**：`before()` 把历史消息原样拼到当前 prompt 前面，`after()` 把助手的输出存回去（`MessageChatMemoryAdvisor.java:78-105`）。

**先纠正一个流传很广的说法**：历史里躺着的是**答案全文**，不是**检索原文**。

`RetrievalAdvisor.augment()` 确实把带编号的资料拼进了用户消息（`RetrievalAdvisor.java:260-289`），但那**只影响发给模型的这一次调用**；而 `MessageChatMemoryAdvisor.before()` 存的用户消息，是它**收到时**的那一份。所以"原文会不会进历史"**完全取决于 advisor 的 order**：

| `MemoryAdvisor.getOrder()` | 它看到的用户消息 | 后果 |
|---|---|---|
| 默认 `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER`（= `HIGHEST_PRECEDENCE + 1000`） | **未被增强的原话** | 原文不进历史 ✅ |
| 配成 `> RetrievalAdvisor(0)`（比如为了"让检索用上历史"而往里挪） | **已被 augment 的**带原文消息 | **整段原文进历史** ❌ |

本项目现有 order 是 `PermissionAdvisor = Integer.MIN_VALUE` / `RetrievalAdvisor = 0` / `NumericGuardrailAdvisor = 10`，**用默认值刚好落在正确位置**（Permission 之后、Retrieval 之前）。

> ⚠️ **这是全文最值得记住的一处工程细节：order 不是风格问题，是安全问题。** 只要为了"检索用上历史"把 `MemoryAdvisor` 往里挪，挪过 `RetrievalAdvisor` 的那一刻，原文就灌进历史了——而这个改动在 code review 里看起来人畜无害。

那么历史里实际有什么、分别泄露什么：

| 历史内容 | 泄露什么 |
|---|---|
| 用户原话（未增强） | "我问过 XX 客户"本身就是敏感信息 |
| **助手答案全文** | **密级 4 的结论** —— 答案会写"该客户评级为 AA-" |
| 答案里的引文标签 | **元数据** —— `sourceLabel()` 生成 `来源：《XX 办法》（doc-004） 版本 3 · 第 12 条`，精确暴露"存在哪份制度、哪一版、哪一条" |

`RetrievalAdvisor` 渲染给模型的消息长这样（`RetrievalAdvisor.java:260-289`）：

```
<用户问题>

【请仅基于下列授权资料作答……】

[1] 来源：《XX 管理办法》（doc-001） 版本 3 · 第 12 条
<秘密级正文……>

[2] ……
```

也就是说，**回放历史会造成下列后果**（第 1–4 条在"原文进历史"的误配下会叠加放大，但**即使 order 配对也依然存在**，因为答案全文本身就含敏感结论）：

| # | 问题 | 具体场景 |
|---|---|---|
| 1 | **绕过权限门禁** | 检索层的 `accessFilter` 只在**检索那一刻**生效。历史回放不经过 `RetrievalAdvisor` 的权限分支，等于把上一轮的答案原样重新注入 context |
| 2 | **降级后仍可读** | 用户上一轮在授权范围内取到了密级 4 的结论；本轮权限被降级（`auth_version` 变了）。检索侧会拦，但**历史里上一轮的答案照样进 prompt** —— 这把 `SessionAuthService` 的 TOCTOU 防护整个绕过去了 |
| 3 | **密级混洗** | 同一会话内先问高密级、再问低密级。低密级问题的答案由历史里的高密级结论拼出来，输出侧却按"低密级问题"来打码/审核 |
| 4 | **元数据推理** | 即便答案正文被脱敏，`sourceLabel()` 的引文标签仍在 —— 足以推出"行内存在 doc-004 这份密级 4 的制度，第 12 条讲的是 X" |
| 5 | **越权结论转嫁** | 用户 A 问出的内容若被蒸馏进共享记忆，用户 B 能召回（§四 D1） |

> **一句话**：检索层的权限是"每次现查"的，记忆层如果是"整段回放"的，那记忆就是权限模型上的一条**旁路**。所有把 `ChatMemory` 直接挂到带权限的 assistant 上的做法，都有这个问题。

**结论：记忆层是一个带权限属性的存储层，不是 `ChatClient` 的一个装饰器。**

### 本文的取舍基调

> **记忆存指针，不存内容；存决策，不存过程。**

这句话在 §二（L1 存什么）、§三（怎么解决越权）、§五（断点怎么续）会各用一次。它是整篇设计的地基。

---

## 二、记忆分层模型（L0–L5）

### 分层依据：三个问题，不是三种技术

很多人把"记忆分层"理解成"内存 / Redis / MySQL / 向量库"——**那是存储分层，不是记忆分层**。存储选型是分层的*结果*，不是依据。

本文的分层依据是三个问题，**这三问直接决定"存哪、存多久、谁能读"**：

| 问题 | 为什么它决定分层 |
|---|---|
| ① **变化频率**：一次对话内变 / 跨会话变 / 几乎不变 | 决定存储介质与失效策略 |
| ② **权限属性**：属于个人 / 属于岗位 / 属于全员 | 决定读取过滤条件（§三的核心） |
| ③ **是否需加工**：原文照存 / 必须蒸馏 / 必须人工确认 | 决定写入管线的复杂度，也决定幻觉风险（§四） |

### 分层总表

| 层 | 名称 | 存什么 | 存哪 | 变化频率 | 权限属性 | 加工 |
|---|---|---|---|---|---|---|
| **L0** | 工作记忆 | 本轮消息 + 本轮命中 chunk | 请求内（context），**不落库** | 单次请求 | 本轮实况 | 无 |
| **L1** | 情景记忆 | 消息流水（问 + 答 + 指针） | MySQL `chat_message` | 会话内 | 个人 | 无 |
| **L2** | 语义记忆 | 蒸馏出的稳定事实 | MySQL `user_memory`（+ 可选向量列） | 跨会话慢变 | 个人 / 岗位 / 机构 | **必须蒸馏** |
| **L3** | 程序记忆 | 可复用做法（SOP / 查询模板） | —— **本项目不做** | 几乎不变 | 全员 | 必须人工 |
| **L4** | 领域记忆 | 制度知识 | 现有 PGVector KB | 版本级 | 全员共享 | 已有管线 |
| **L5** | 权限画像 | 工号 / 角色 / 密级 / org_path | MySQL `rag_assistant_session` | 每次请求现查 | —— | —— |

> ⚠️ **L4 和 L5 不是"记忆"，列出来是为了划清边界。**
> 把"共享知识"和"个人记忆"混为一层，是很多 agent 项目失控的起点：
> 前者按**文档密级**过滤，后者还要多一层**归属人**过滤，两者的失效策略也完全不同（制度靠版本，记忆靠时效）。

---

### L0 · 工作记忆 —— 刻意不落库

**存什么**：本轮 `message`、本轮检索命中的 chunk、业务日期 `bizDate`、权限信封。

**存哪**：只在 `ChatClientRequest.context()` 里活一次请求。**不落库。**

**为什么**：会话表 v2 砍掉 `conversation_context_json` 的理由（"避免 PII 重复落库"）**在这里同样成立，本文不改这条决策**。检索回来的原文已经躺在 PGVector 里了，再在 MySQL 里抄一份，等于凭空多一个泄露面，而攻击者只需要拖走那个 JSON 列就拿到了"按业务拼好的机密内容包"。

**代价与补偿**：不落库 → 无法事后复现"当时模型看到了什么"。**补偿手段是存指针**：把命中的 `chunk_id + doc_id + doc_version` 记进审计表（RAG-04 原 #13），需要复现时按当时的权限重新取一次。这就是"记忆存指针，不存内容"的第一次应用。

---

### L1 · 情景记忆 —— 消息流水，但只存"结论"

**存什么**（`chat_message` 表，DDL 见附录 A）：

| 字段 | 说明 |
|---|---|
| `session_id` / `user_id` | 归属（同时是权限过滤的依据） |
| `turn_no` | 轮次序号，恢复时的锚点 |
| `user_message` | 用户原话 |
| `answer_text` | **最终答案全文** |
| `cited_chunk_refs` | `[{doc_id, doc_version, chunk_id}]` —— **指针，不是原文** |
| `answer_decision` | 放行 / 拒答（对应 `RetrievalAdvisor.Decision`），拒答原因也要存 |
| `biz_date` | 该轮的业务日期 |
| `clearance_level` | **该轮内容可能反映的最高密级**（= 该轮引用资料的 `max(doc_level)`，无引用资料时取会话密级）—— **读取判定用**，见 §三 |
| `perm_auth_version` | 该轮的权限版本 —— **仅审计用**，不参与读取过滤 |
| `created_at` / `expires_at` | 留存与过期 |

> **这两个权限列的分工必须记住，否则一定会误用**：`clearance_level` 回答"这轮内容有多敏感"（判定用），
> `perm_auth_version` 回答"这轮是在什么权限下产生的"（审计用）。**读取时只看前者 + 本轮的实况信封**；
> 后者绝不能拿来做读取判断 —— 拿它判定等于用快照判权，正是 §二 L4/L5 那段反复强调要避免的。
> 两列均已落地，代码见《银行RAG-06》§九。

**关键取舍：只存答案和指针，绝不存 `RetrievalAdvisor.augment()` 渲染出来的那段带原文的 prompt。**

这一条同时解决了四个问题：

1. **权限问题自然消解** —— 回看某一轮时，用**当前**权限去 `cited_chunk_refs` 重新取原文；取不到（密级不够 / 已废止）就如实告知"该轮引用的资料你已无权查看"。而如果存的是渲染后的 prompt，就没法做这个判断了——原文已经跟着历史走。
   > ⚠️ **实现时的偏离（阶段 1）**：真正落地的过滤**没有走"回向量库重取"这条路**，而是在写入时给每轮打一个 `clearance_level` 快照，读取时拿它与**本轮实况信封**比。
   > 原因是代价与依赖：重取要给每轮历史加一次 PG 往返（或新建按 id 批量取接口），并把"记忆读取"这条路的可用性**硬绑在向量库上** —— 而存指针的初衷正是避免这个。
   > **代价要讲清楚**：快照堵得住「用户被降级」，堵不住「引用的制度被事后升密」。后者归阶段 4 巡检。详见 RAG-06 §九 与 §三 的「边界」。
2. **存档体积** —— 一条 10KB 的答案可能对应 50KB 的检索原文，而每轮都重复。
3. **密级混洗（§一表 #3）被堵死** —— 历史里没有原文，低密级问题就无法从历史里"继承"高密级内容。
4. **保住引用归因链（对本项目最贴身的一条）** —— 你已经有 `NumericGuardrailAdvisor`：答案里的金额/比例/期限必须在原文里有出处，否则拒答。**这条规则和"用摘要替代原文"是直接冲突的**：上一轮的"对公存款年利率 1.35%"一旦被摘要成"讨论了存款利率"，下一轮用户问"刚才那个利率是多少"，模型要么答不出（体验是"上一句刚说的这一句就忘了"），要么编一个（被 `NumericGuardrailAdvisor` 拦掉，体验同样是"答了又撤回"）。
   **所以摘要只能用来定位，不能用来承载事实**：摘要进上下文（"上一轮聊的是 XX 制度"），数字按指针回原文取。

**代价（必须写清楚）**：回看时若原文已出新版，你看到的是**新版**。所以 `cited_chunk_refs` 里**必须带 `doc_version` 快照**，展示时明确标注"该轮引用的是 v3，当前已有 v5"。这正好复用你 `DocumentVersionResolver` 已有的版本语义（RAG-06 §五）——**不是新机制，是同一套版本观从"检索时"延伸到"回看时"**。

**为什么这一层优先做**：RAG-04 里 #8 的剩余项「指代消解」**卡在这层上**。"那它呢？"要能补全，前提是系统知道上一轮问的是什么。L1 一落地，指代消解和用户画像统计同时有了前提。
<p>
✅ **本层已落地**（阶段 0，见 RAG-06 §八）。但要说准：**指代消解本身仍未实现** ——
历史已经被放进 `context`（`MemoryAdvisor.CONTEXT_HISTORY_TURNS`），可
`QueryExpander.expand(query)` 至今只接收当前 query 一个入参，**没人读那个键**，
检索层看到的仍是「那它呢」三个字。所以现在的状态是"**数据到位、消费方缺席**"：
紧接着做 #8 时改动面是收敛的（把上文接进改写层），而不是"已经能用了"。
同理，用户画像统计（L2）也还没开始。

---

### L2 · 语义记忆 —— 唯一需要"加工"的一层

> ✅ **已落地（2026-09-26，阶段 2）**，见 RAG-06 §十。落地时的一个必要修正：
> 下面的 `fact_key` 例子（`"常用机构"`）**存中文标签是不可行的** —— 它活在提示词里，
> 一旦有人改词，旧行就变孤儿且 supersede **静默失效**。落地版存 **ASCII code**（`preferred_org`），
> 展示文本由 `FactKey.label()` 提供。另外 `scope` 只产生 `USER`（DEPT 只有读分支，无自动写入路径）。

**存什么**：从多轮对话中蒸馏出的稳定事实，而不是对话本身。

```
{user_id: "E10023", fact_key: "常用机构", fact_value: "0100/0101",
 source_session_id: "...", source_turn_no: 4,
 scope: "USER", clearance_level: 2, confidence: 0.9,
 valid_from: "2026-09-01", expires_at: null}
```

**为什么必须蒸馏**：L1 是"按时间铺开"的，L2 是"按主题收拢"的。用户三个月前提过一次"我主要做对公业务"，L1 里它埋在第 40 轮，检索不到；L2 里它就是一条 `常用条线: 对公`，一次查询就拿到。**没有 L2，每次会话都像第一次见这个人。**

**为什么它是幻觉的重灾区**：L1 存的是用户原话，有据可查；L2 是**模型加工过的**，模型可能"总结"出用户没说过的东西。§四 会专门用一条规则挡这个：**模型只能复述，不能推测**。

**权限属性可以是三档**（`scope` 字段）：

| scope | 谁能读 | 例子 | 写入策略 |
|---|---|---|---|
| `USER` | 只有本人 | 常用机构、偏好产品 | 可自动写 |
| `DEPT` | 同 `org_path` 内 | 本部门高频查的制度 | 需人工确认 |
| `GLOBAL` | 全员（且受密级过滤） | —— **本项目建议不开** | 禁止 |

> **`GLOBAL` 为什么建议不开**：全局记忆一旦被污染（幻觉、越权内容、已废止结论），污染面是全体员工。收益（省几毫秒）远小于风险。这条判断可以直接写进"设计取舍"的面试回答。

---

### L3 · 程序记忆 —— 本项目明确不做

程序记忆 = "怎么做事"的可复用经验（Voyager 的 skill library、Claude 的 Skills 都是这个）。

**不做的理由**（不是"太难"，是"不划算"）：

- 本项目的"程序"数量是个位数（查制度、查时效、数字核对、将来的图谱多跳），**全部硬编码在 Advisor 链里比"让模型自主习得"更可控**。
- 银行场景下"模型自己学会了新做法"是**风险**不是亮点——它绕过了评审。任何新的处理路径都必须走人工评审，那"自主学习"就没有存在意义了。
- 真正需要"程序记忆"的形态是**查询模板库**（把"某类问题 → 该怎么改写查询"沉淀下来）。这个有需要时直接读 `TermDictionary` 的现成模式扩展即可，不必单开一层。

---

### L4 / L5 · 划清边界

**L4 领域记忆（现有 KB）**：制度知识。它**不随用户变**，权限靠 `doc_level + org_codes` 过滤（`SessionAuthService.buildAccessFilter()`），失效靠版本收敛（`DocumentVersionResolver`）。**本文不动它。**

**L5 权限画像 —— 明确不算记忆。** 这一条是刻意的：

> **记忆可以过期，权限不能过期。**

工程上的体现：`SessionAuthService` **每次请求**都实时校验 `auth_version`（TOCTOU 防护），而不是"会话建立时记住权限就一直用"。同理，**记忆的读取权限必须来自本轮的实况信封，绝不能来自记忆里存的快照**——记忆里那个 `perm_auth_version` 是给"事后审计这轮该不该给他看"用的，不是给"读取时判断"用的。

---

### 一次对话里的读路径

```
请求 → PermissionAdvisor（实时校验，产信封 + accessFilter）
     ├─→ L5 权限画像（来自本轮实况，不是记忆）
     ├─→ L1 情景记忆  ← 取最近 N 轮，按当前权限二次过滤
     ├─→ L2 语义记忆  ← 按 user_id + scope 取事实，同样二次过滤
     ├─→ L4 领域记忆  ← 现有 RetrievalAdvisor 检索
     └─→ 拼装 prompt（L0：临时，不落库）
                    ↓
              写回：L1 追加一条消息 + 指针
                    L2 走写入管线（§四）
```

---

## 三、记忆的权限继承（本设计最核心的一节）

> **这一节是"银行 RAG"和"通用 RAG 套个银行外壳"的分界线。**
> Mem0、Letta、Zep 这些主流记忆方案**全都没有权限概念**——它们的记忆是无主的、全局可召回的。这不是它们的缺点（通用场景确实不需要），但意味着**这块你抄不到，必须自己设计**，也意味着**做出来了就是差异化**。

### 三个危险点（先定义威胁，再谈设计）

| # | 危险点 | 具体场景 | 后果 |
|---|---|---|---|
| **D1** | 跨用户泄露 | A 问出的内容被蒸馏进记忆，B 召回时命中 | 越权，且**没有任何日志能看出这是越权**（看起来就是"记忆命中了"） |
| **D2** | 记忆密级 ≠ 用户密级 | 用户密级 2，但他上一轮问的内容引用了密级 4 的资料（因为有别的授权路径）→ 蒸馏出的记忆带上了密级 4 的知识 | 记忆成了**洗密级**的通道：低权限用户通过记忆读到高密级结论 |
| **D3** | 权限变更后的存量记忆 | 用户调岗/降级，`auth_version` 变了 | 存量记忆仍按旧权限在召回；且"记忆里存的那个 `auth_version` 快照"会误导审计 |

**D2 是本设计里最容易被忽略、也最危险的一条。** 通用记忆方案的核心假设是"记忆是用户自己的东西，所以按 owner 过滤就够了"——在银行场景下这条假设是错的：**用户有权*提问*，不等于有权*长期持有*由此推导出的结论。**

### 设计：记忆继承的是"内容的密级"，不是"写入者的密级"

这是 D2 的解法，也是全文最该记住的一句话：

```
记忆的 clearance_level = max( 该轮引用资料的 doc_level )
```

> ⚠️ **勘误（2026-09-25 实现时发现）**：本节原写的是 `min(写入者密级, 该轮引用的最高资料密级)`，
> **那是错的，而且错得很隐蔽 —— 它在干净数据上是个空操作**。两层论证：
>
> **第一层（恒等式）**：检索侧的 `passesGate` 已经保证 `doc_level <= envelope.dataClearanceLevel()`，
> 也就是"引用资料的最高密级 ≤ 写入者密级"**恒成立**。于是 `min` 恒等于右边那一项，
> 写 `min` 和写"写入者密级"在正常数据上**完全没有区别** —— 它一行行为都不改变。
>
> **第二层（它唯一生效的方向）**：`min` 只在"引用资料的密级**高于**写入者密级"时才有影响，
> 而那只能是 metadata 脏数据（`doc_level` 被写错/被绕过门禁塞进来）。此时 `min` 会把
> 一份密级 4 的内容**降级标成密级 2** —— 正是 D2 想要的"洗密级"通道方向。**一个只在脏数据上生效、
> 且生效方向恰好是不安全那一侧的表达式，比不写更危险，因为它看起来像做了防护。**
>
> 所以正确的口径是 **`max(该轮引用资料的密级)`**：资料越敏感，记忆越难被读到。
> 这与下面那句"保守做法：取引用资料的最高密级"、`MemoryWriteGuard` 表里的
> "密级 → 取更严的值"、读取侧的门 `m_clearance <= envelope.clearanceLevel()` 三处本来就一致 ——
> 原来只有公式写反了。

**不能**简化成 `= 写入者的数据密级`。举例说明为什么：

```
用户密级 2，查询"对公账户开销户流程"
  → 检索命中 doc_level 2 的制度  → 蒸馏记忆：密级 2  ✓ 正常
用户密级 2，查询中含"XX 客户评级"（该问题本身在授权范围内）
  → 检索命中 doc_level 4 的客户档案 → 蒸馏记忆：密级 2 ✗ 错！
     他有权"在这一次问答里看到"这个结论，但没有形成"可长期召回的密级 4 记忆"的权利
```

前者是"授权的读"，后者是"推导出的存"。**读的授权是一次性的，存的授权要重新判。** 保守做法：**取引用资料的最高密级作为记忆密级**。宁可把记忆判得过严（顶多损失一点召回率），也不能过松。

### 读取侧：复用现有的 `buildAccessFilter`

不要另写一套过滤逻辑——**把记忆当成"另一个受限向量库"**：

```java
// 现有（文档检索，SessionAuthService.java:142）
"kb_id in [...] && doc_level <= {clearance} && org_codes in [...]"

// 新增（记忆召回，在现有条件上叠加归属维度）
memoryFilter = existingAccessFilter
             + " && m_clearance <= " + envelope.clearanceLevel()
             + " && (m_scope == 'USER' && m_owner_id == '" + userId + "'"
             + "  || m_scope == 'DEPT' && m_org_path == '" + orgPath + "')"
```

**为什么必须复用而不是新写**：`buildAccessFilter` 里那句注释（`SessionAuthService.java:147`）记录了一个已经踩过的坑——过滤条件不能把"该由下游脱敏处理的东西"提前滤掉。记忆过滤一旦另起一套，这个坑要重踩一遍。**一条权限策略，一个出口。**

> **上面这段是针对 L2（向量召回）的，阶段 2 才做。阶段 1 先做的是 L1**，而 L1 的读取不过向量库
> ——它是 `chat_message` 表上的行，所以过滤形态不同但**口径逐字相同**：
>
> | | L2（本节的写法） | L1（已落地） |
> |---|---|---|
> | 过滤实现 | 拼进向量库的 `AccessFilter` | `ChatMemoryService` 里的 Java 判定 `1 <= clearance_level <= envelope.dataClearanceLevel()` |
> | 不过怎么办 | 该条记忆不被召回 | **整轮丢弃**（与"拒答轮不进历史"同一机制） |
> | 条件写进 SQL 的 `WHERE` 吗 | 是（向量库查询的一部分） | **否** —— 刻意放在 `LIMIT` 之后 |
>
> L1 不把密级条件写进 SQL 的 `WHERE`，是因为 `LIMIT` 的语义必须是"取最近 N 轮"，
> 而不是"取最近 N 轮**当中能读的**"。后者会在降级后**跳过最近几轮去捞更早的历史**，
> 等于把降级整个绕过去。宁可真话少说，不可旧话多说。

### 写入侧：`MemoryWriteGuard`

写入是权限的入口，必须有一个**独立的、可测的**门禁组件（别散在 service 里）：

| 校验 | 规则 | 失败处理 |
|---|---|---|
| 归属 | 记忆的 `owner_id` 必须 = 当前信封的 `userId` | 拒绝写入 |
| 密级 | `clearance = max(引用资料密级)`（见上） | 取更严的值 |
| scope 合法性 | `USER` 可自动；`DEPT` 需人工确认；`GLOBAL` 拒绝 | 降级为 `USER` 或挂起 |
| 内容红线 | 命中凭证/密码模式 → 直接拒绝（复用 `CodeSniffer` 的思路） | 拒绝 + 审计 |
| 来源 | 必须能追溯到 `source_session_id + source_turn_no` | 无源不写 |

> **"无源不写"是挡幻觉记忆的第一道闸。** 任何进不了 L1 的记忆，都不该进 L2。

**但上表里的"拒绝写入"只适用于 L2，L1 不能这么做** —— 阶段 1 落地时明确区分了这一点：

| | L1 `chat_message` | L2 `user_memory` |
|---|---|---|
| 它是什么 | **审计记录**（"发生了什么"） | **加工产物**（"我们从中得出了什么"） |
| 能不能拒写 | **不能**。拒一次就在审计里留一个洞：用户问了、系统答了、却不留痕 | 能，而且该拒。"无源不写"正是这一层 |

所以阶段 1 的 `MemoryWriteGuard` 是个**纯函数**：输入检索到的资料与本轮信封，输出一个
**更严的密级值**，**一行都不丢**。上表四项校验在 L1 上的落点分别是：归属（已由阶段 0 的
读取复核 `user_id` 覆盖）、`scope`（L1 恒为 `USER`，且表里根本没有 `scope` 列）、
内容红线（`CodeSniffer` 认的是代码不是凭证，硬拦会拦掉正常业务答案）、来源（这一行本身就是来源）。
**四项都不落，逐条理由写在 `MemoryWriteGuard` 的类注释里。**

### D3 的处理：不删，但读取时按实况过滤

`auth_version` 变化时，**不要批量删除存量记忆**（删了不可逆，且可能删掉本身仍合规的内容）。正确做法：

1. **读取时按本轮实况过滤**（上面那段 filter 已经做到了——`envelope` 是本轮实时的）。✅ **已落地**（阶段 1，L1）。
2. **异步重算**：`auth_version` 变更时给该用户的记忆打 `needs_review` 标记，由后台任务用新权限重判一遍 `clearance`。⏳ 阶段 4。**L1 上刻意不加标注列**：在没有消费方（巡检任务、管理接口）之前加一个字段是投机设计。
3. **审计可见**：记忆表里那个 `perm_auth_version` 快照**只用于回答"这条记忆是在什么权限下产生的"**，不参与读取判定。这个区分要在代码注释里写死，否则后来者一定会误用。

---

## 四、坏记忆：谁写、谁删、怎么审计

> 行业现状参考：2026 年 3 月的 State of AI Agents 报告结论是 **"memory is immature everywhere"** —— 没有一个框架做到了生产级的长期记忆（语义检索 + 剪枝）。报告给的操作性建议只有一句，但很关键：**"不要把记忆当勾选项。要明确谁可以写、谁可以删、坏记忆怎么审计。"**
>
> 本节就是把这三问逐条答掉。

### 先定义：什么算"坏记忆"

| 类型 | 怎么产生的 | 危害 | 对应机制 |
|---|---|---|---|
| ① 幻觉记忆 | 模型"总结"出用户没说过的事实 | 后续所有回答被污染，且**溯源不到用户原话** | 只复述不推测（见下） |
| ② 过期记忆 | "当前利率 3.5%" 存下来，利率已调 | 答错，且用户会信（因为它"记得"他说过） | `expires_at` + 挂 `TemporalGate` |
| ③ 越权记忆 | §三 D1/D2 | 越权泄露，且**看起来像正常召回** | `MemoryWriteGuard` + 读取过滤 |
| ④ 违禁内容 | 用户粘了一段凭证/密码进去 | 明文长期驻留 | 内容红线（复用 `CodeSniffer`） |
| ⑤ 矛盾记忆 | 用户改口了，新事实和旧事实并存 | 召回时随机命中一个，行为不可预测 | supersede 而非 delete（见下） |

### 写入策略：分三档，不是"全自动"

自动写入是记忆系统最诱人的设计，也是坏记忆的主要来源。**按内容敏感度分档**：

| 档 | 内容 | 策略 | 理由 |
|---|---|---|---|
| **自动写** | 偏好、常用机构、关注条线、常查的制度主题 | 直接落库 | 错了也只是"答得不够贴心"，不是合规问题 |
| **需确认** | 涉及具体客户、金额阈值、评级结论 | 落库为 `PENDING`，用户确认或二次出现才生效 | 用户的"一次提及"不等于他的稳定认知 |
| **禁止写** | 凭证/密码、超出用户密级的结论、**模型的推测** | 拒绝 | 见下 |

**最关键的一条规则：**

> **模型只能复述用户明说的事实，不能推测。**
>
> 判定方式：每条 L2 记忆必须能指回 `source_session_id + source_turn_no`，且**该轮用户原话里必须含有构成这条事实的要素**。做不到就不写。
>
> 这条规则的价值在于它**把"记忆质量"问题转化成了"可验证的引用"问题**——你项目已经有了引用归因的完整实践（RAG-06 §四的四种拒答），这里是同一套方法论平移过来：**没有出处的结论，不配被记住。** 面试时这是一个很好的呼应点。

### 纠正：supersede，不要 delete

> ### ✅ 已落地（2026-09-26）+ 一条本文原来**没说透**的结论（补在下面）
>
> **结论：`status` 不承载 supersede 语义。** 本文 A.2 的 `status` 枚举注释把 `STALE` 写成
> 「所依制度已废止」，而下面「巡检②」的判据写的是 `valid_to IS NULL` —— 两处合起来指向同一件事：
>
> | 列 | 回答的问题 | 谁写 |
> |---|---|---|
> | `valid_to` | 「**历史上**它何时为真」（真值区间） | 被新事实取代时，系统写 |
> | `expires_at` | 「到什么时候我还可以**不问就信**」（怀疑标记） | 写入时按 TTL 预算 |
> | `status` | 「这条记忆的**生命周期 / 健康度**」 | 巡检（`STALE`）、用户（`USER_DELETED`） |
>
> **所以被 supersede 掉的行留 `ACTIVE`** —— 它是「不再当前」，不是「坏」。
> 给 `STALE` 塞第二个含义会**直接破坏**巡检①：那是给"所依制度已废止"预留的处置动作，
> 混进 supersede 之后，巡检① 就没法用 `status` 表达它的结论了。
>
> **风险与对策**（这条一定要讲，否则是个真陷阱）：将来有人写 `WHERE status='ACTIVE'`
> 会把**被取代的历史行全部复活**。对策是 DAO **不提供任何只按 `status` 筛的通用查询**
> （与 `ChatMessageDao`「不提供按权限字段过滤的查询」同一手法）+ 列注释写明 + 一条测试锁住。
> **DB 级加固**：生成列 `active_key = IF(valid_to IS NULL, fact_key, NULL)`
> + `UNIQUE KEY uk_owner_active (owner_id, scope, active_key)` ——
> 唯一索引对 NULL 不生效，所以必须把「有效」**压成非 NULL 值**才能被约束。
> 这把"同一 `(owner, scope, fact_key)` 至多一条有效行"从 Java 层不变量落成了 DB 约束。


用户改口时（"我上周说主要做对公，其实我现在转对私了"）：

```
❌ 删掉旧记忆 → 失去"他曾经这么说过"的信息，且删除不可逆、审计断链
✅ 旧记忆标 superseded_by = 新记忆 id，valid_to = now；新记忆 valid_from = now
```

这就是 Zep/Graphiti 的**双时态（bi-temporal）**做法：每条事实有两个时间——**何时为真**（`valid_from`/`valid_to`）和**何时被系统记录**（`created_at`）。查询默认只看"当前为真"的，但历史查询能回溯。

> **这个思路你项目里已经有了，只是用在文档上。**
> `DocumentVersionResolver` 对制度做的是"版本收敛 + 推导旧版失效日 + 标注取代关系"（RAG-06 §五）——**和 supersede 旧记忆是同一件事**。
> 差别只在：制度失效日由新版本倒推，记忆失效日由新事实的发生时间确定。
> 把这两套逻辑合并成一个 `Supersedable` 抽象，是这个设计里最优雅的一步，也是最能讲的一段。

### TTL：带时效的事实必须有保质期

不是所有记忆都该永久保留。分两类：

| 类别 | `expires_at` | 例子 |
|---|---|---|
| **稳定事实** | `null`（长期） | 所属部门、角色 |
| **时效事实** | **必填** | 当前利率、当前产品清单、"这个季度重点抓 XX" |

**时效记忆失效后怎么处理**：不是删，是标记 `expired`，且**失效时不能靠"记不住"来兜底**——更稳的做法是让记忆**携带指向制度的指针**，而不是携带制度的内容：

```
❌ {fact: "当前对公存款利率 1.35%", expires_at: "2026-12-31"}   ← 利率一变就错
✅ {fact: "关注对公存款利率", ref_doc_id: "doc-088"}            ← 召回"关注点"，利率现查
```

**指针还是内容**——这是 §一那句基调在 L2 上的第三次应用。凡是"答案会变"的记忆，存指针。

### 审计：append-only + 哈希链

记忆的每一次变更（写 / 改 / 失效 / 删）都要留痕，且**不可篡改**：

```
memory_change_log: {memory_id, action, before_json, after_json, actor, reason, prev_hash, hash}
```

`prev_hash` 指向上一条的 `hash`。这和 RAG-04 原 #13「审计表 + 哈希链」是**同一个机制，同一个理由**——防篡改。建议**直接复用那张表的实现**，不要各写一套。

> ### ✅ 已落地（2026-09-26）：这条建议被照做了
>
> 哈希链建成了**通用纯函数** `org.agent.audit.AuditHashChain`（`hash` / `verify` / 规范化 JSON），
> **刻意不依赖记忆模块** —— 所以 RAG-04 #13 将来接审计查询接口时可以直接复用，
> 不必从记忆模块里把它抠出来。可以说 **#13 的底层实现已经就位，只差消费方**。
>
> 落地时的**四处偏离**，都在附录 A.3 的「落地说明」里（`memory_id` → `fact_uid`、
> 新增 `entity_type`、`createdAt` 由 Java 提供、JSON 规范化后哈希）。
> 另外实测到一个反直觉的结论：**本库里"全链校验为空"永远不可能成立**（审计表是全库共享的
> append-only 表，而 DAO 的列映射测试会故意写进假哈希）—— 所以校验断言只能做成
> 「相对参照点」的形式。这条记在 RAG-06 §十，写共享表断言时会再遇到。

### 巡检：三类自动检测

记忆是"会腐烂"的数据，必须有人定期翻。三个检测项，**第一个是本项目独有的、最漂亮的**：

| 检测 | 做法 | 处置 |
|---|---|---|
| **① 引用的制度已废止** | 把记忆里的 `ref_doc_id` 对 `DocumentVersionResolver` 的版本表做一次 join；已废止 → 该记忆标 `stale`，不再参与召回 | 自动失效 |
| ② 自相矛盾 | 同 `fact_key` 下多条 `valid_to IS NULL` 的记录（正常应该只有一条） | 报警，人工裁决 |
| ③ 长期未命中 | `last_hit_at` 超过 N 天 | 归档（不是删） |

> **① 是你的差异化**：通用记忆方案没有"制度版本"这个概念，所以它们**无法发现"我记住的这个结论，所依据的制度已经废止了"**。你有 `DocumentVersionResolver`，这个检测几乎是免费的。
> 这就是"垂直领域的记忆系统"比"通用记忆系统"强的地方——**你能利用领域内的结构**。

### 用户权利：可查、可删

合规要求（个人信息保护）：用户要能看到"你记住了我什么"，并能删除。

- 提供一个 `GET /memory?userId=自己` 的只读接口（**只能查自己的**，`userId` 从信封取，不接受入参——这是个很容易写错的地方）
- 提供 `DELETE /memory/{id}`，走 supersede 的逆操作（标 `deleted_by_user`，从召回中排除，审计留痕）
- **这两个接口是合规项，不是功能项**。RAG-04 的排序标准是"亮点优先"，所以它在本文里排在路线图靠后（§六 阶段 4），但**生产环境里它该排在前面**——和 RAG-04 里"审计表排后"是同一个道理，值得在面试时主动点出来。

---

## 五、任务断点续传：怎么从最省时间的点继续

### 0. 先分清两类断点（它们的解法完全不同）

| 类型 | 场景 | 恢复目标 | 主要机制 |
|---|---|---|---|
| **会话级断点** | 网络断、浏览器关、服务重启、用户明天再来 | 让对话**接着聊**，不重复问已确认的信息 | L1 情景记忆 + 会话摘要（§5.5） |
| **任务级断点** | 一次请求内部的多步任务跑一半失败 | 让**计算**接着跑，不重算已完成的部分 | `rag_task_run` / `rag_task_step`（§5.2–5.4） |

**本项目有三个具体的断点场景**，覆盖了两类：

1. **对话中断**（会话级）—— 今天就已经存在，只是没记忆所以无感。
2. **多跳检索 / 工具链中断**（任务级）—— GraphRAG-lite（RAG-04 #1）和 Function Calling（RAG-04 #3）一旦落地，就是多步任务，中途失败会**整链重跑**。昂贵的是其中的模型调用。
3. **批量入库中断**（任务级，**今天就有真实损失**）—— `RagDocumentIngestService` 现在是**全有或全无**：100 篇文档跑到第 60 篇挂了，重跑会把前 60 篇的 embedding 全部重算一遍。`IngestManifestLoader` 只解析元数据、**不记进度**（`IngestManifestLoader.java` 全文只是个 YAML 解析器）。而 embedding 是走硅基流动 API 的**真金白银**。

> **场景 3 是本节最该先做的**：改动最小（给入库加"文件级进度表 + 内容哈希跳过"），收益立刻可量化（省掉重复 embedding 的钱和时间），而且它天然是"任务级断点续传"的最小可用版本。

### 1. 核心原则（本章的基调，也是 §一 那句的第三次应用）

> **存决策，不存过程。存指针，不存内容。**

- **存决策**：存"第 3 步检索用的是什么查询、什么 filter、命中了哪几个 chunk_id"，不存"模型当时的思考过程"。理由：决策是**可重放的**（给定相同输入必然得到相同输出，或至少有据可查），思考过程既不可重放也不可信。
- **存指针**：第 3 步的输出存 `chunk_id` 列表，不存 chunk 正文。正文在 PGVector 里，恢复时按当时权限重新取——**和 §二 L1 是同一个理由**。

### 2. 表设计

**`rag_task_run`（任务实例）**

| 字段 | 说明 |
|---|---|
| `task_id` / `session_id` / `trace_id` | 标识与关联 |
| `task_type` | `CHAT_TURN` / `MULTI_HOP_RETRIEVAL` / `INGEST_BATCH` / `TOOL_CHAIN` |
| `status` | `RUNNING` / `SUCCEEDED` / `FAILED` / `SUSPENDED` |
| `current_step_no` / `total_steps` | 进度 |
| `biz_date` | **恢复时必须原样带回**，否则时效判定会跨日漂移 |
| `perm_auth_version` / `access_filter_hash` | 权限快照，恢复时比对（§5.3） |
| `resume_count` / `last_resumed_at` | 恢复了几次（防"恢复风暴"，也用于排查） |

**`rag_task_step`（步骤）**

| 字段组 | 字段 | 用途 |
|---|---|---|
| 定位 | `run_id`, `step_no` | |
| 类型 | `step_type` = `QUERY_EXPAND`/`EMBED`/`RETRIEVE`/`RERANK`/`LLM_CALL`/`TOOL_CALL`/`PERSIST` | 决定成本与是否可缓存 |
| 输入 | `input_hash`, `input_json` | 重放依据 |
| 输出 | `output_json`, `output_hash` | 复用依据（**存指针**） |
| 执行 | `status`, `error_code`, `retry_count`, `cost_ms`, `cost_tokens` | 成本核算 |
| **有效性** | `doc_version_refs`（JSON）, `perm_auth_version`, `expires_at` | **§5.3 的核心** |
| 依赖 | `deps`（step_no 数组） | 为脏传播预留（§5.6 第三种粒度） |
| 副作用 | `side_effect`, `idempotency_key` | §5.4 幂等 |

### 3. 恢复点选择：**"最后一个成功的步骤"是错的**

这是本节的核心，也是问题里"从断开的点**或者尽可能省时间的点**继续"的真正含义。先说三个反直觉的例子：

**例 1：从最后一步恢复会漏掉失效的前置步骤**

```
step 1  改写查询          1 ms     ✓
step 2  embedding         300 ms   ✓
step 3  检索（命中 5 篇）  200 ms   ✓
step 4  调模型生成答案     2.5 s    ✗ 崩在这
```

最后成功的是 step 3 → 天真做法从 step 4 重跑。**但如果期间用户的权限被降级了**，step 3 的命中集合里有 2 篇现在他无权看了——从 step 4 重跑，等于**用越权的检索结果生成答案**。必须回退到 step 3。

> **规律一：恢复点由"最早的失效步骤"决定，不由"最后成功的步骤"决定。**

**例 2：反过来，便宜步骤不值得做有效性校验**

step 1 只要 1ms。为了判断它是否失效，你要比对 `input_hash` + 查词典版本 + 读 `expires_at`——**校验本身可能比重跑还贵**。

> **规律二：`cost(校验) > cost(重跑)` 时，直接重跑。** 只有昂贵步骤（`EMBED` / `LLM_CALL` / `TOOL_CALL`）才值得做有效性校验。
>
> 落地方式：`step_type` 上挂一个 `checkable` 标记。不可校验的步骤**一律视为失效**，恢复时无条件重跑——省掉判断逻辑，也省掉判断的 bug。

**例 3：有依赖图时，失效应该沿图传播**

多跳检索里，第 2 跳的查询由第 1 跳的命中集构成。第 1 跳命中 5 篇，其中 1 篇出了新版 → 只有依赖那 1 篇的第 2 跳分支要重跑，另外 4 篇的分支可以复用。

> **规律三：失效按依赖图传播（脏标记），不是整段作废。** 这是进阶优化，`deps` 字段先留着，第一版不必实现。

#### 恢复点算法

```java
/** 返回应从哪一步重新执行 */
int resumeFrom(TaskRun run, SessionEnvelope now) {
    for (TaskStep s : run.stepsOrderedByStepNo()) {
        if (s.status() == FAILED) {
            return s.stepNo();                       // 崩在这一步，重跑它
        }
        if (s.status() == SUCCESS && !stillValid(s, now)) {
            return s.stepNo();                       // 最早的失效步骤
        }
    }
    return run.lastStepNo() + 1;                     // 全部仍有效：纯续跑
}

/** 输出是否还能用 —— 五个条件全满足才可复用 */
boolean stillValid(TaskStep s, SessionEnvelope now) {
    return !s.checkable()                            // 规律二：不可校验的一律重跑
        || ( s.inputHashMatchesReplay()              // ① 输入未变
          && !isExpired(s, now)                      // ② 时效未过
          && s.permAuthVersion() == now.authVersion() // ③ 权限未变（防例 1 的越权复用）
          && docVersionsUnchanged(s)                 // ④ 引用的文档版本未变
          && (!s.sideEffect() || !s.idempotencyKeyUsed()) ); // ⑤ 副作用未执行过
}
```

**条件 ③ 和 ④ 是本项目独有的**——通用 agent 框架的 checkpoint 只做 ①（输入哈希），因为它们没有"权限会变"和"知识会过期"这两个概念。**你这两条加起来，就是"可审计的断点续传"**，也是这节最值得讲的部分。

#### 设计期比运行期更重要：checkpoint 边界要落在便宜段之后

运行期算法再聪明，也救不了一个"昂贵步骤排在最前面"的管线。**便宜步骤放前面、昂贵步骤放后面**，让检查点边界落在便宜段末尾——这样恢复时最多重跑便宜段。

```
✅ [改写查询 1ms] → [embedding 300ms] → ‖检查点‖ → [多跳检索] → [模型生成 2.5s]
                                          ↑ 边界在便宜段后，恢复代价 ~300ms

❌ [模型生成 2.5s] → [后处理] → ‖检查点‖ → [检索]
                                 ↑ 边界在昂贵段后，前面白跑就得全部重来
```

### 4. 幂等：崩溃发生在"副作用之后、确认之前"

这是断点续传里最经典的一个坑，**必须有明确的处理**：

```
执行 → 写工单（有副作用）→ 【进程崩溃】→ 恢复时 status 仍是 RUNNING
                                        → 重放 → 写第二张工单 ❌
```

解法：**幂等的步骤用 `idempotency_key` 去重，不幂等的步骤要么改成幂等，要么把"已执行"的确认做在副作用之前。**

| 步骤 | 幂等性 | 处理 |
|---|---|---|
| 检索 / embedding / 模型调用 | 幂等（只读或可重算） | 无需特殊处理 |
| 写审计日志 | **天然幂等**（append-only，重复写=多一条） | 用 `idempotency_key` 去重 |
| 发通知 / 建工单 / 提交复核 | **不幂等** | 强制 `idempotency_key`，执行前先查 |

> **对本项目的实际影响**：RAG-04 #12「高风险话题人工复核状态机」一旦落地，就会有"派发复核任务"这种不幂等的副作用。**断点续传和复核状态机必须一起设计**，否则会派发重复的复核单——在银行里，重复派单是要有人负责的。

### 5. 会话级恢复：恢复"槽位"，不恢复"思维链"

会话断点（用户明天回来接着聊）的恢复内容：

```
恢复给模型的上下文 = 会话摘要（compaction 产物）
                  + 最近 N 轮原话（L1 读，按当前权限过滤）
                  + 未完成任务的槽位状态（"上次在等你确认要不要查 XX"）
```

三个明确的"不做"：

- **不塞全历史**。长会话全塞回去 token 成本失控，而且**历史越长越容易把旧权限下的内容带进新请求**（§一的旁路问题）。
- **不恢复"模型当时想到哪了"**。存决策不存过程——模型没有可靠的"中间思路"可存，而槽位是确定性的。
- **不在恢复时重放工具调用**。恢复是"接着聊"，不是"接着算"。要接着算的是任务级断点（§5.2），走不同的表。

**摘要（compaction）的触发策略**：按 token 数触发（比如超过窗口 60%）而不是按轮数，因为一轮的长度差异极大（一条"你好"和一段粘贴的报表）。

> ### ✅ 本节已落地（2026-09-26），**但触发策略有一处明确偏离**
>
> 摘要表与压缩管线已实现，见 RAG-06 §十一 与**新增的附录 A.4**。上面那条「恢复给模型的上下文」
> 的构成也照实现了 —— 只是**目前只由 `MemoryAdvisor` 常驻消费**（每轮都读），
> 而不是在"会话恢复"这一刻才拼。
>
> **偏离：主触发不是 token 规则，而是「按轮次滑出窗口」。**
> 本节写的 token 触发是**为恢复场景**设想的（会话暂停后重新拉起，攒了一大段历史一次性压）。
> 而本项目的窗口是**连续滑动**的：`window-turns: 3` ⇒ 只取最近 6 行，第 4 轮之后第 1 轮就滑出去了 ——
> 也就是说**不需要等到"恢复"那一刻，上下文已经在每轮悄悄丢东西了**。所以：
>
> | 触发 | 场景 | 实现 |
> |---|---|---|
> | **唯一的生效触发（按轮次滑出）** | 连续对话，每轮都可能丢轮次 | 「有轮次滑出窗口且未被 `covers_through_turn_no` 覆盖」就压 —— 覆盖区间与 L1 窗口**无缝且不重叠** |
> | token 规则 | 恢复场景 / 单轮超长 | `ContextBudget` 按**字符数**估算（classpath 无 tokenizer，中文约 1 字 ≈ 1 token，是**保守的偏大估计**）—— 它是**预算**不是计费数字。⚠️ **本阶段只做观测**：越线只打一条 WARN，**不触发再压缩** |
>
> **为什么 token 规则只做观测而不接成触发器**（落地时的判断，不是偷懒）：
> 它压的是**已经滑出去的内容**，不是当前窗口 —— 所以"再压一次"**体积并不会变小**，
> 却会换来一个**每轮都可能触发的 LLM 调用**。那句话本身是对的（"按轮数触发不对，因为一轮长度差异极大"），
> 只是它描述的痛点在**恢复场景**下才存在；本项目连续滑动的窗口里，**丢轮次**与轮的长度无关，
> 「按轮次滑出」才是它的直接解药。
> ⚠️ 于是那条 WARN 频繁出现是在提示**配置不匹配**（`budget-chars` / `trigger-ratio` 与
> `window-turns` / `max-chars-per-message` 不同源：前一组是按恢复场景定的，比连续滑动场景小），
> 是**配置提示**而不是故障信号。

### 6. 三种恢复粒度：成本 vs 复杂度

| 粒度 | 恢复代价 | 实现复杂度 | 适用场景 |
|---|---|---|---|
| **整链重跑** | 最高，但**可预期** | 最低 | 便宜链路；或作为兜底 |
| **步级断点续跑** | 中 | 中 | 昂贵链路：多跳检索、工具链、**批量入库** |
| **依赖图增量续跑** | 最低 | 高 | 分支多、命中集大（多跳检索规模化后） |

**建议路径**：先做"整链重跑 + 入库场景的内容哈希跳过"（几乎零成本，收益立刻），再上步级续跑，脏传播留到多跳检索真的落地之后。

### 7. 与 L1 / L2 的关系：它不是新的一层

**`rag_task_step` 不是记忆层，它是一张"运行态表"。** 区别在于读者：

| | 面向 | 内容 | 生命周期 |
|---|---|---|---|
| L1 `chat_message` | **人**（用户/审计看"说了什么"） | 用户原话 + 答案 + 引文指针 | 受留存期约束，可能数年 |
| `rag_task_step` | **机器**（恢复时看"算到哪了"） | 中间产物、输入哈希、成本 | 任务结束后即可清理（短 TTL） |

**不要合并成一张表**——生命周期差两个数量级，合并后要么撑爆存储，要么留不住审计所需的 L1。两者通过 `trace_id` / `turn_no` 关联即可。

---

## 六、落地路线图

排序依据沿用 RAG-04 §一 的规则（亮点 + 难度 − 风险），但**阶段 0 是例外**——它不炫，但它是所有后续阶段的前置，且能立刻解掉一个已挂账的待做项。

| 阶段 | 交付物 | 依赖 | 解锁 / 可讲亮点 |
|---|---|---|---|
| **0** ✅ | **L1 消息流水**：`chat_message` 表 + `MemoryAdvisor`（读历史 + 写回） | 无 | 为 RAG-04 #8 的**指代消解**备好上文（历史进 `context`，**改写层尚未消费**）；可讲"为什么不用 `MessageChatMemoryAdvisor`"（§一）。**已实现**，见 RAG-06 §八 |
| **1** ✅ | **L1 权限继承**：`chat_message.clearance_level` + 读取过滤 + `MemoryWriteGuard`（只算严、不丢行） | 阶段 0 | **本项目最强差异化**（§三）。通用记忆方案全都没有权限概念，这段抄不到。**已实现**，见 RAG-06 §九 |
| **2** ✅ | **L2 语义记忆**：`user_memory` + 写入管线（复述/推测判定）+ TTL + supersede + **`memory_change_log`** | 阶段 1 | 与 `DocumentVersionResolver` 抽象合并（§四），"制度版本 vs 记忆版本"同一套观。**已实现**，见 RAG-06 §十；交付物按实际收敛为**跨会话事实**这一层（不含可查/可删接口与巡检，均归阶段 4） |
| **3a** | **入库断点续传**：内容哈希跳过 + 文件级进度表 | 无 | **改动最小、收益可量化**（省重复 embedding 的钱）。`IngestManifestLoader` 扩成 checkpoint（§五 场景 3） |
| **3b** | **步级断点续传**：`rag_task_run` / `rag_task_step` + 恢复点算法 | 阶段 3a | §五 §5.3 的算法；"权限变了检索结果就作废"这一条通用框架没有 |
| **4** | **坏记忆巡检 + 记忆管理接口** | 阶段 2 | 引用已废止制度的记忆自动失效（§四 巡检①）；可查可删是合规项 |

**关于阶段 0 的 advisor 顺序**（很容易写错，先记下约束）：

```
PermissionAdvisor        ← 必须最先：其余 advisor 都要读它产出的信封
  └→ MemoryAdvisor       ← 必须在其后（要信封做权限过滤）
                         ← 必须早于 RetrievalAdvisor（检索前要拿历史做指代消解）
       └→ RetrievalAdvisor
            └→ NumericGuardrailAdvisor   ← 现有，最内层
```

**已核实的现有值**（读源码确认，order 语义见 RAG-06 §十二）：

| Advisor | `getOrder()` |
|---|---|
| `PermissionAdvisor` | `Ordered.HIGHEST_PRECEDENCE`（= `Integer.MIN_VALUE`） |
| `MemoryAdvisor` | `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER`（= `HIGHEST_PRECEDENCE + 1000`）—— 阶段 0 已落地 |
| `RetrievalAdvisor` | `0` |
| `NumericGuardrailAdvisor` | `10` |

所以 `MemoryAdvisor` 取 **`Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER`（= `HIGHEST_PRECEDENCE + 1000`）即可** —— 正好落在 Permission 之后、Retrieval 之前，**不必自己编数字**。

> ⚠️ **这个位置有两条理由叠加，不能动：**
> 1. **功能理由**：检索前要拿到历史，才能做指代消解（本阶段要解锁的 #8）。
> 2. **安全理由**：一旦挪到 `RetrievalAdvisor` 之后，写回时看到的就已是 `augment()` 增强过的用户消息，**带原文的整段资料会被写进历史** —— 见 §一 的 order 表。这条比第 1 条严重得多。
>    （原书写的是 `MessageChatMemoryAdvisor.before()`；阶段 0 落地的是我们自己的 `MemoryAdvisor`，
>    同一条约束逐字适用。已实现的那版在写回时读的是**未被增强**的那份用户消息，
>    并且有一条断言把这一点锁住了 —— 见 RAG-06 §八。）

---

### 阶段 1 的范围修正（实现时确认）

本节原把阶段 1 写成「`MemoryWriteGuard` + 记忆过滤 + `memory_change_log`」，**实现时收敛为「L1 权限继承」**，
`memory_change_log` 移到阶段 2。理由是归属：`memory_change_log` 的 `action` 枚举
（`SUPERSEDE` / `EXPIRE` / `STALE`）与它指向的 `memory_id` **全是 L2 `user_memory` 的东西**，
而 L2 表本身排在阶段 2 —— 阶段 1 里没有任何东西能产生这些动作，先建表只会得到一张**永远是空的**日志表。
**修正的是交付物的归属阶段，不是设计本身**，§四 那一节不用改。

---

## 七、明确不做的（避免过度设计）

| 不做 | 理由 |
|---|---|
| **不引入 Letta / Mem0 / Zep 作为运行时依赖** | 借设计，不借依赖。根因是**权限模型不兼容**：它们的记忆是无主的、全局可召回的（§三），接进来必须在外面再包一层权限过滤——等于用两套模型管同一件事，而过滤层一旦漏了就是越权。另外记忆里装的是内部对话蒸馏出的内容，多一个不受你权限模型约束的存储面，与自己设计相比没有收益 |
| **不做 L3 程序记忆** | §二已述：SOP 数量是个位数，硬编码更可控。银行场景下"模型自己学会新做法"是风险不是亮点 |
| **不开 `GLOBAL` scope 记忆** | 污染面 = 全体员工，收益只是省几毫秒 |
| **第一版不做记忆的向量化召回** | 单用户记忆量级是**几百条**，`fact_key` 结构化查询 + 关键词足够。上向量是过早优化，还会引入"语义漂移导致误召回"这个新风险源。等量级上万再上 |
| **不做记忆的无限增长** | 每用户配额（如 200 条）+ LRU 淘汰。没有配额的记忆系统最终会变成"什么都记、什么都记不准" |
| **不把 L0 落库** | 沿用会话表 v2 的决策（§二 L0）：避免 PII 重复落库。落库前先想清楚"存了之后谁有权读、留存多久" |

---

## 八、复习自测题

> 能答上来才算真懂。答案都在正文里，**建议先合上文档答一遍**。

**记忆分层**

1. 记忆分层的依据是哪三个问题？为什么"内存/Redis/MySQL/向量库"不算分层依据？
2. L0 为什么刻意不落库？不落库导致无法复现"模型当时看到了什么"，靠什么补偿？
3. L1 只存答案和指针、不存检索原文，这一个取舍同时解决了哪三个问题？
4. L4（领域知识）为什么不算"记忆"？把它和 L1/L2 混为一层的后果是什么？
5. L5 权限画像为什么明确排除在记忆之外？"记忆可以过期，权限不能过期"在代码上体现为哪一行？

**权限继承**

6. 为什么不能把 `MessageChatMemoryAdvisor` 直接挂到 `BankAssistant` 上？说出至少三个后果。
7. 记忆的密级为什么取 `max(该轮引用资料的密级)`，而不是"写入者的密级"？**进一步**：`min(写入者密级, 资料密级)` 为什么在干净数据上是个**空操作**？它唯一能生效的那个方向，为什么恰好是危险的方向？
8. `auth_version` 变化时，为什么不批量删除存量记忆？那 `user_memory` 里那个 `perm_auth_version` 快照是干什么用的？它和 `clearance_level` 的分工是什么？
9. 记忆过滤为什么必须复用 `buildAccessFilter` 而不是另写一套？
10. L1 的密级过滤为什么放在 Java 侧、在 SQL 的 `LIMIT` **之后**？写进 `WHERE` 看起来更"干净"，会让"降级"发生什么？
11. 写入门禁在 L1 上只能"算得更严"、不能"拒绝写入"，在 L2 上却能拒绝。两者的差别是什么？

**坏记忆**

12. 五类坏记忆分别是什么？各自的对应机制是什么？
13. "模型只能复述、不能推测"这条规则，靠什么字段组合来验证？它和你已有的引用归因（RAG-06 §四）是不是同一套方法论？
14. 双时态（supersede）和你 `DocumentVersionResolver` 做的版本收敛，本质上是同一件事吗？差异在哪？
15. 三个巡检项里，哪一个是你相对通用记忆方案的独有优势？为什么？
16. 用户可查/可删为什么是合规项而非功能项？这个接口最容易写错的地方在哪？

**断点续传**

17. 为什么"从最后一个成功的步骤恢复"是错的？举一个会因此越权的例子。
18. 恢复点算法为什么是**从前往后**扫第一个失效步骤，而不是从后往前找最后一个有效步骤？（提示：两者等价吗？哪个更早短路？）
19. `stillValid` 的五个条件里，哪两个是通用 agent 框架没有的？为什么它们和银行场景强相关？
20. 为什么"便宜步骤不做有效性校验、一律重跑"反而是对的？
21. 崩溃发生在"副作用之后、确认之前"会怎样？哪一类步骤必须强制 `idempotency_key`？
22. 为什么 checkpoint 边界要落在便宜段之后？画出"对"和"错"两种管线排布。
23. `rag_task_step` 为什么不能和 `chat_message` 合并成一张表？

---

## 附录 A · DDL 草案

> **草案，不是最终 DDL。** 落库前需要和 `sql/rag_assistant_session.sql` 对齐命名风格，并确认 MySQL 版本的 JSON / 生成列支持。风格沿用现有会话表：中文列注释 + 显式写明"为什么删/为什么不存"。
>
> **落地状态（2026-09-26）**：
>
> | 附录 | 落地版 | 状态 |
> |---|---|---|
> | A.1 `chat_message` | `sql/chat_message.sql` | ✅ 阶段 0/1 |
> | A.2 `user_memory` | `sql/user_memory.sql` | ✅ 阶段 2（4 处偏离，见该节） |
> | A.3 `memory_change_log` | `sql/memory_change_log.sql` | ✅ 阶段 2（4 处偏离，见该节） |
> | A.4 `session_summary` | `sql/session_summary.sql` | ✅ §5.5（**本附录首写**：原文只有散文、没有 DDL） |
> | A.5 `rag_task_run` / `rag_task_step` | —— | ⏳ 阶段 3b，仍是草案 |
>
> ⚠️ 各 DDL 文件头都写明：**Windows 的 `mysql` CLI 要用 `--default-character-set=utf8mb4`**，
> 否则中文注释会按 GBK 存成乱码（表照样建得出来，`SHOW CREATE TABLE` 才发现）。

### A.1 `chat_message` —— L1 情景记忆

```sql
CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `session_id`        CHAR(36)        NOT NULL COMMENT '所属会话',
    `user_id`           VARCHAR(32)     NOT NULL COMMENT '归属人(读取过滤用, 冗余存储避免 join)',
    `turn_no`           INT UNSIGNED    NOT NULL COMMENT '轮次序号(恢复锚点, 会话内从 1 递增)',
    `role`              VARCHAR(16)     NOT NULL COMMENT 'user / assistant',

    `user_message`      TEXT            DEFAULT NULL COMMENT '用户原话(turn 的 user 侧)',
    -- ⚠️ 只存"最终答案", 绝不存 RetrievalAdvisor.augment() 渲染出的带原文的 prompt。
    --    原文已在 PGVector, 再抄一份 = 凭空多一个泄露面(沿用会话表 v2 砍 context_json 的决策)。
    `answer_text`       TEXT            DEFAULT NULL COMMENT '最终答案全文(assistant 侧)',
    -- 存指针不存内容: 回看时按"当前"权限重新取原文, 取不到就如实告知无权查看。
    -- 必须带 doc_version 快照: 原文可能已出新版, 展示时要标注"该轮引用 v3, 当前已有 v5"。
    `cited_chunk_refs`  JSON            DEFAULT NULL COMMENT '[{doc_id, doc_version, chunk_id}] 引文指针, 非原文',

    `answer_decision`   VARCHAR(24)     DEFAULT NULL COMMENT '该轮结论(对应 RetrievalAdvisor.Decision): PROCEED/REFUSED_NO_DOC/...',
    `refusal_reason`    VARCHAR(255)    DEFAULT NULL COMMENT '拒答原因(拒答也是结论, 要能回看)',
    `biz_date`          DATE            DEFAULT NULL COMMENT '该轮业务日期(时效判定的上下文)',

    -- 权限双列, 分工别混:
    --   clearance_level  = 该轮"内容"有多敏感 → 读取判定用(见 §三)
    --   perm_auth_version= 该轮"在什么权限下产生" → 仅审计
    -- ⚠️ 读取判定必须用本轮实况信封(记忆可以过期, 权限不能过期 —— 见 §二 L5)。
    -- ⚠️ DEFAULT 取 5(最高密级) 而非 1, 一个默认值同时管住两件事:
    --    ① 迁移: ALTER 时既有行一律回填 5 → 旧历史谁都读不到(真实密级已无法回溯判定, 宁可读不到);
    --    ② fail-closed: 将来若有别的写入路径漏了这列, 漏掉的那行是"谁都读不到"而不是"谁都能读到"。
    `clearance_level`   TINYINT UNSIGNED NOT NULL DEFAULT 5
        COMMENT '该轮内容可能反映的最高密级(读取判定用; 无引用资料时=会话密级)',
    `perm_auth_version` BIGINT          NOT NULL DEFAULT 0 COMMENT '该轮权限版本(审计用, 不参与读取过滤)',

    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `expires_at`        DATETIME        DEFAULT NULL COMMENT '过期时间(留存期, 到期归档/销毁)',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_turn` (`session_id`, `turn_no`, `role`),
    KEY `idx_user_created` (`user_id`, `created_at`),
    KEY `idx_session_created` (`session_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '银行内部RAG助手-情景记忆(消息流水, 只存结论与指针)';
```

> ✅ **A.1 已不是草案**：它就是 `sql/chat_message.sql` 的实际内容（阶段 0 + 阶段 1 已落地）。
> 已有旧表的升级脚本以**注释形式**附在该文件末尾（本仓库没有 Flyway，沿用"手工 DDL + 文末注释 ALTER"的惯例）。
> 下面 A.2 起仍是草案，阶段 2 才落。

### A.2 `user_memory` —— L2 语义记忆

```sql
CREATE TABLE IF NOT EXISTS `user_memory` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `owner_id`          VARCHAR(32)     NOT NULL COMMENT '归属人工号(scope=USER 时的读取过滤主键)',
    `scope`             VARCHAR(16)     NOT NULL DEFAULT 'USER' COMMENT '可见范围: USER(仅本人)/DEPT(同 org_path)。⚠️ 不开 GLOBAL(§七)',

    `fact_key`          VARCHAR(64)     NOT NULL COMMENT '事实键(如 常用机构/关注条线), 同一 key 下只应有一条 valid 状态记录',
    `fact_value`        VARCHAR(512)    NOT NULL COMMENT '事实值',

    -- 密级继承: = max(该轮引用资料的 doc_level)。⚠️ 绝不能简化为"写入者密级"(§三 D2:
    -- 用户有权在一次问答里看到密级4的结论, 不等于有权长期持有这条记忆)。
    -- ⚠️ 不要写成 min(写入者密级, 资料密级): passesGate 已保证"资料密级 <= 写入者密级",
    --    于是 min 恒等于后者 = 空操作; 而它唯一能生效的方向恰恰是把高密级内容标低(§三 勘误)。
    -- DEFAULT 5(最高密级) 的理由与 chat_message 同(见 A.1): 迁移回填 + 漏写 fail-closed。
    `clearance_level`   TINYINT UNSIGNED NOT NULL DEFAULT 5 COMMENT '该条记忆的密级上限(取更严的一方)',
    `org_path`          VARCHAR(255)    DEFAULT NULL COMMENT 'scope=DEPT 时的可见组织路径',

    -- 无源不写: 挡幻觉记忆的第一道闸。溯源不到 L1 的记忆不允许进 L2。
    `source_session_id` CHAR(36)        NOT NULL COMMENT '来源会话(必填, 无源不写)',
    `source_turn_no`    INT UNSIGNED    NOT NULL COMMENT '来源轮次(必填)',
    `confidence`        DECIMAL(3,2)    DEFAULT NULL COMMENT '置信度(供人工复核排序)',

    -- 双时态(supersede 而非 delete): 旧事实标 valid_to + superseded_by, 历史可回溯。
    -- 与 DocumentVersionResolver 对制度做的"版本收敛+标注取代关系"是同一套观(§四)。
    `valid_from`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '何时开始为真',
    `valid_to`          DATETIME        DEFAULT NULL COMMENT '何时不再为真(NULL=当前有效)',
    `superseded_by`     BIGINT UNSIGNED DEFAULT NULL COMMENT '被哪条新记忆取代',

    -- 时效事实必填 expires_at(如"当前利率")。更稳的做法是存指针不存值(§四 TTL)。
    `expires_at`        DATETIME        DEFAULT NULL COMMENT '过期时间(时效事实必填)',
    `ref_doc_id`        VARCHAR(64)     DEFAULT NULL COMMENT '指向制度文档(存指针不存值, 巡检①用)',

    `status`            VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PENDING(待确认)/STALE(所依制度已废止)/USER_DELETED/EXPIRED',
    `last_hit_at`       DATETIME        DEFAULT NULL COMMENT '最近命中时间(巡检③ LRU 归档用)',

    -- ⚠️ 仅审计: 记录"这条记忆在什么权限下产生", 不参与读取判定(§三 D3)。
    `perm_auth_version` BIGINT          NOT NULL DEFAULT 0 COMMENT '写入时权限版本(审计用)',

    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (`id`),
    KEY `idx_owner_status` (`owner_id`, `status`),
    KEY `idx_fact_key` (`owner_id`, `fact_key`, `valid_to`),
    KEY `idx_ref_doc` (`ref_doc_id`),
    KEY `idx_scope_org` (`scope`, `org_path`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '银行内部RAG助手-语义记忆(蒸馏事实, 带密级继承与双时态)';
```

> ### ✅ 已落地（2026-09-26）—— 落地版是 `sql/user_memory.sql`，四处偏离
>
> | # | 偏离 | 为什么**必须**这么改 |
> |---|---|---|
> | 1 | 新增 **`fact_uid CHAR(36)`** 业务键 + `uk_fact_uid`；`superseded_by` 由 `BIGINT`（指向新记忆的 **id**）改成 `CHAR(36)`（指向后继的 **fact_uid**） | 上面那行 `superseded_by` 按原文**实现不了**：本项目有一条写死的约定——**物理主键不进 record**（`ChatMessageDao.insertTurn` 的注释：「与会话表一致，物理主键 `id` 不进 record，业务主键是 `session_id + turn_no`」），而 record 不可变 ⇒ **插入前拿不到生成键**。先例：`Document.getId()` 用 `nameUUIDFromBytes` 派生确定性 UUID。附带好处：supersede / 续期全部按业务键定位，不依赖物理 id |
> | 2 | 新增**生成列 `active_key`** + `UNIQUE KEY uk_owner_active (owner_id, scope, active_key)` | 把「同一 `(owner, scope, fact_key)` 至多一条有效行」**从 Java 层不变量落到 DB 层**。⚠️ 唯一索引对 NULL **不生效**（多个 NULL 互不相等），所以必须先用生成列把「有效」压成非 NULL 值才约束得住。收益：阶段 4 巡检② 从「报警」降级成「不该发生」 |
> | 3 | `fact_key` 存 **ASCII code**（`business_line`）而不是中文标签（`业务条线`） | 中文标签活在**提示词**里，一旦有人把「关注条线」改成「业务条线」，旧行立刻变孤儿，而 supersede 会**静默失效**（不报错，只是永远替代不上）。ASCII code 把「稳定标识」与「展示文本」解耦，展示文本由 `FactKey.label()` 提供（词表唯一真相源） |
> | 4 | `confidence` 本阶段**不参与任何判定**（只留痕供人工复核排序） | 模型自报的置信度**不是校准信号**，拿它当门禁是**装饰性的安全性** |
>
> 另外两处**照原文执行**、但值得指出它们是"闸门"而不是"字段"：
> `source_session_id` / `source_turn_no` 的 **`NOT NULL`** 就是「**无源不写**」的 DB 兜底；
> `clearance_level` 的 **`DEFAULT 5`** 与 `chat_message` 同一个理由（迁移回填 + 漏写 fail-closed）。
>
> ⚠️ **执行方式**（踩过的坑，写进了 DDL 文件头）：Windows 的 `mysql` CLI 默认
> `character_set_client=gbk`，直接 `< sql/user_memory.sql` 会把 UTF-8 中文注释存成乱码
> —— **表照样建得出来**，但 `SHOW CREATE TABLE` 里注释全是乱码，而本项目的 DDL 头注释
> 正是设计文档的一部分。必须 `mysql -u root --default-character-set=utf8mb4 < ...`。

### A.3 `memory_change_log` —— 坏记忆审计

```sql
CREATE TABLE IF NOT EXISTS `memory_change_log` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `memory_id`     BIGINT UNSIGNED NOT NULL COMMENT '被变更的记忆',
    `action`        VARCHAR(24)     NOT NULL COMMENT 'WRITE/SUPERSEDE/EXPIRE/STALE/USER_DELETE/GUARD_REJECT',
    `before_json`   JSON            DEFAULT NULL COMMENT '变更前',
    `after_json`    JSON            DEFAULT NULL COMMENT '变更后',

    -- 谁改的: 用户(USER)/模型自动(AUTO)/巡检任务(SWEEP)/管理员(ADMIN)。
    -- 必须区分 —— "模型自己改的"在审计里和一回事 (合规要点)。
    `actor_type`    VARCHAR(16)     NOT NULL COMMENT 'USER/AUTO/SWEEP/ADMIN',
    `actor_id`      VARCHAR(32)     DEFAULT NULL COMMENT '操作人工号(actor_type=USER/ADMIN 时)',
    `reason`        VARCHAR(255)    DEFAULT NULL COMMENT '变更原因',

    -- 哈希链: 与 RAG-04 #13「审计表 + 哈希链」同一机制同一理由(防篡改)。建议复用同一个实现。
    `prev_hash`     CHAR(64)        DEFAULT NULL COMMENT '上一条的 hash',
    `hash`          CHAR(64)        NOT NULL COMMENT '本条内容的 SHA-256(含 prev_hash)',

    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_memory` (`memory_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '银行内部RAG助手-记忆变更审计(append-only + 哈希链)';
```

> ### ✅ 已落地（2026-09-26）—— 落地版是 `sql/memory_change_log.sql`，四处偏离
>
> | # | 偏离 | 为什么 |
> |---|---|---|
> | 1 | `memory_id BIGINT NOT NULL` → **`fact_uid CHAR(36) DEFAULT NULL`** | 同上表：物理主键不进 record，插入前拿不到。改 `DEFAULT NULL` 是因为 **`GUARD_REJECT`（内容红线拒绝）没有记忆条目**，只能为 NULL |
> | 2 | **新增 `entity_type`**（`FACT` / `SUMMARY`） | 摘要的写入/取代也进这张表（§5.5）。**一张审计表带判别列，好过两张几乎相同的表** —— 也让 RAG-04 #13 将来能直接复用。落地的枚举是 `MemoryEntityType`，`payload()` 里**包含** `entity_type`（这一条是相对本文的**修正**：漏了它，两个实体的同 uid 行会哈希相同） |
> | 3 | 哈希链由 **`org.agent.audit.AuditHashChain`** 提供（不在 DAO 里内联） | #13 要复用它，所以刻意不依赖记忆模块。四个坑（分隔符 / 不含 `id` / `createdAt` 由 Java 传 / **JSON 规范化后哈希**）见 RAG-06 §十 |
> | 4 | `action` 本阶段只用 **`WRITE` / `SUPERSEDE` / `GUARD_REJECT`** 三个 | `EXPIRE` / `STALE` / `USER_DELETE` 属阶段 4（没有 sweeper 与管理接口，产生不了这些动作）。⚠️ 且 **`GUARD_REJECT` 只记内容红线那一种**：`NOT_GROUNDED` / `TIER_CONFIRM` 是例行质量过滤且**没有记忆条目**，写进去只会把审计表灌满「从未存在过的记忆」 |
>
> **落地的两条自律**（本文没写，实现时定的）：
> ① **`GUARD_REJECT` 的 `after_json` 只放 `{factKey, pattern, valueLength}`，绝不回写被拒的值** ——
> 否则等于把凭证又抄一遍，**审计本身成了泄露面**；
> ② 每次追加后把 **head hash 打一条 INFO 日志** —— 链只在库内串行化、有写权限者可整条重写，
> 所以它只能 **tamper-evident（可发现）**、不是 **tamper-proof（防篡改）**；
> 这条日志等于一个**外部锚点**，是低成本补偿。

### A.4 `session_summary` —— 会话摘要/压缩（**本文原来只有散文，本附录首写**）

> §5.5 只描述了「恢复给模型的上下文 = 会话摘要 + 最近 N 轮原话」，
> 没有给 DDL —— 因为它当时被归在阶段 3b。阶段 2 顺手把它做了（它和 L2 共用双时态与审计那一套），
> 所以补在这里。落地版是 `sql/session_summary.sql`。

```sql
CREATE TABLE IF NOT EXISTS `session_summary` (
    `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键(不进 record)',
    `summary_uid`           CHAR(36)        NOT NULL COMMENT '业务键(Java 生成 UUID)',
    `session_id`            CHAR(36)        NOT NULL COMMENT '所属会话(读取窗口的第一道条件)',
    `user_id`               VARCHAR(32)     NOT NULL COMMENT '归属人工号(归属复核用)',

    -- 覆盖区间 [covers_from_turn_no, covers_through_turn_no)：左闭右开，through = 被折叠的最后一轮 + 1
    -- ⇒ 与 L1 窗口**无缝且不重叠**："每一轮要么在窗口里、要么在摘要里、要么被密级过滤掉"
    `covers_from_turn_no`   INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '覆盖起始轮次(含)',
    `covers_through_turn_no` INT UNSIGNED   NOT NULL COMMENT '已摘要到第几轮(**不含**该轮)',
    `summary_text`          VARCHAR(2000)   NOT NULL COMMENT '摘要正文：**只含定位信息**，禁止出现金额/比例/期限',
    `source_turn_count`     INT UNSIGNED    NOT NULL COMMENT '本次折叠了几轮(审计用; 也是"摘要膨胀"的观察指标)',

    -- ⚠️ 摘要**必须**继承密级：一份摘要顶好几轮，不继承的话它是一条比 L1 旁路更宽的路(§三)
    -- = max(被摘要轮次的 clearance_level)，由 Java 算，**模型无权输出**
    -- DEFAULT 5(最高密级) 与 chat_message / user_memory 同一套理由
    `clearance_level`       TINYINT UNSIGNED NOT NULL DEFAULT 5 COMMENT '被摘要轮次的最高密级(整份摘要按此过滤)',

    -- 版本化(append-only + supersede)，**不原地更新**：
    -- L0"不落库"的代价原文是"无法事后复现当时模型看到了什么"，而摘要正是被注入模型的东西。
    -- 第 5 轮"读 v1 注入"与"压出 v2 取代 v1"发生在同一轮、先读后写 —— 版本化才保得住可复现。
    `valid_from`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `valid_to`              DATETIME        DEFAULT NULL COMMENT '何时不再为真(NULL = 当前有效)',
    `superseded_by`         CHAR(36)        DEFAULT NULL COMMENT '被哪一版新摘要取代',

    `perm_auth_version`     BIGINT          NOT NULL DEFAULT 0 COMMENT '仅审计, 不参与判定',
    `expires_at`            DATETIME        DEFAULT NULL COMMENT '留存到期(与 chat_message 同一个 retention-days 口径)',
    `created_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- 与 user_memory.active_key 同一手法：唯一索引对 NULL 不生效，故压成非 NULL 才能约束
    `active_session_id`     CHAR(36) GENERATED ALWAYS AS (IF(`valid_to` IS NULL, `session_id`, NULL)) VIRTUAL
        COMMENT '仅用于唯一约束: 有效行=session_id, 历史行=NULL',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_summary_uid` (`summary_uid`),
    UNIQUE KEY `uk_session_active` (`active_session_id`),   -- 一个会话至多一版有效摘要
    KEY `idx_user` (`user_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '银行内部RAG助手-会话摘要(滑出窗口的定位块, 带密级继承与版本化)';
```

**两处与 §5.5 的偏离**（都在 RAG-06 §十一 展开）：

1. **触发**：主触发是「按轮次滑出窗口」，不是本文写的 token 规则（token 规则降为次触发）。
   理由见 §5.5 里补的那段。
2. **「只做定位」必须机械化**，不能只写在提示词里：`NumericExtractor.extract(summaryText)`
   **必须为空**，违规**整份丢弃**（不是截断、不是抹掉数字）。且这道终审落在
   **`SessionSummaryService`（策略层）**，不是 `LlmSummaryWriter` —— 否则它只是**某个实现的属性**，
   打桩就失效了。

### A.5 `rag_task_run` / `rag_task_step` —— 断点续传（**未落地**，仍是阶段 3b 的方案）

```sql
CREATE TABLE IF NOT EXISTS `rag_task_run` (
    `task_id`           CHAR(36)        NOT NULL COMMENT '任务实例 ID',
    `session_id`        CHAR(36)        DEFAULT NULL COMMENT '所属会话(会话级断点用)',
    `trace_id`          CHAR(32)        DEFAULT NULL COMMENT '关联审计 trace',
    `task_type`         VARCHAR(32)     NOT NULL COMMENT 'CHAT_TURN/MULTI_HOP_RETRIEVAL/INGEST_BATCH/TOOL_CHAIN',
    `status`            VARCHAR(16)     NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCEEDED/FAILED/SUSPENDED',

    `current_step_no`   INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '已执行到的步骤',
    `total_steps`       INT UNSIGNED    DEFAULT NULL COMMENT '总步骤数(未知时为 NULL)',
    -- ⚠️ 恢复时必须原样带回 biz_date, 否则时效判定会跨日漂移 —— 同一个问题的答案会变。
    `biz_date`          DATE            DEFAULT NULL COMMENT '业务日期(恢复时原样带回)',
    `perm_auth_version` BIGINT          NOT NULL DEFAULT 0 COMMENT '权限快照(恢复时比对, 变了则检索步骤全部作废)',
    `access_filter_hash` CHAR(64)       DEFAULT NULL COMMENT '检索过滤条件哈希(同上)',

    `resume_count`      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '已恢复次数(防恢复风暴, 排查用)',
    `last_resumed_at`   DATETIME        DEFAULT NULL,
    `error_code`        VARCHAR(64)     DEFAULT NULL COMMENT '失败原因',
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at`       DATETIME        DEFAULT NULL,

    PRIMARY KEY (`task_id`),
    KEY `idx_session` (`session_id`, `created_at`),
    KEY `idx_status` (`status`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '银行内部RAG助手-任务运行实例(断点续传)';

CREATE TABLE IF NOT EXISTS `rag_task_step` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `task_id`        CHAR(36)        NOT NULL,
    `step_no`        INT UNSIGNED    NOT NULL COMMENT '步骤序号',
    `step_type`      VARCHAR(24)     NOT NULL COMMENT 'QUERY_EXPAND/EMBED/RETRIEVE/RERANK/LLM_CALL/TOOL_CALL/PERSIST',

    `input_hash`     CHAR(64)        DEFAULT NULL COMMENT '输入哈希(重放判据①)',
    `input_json`     JSON            DEFAULT NULL COMMENT '输入快照',
    -- 存指针不存内容: 检索步骤的 output 存 chunk_id 列表, 不存 chunk 正文(§五 §1)。
    `output_json`    JSON            DEFAULT NULL COMMENT '输出快照(存指针)',
    `output_hash`    CHAR(64)        DEFAULT NULL,

    `status`         VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/SKIPPED',
    `error_code`     VARCHAR(64)     DEFAULT NULL,
    `retry_count`    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '局部重试次数(失败步可局部重试, 不整链重跑)',

    -- 有效性校验依据(§五 §5.3): ② 时效 ③ 权限 ④ 文档版本 —— 后两条是本项目独有, 通用框架的 checkpoint 只做①
    `doc_version_refs` JSON          DEFAULT NULL COMMENT '[{doc_id, doc_version}] 该步依赖的文档版本快照',
    `perm_auth_version` BIGINT       DEFAULT NULL COMMENT '该步执行时的权限版本',
    `expires_at`     DATETIME        DEFAULT NULL COMMENT '输出的有效截止时间',
    `checkable`      TINYINT         NOT NULL DEFAULT 1 COMMENT '是否值得做有效性校验(便宜步置0: 一律重跑更省, 见规律二)',

    `deps`           JSON            DEFAULT NULL COMMENT '依赖的 step_no 数组(为脏传播预留, 第一版不实现)',
    -- 副作用步(发通知/建工单/派复核)必须强制幂等键: 崩溃在"副作用之后、确认之前"时防重复执行。
    `side_effect`    TINYINT         NOT NULL DEFAULT 0 COMMENT '是否有外部副作用',
    `idempotency_key` VARCHAR(64)    DEFAULT NULL COMMENT '幂等键(side_effect=1 时必填)',

    `cost_ms`        INT UNSIGNED    DEFAULT NULL COMMENT '耗时(恢复点选择的成本依据)',
    `cost_tokens`    INT UNSIGNED    DEFAULT NULL COMMENT 'token 消耗',
    `started_at`     DATETIME        DEFAULT NULL,
    `finished_at`    DATETIME        DEFAULT NULL,

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_step` (`task_id`, `step_no`),
    KEY `idx_task_status` (`task_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '银行内部RAG助手-任务步骤(断点续传; 短 TTL, 任务结束后可清理)';
```

---

## 附录 B · 参考实现索引与学习路径

> **这个附录回答"记忆这块完全没概念，从哪开始"。**
> 排序原则：**按本项目的实现顺序排，不按项目热度排**。每一项都标注"什么时候读"——提前读会看不懂，因为不知道要解决什么问题。
> 读的顺序本身就是学习路径：**先搞清楚要解决什么问题，再去看别人怎么解的。**

### 第一梯队：动手前必读（读源码，不是读文档）

| # | 资源 | 读什么（要具体） | 学什么 | 服务本文 | 何时读 |
|---|---|---|---|---|---|
| 1 | **Spring AI 自己的 `MessageChatMemoryAdvisor` + `MessageWindowChatMemory`** | 你已经依赖了 `spring-ai-starter-model-openai`，源码在本地 maven 仓库里。读它的 `adviseCall` 怎么把历史拼进 prompt，以及 `MessageWindowChatMemory` 的窗口裁剪逻辑 | **读它是为了知道为什么不能用它**（§一）。看清"存的是已渲染的完整消息"这件事发生在哪一行 —— 那一刻你会立刻明白旁路是怎么形成的 | §一 | **阶段 0 之前，第一件事** |
| 2 | **Spring Batch 的元数据表设计** | `BATCH_JOB_EXECUTION` / `BATCH_STEP_EXECUTION` / `BATCH_STEP_EXECUTION_CONTEXT` 三张表的结构，以及它的 `restart` 机制（`JobRepository` + `ExecutionContext`） | **这是 §五 在 Java 生态里的工业级参考答案，而且是你同技术栈**。"任务-步骤-上下文"的三层元数据模型、`status` 状态机、`restart` 从失败步续跑 —— 你的 `rag_task_run` / `rag_task_step` 基本就是这个模型的领域特化版。**先看它，能省掉自己摸索状态机的时间** | §五 §5.2 | **阶段 3a 之前**（想先做入库续传的话，更早读） |
| 3 | **Claude Agent Skills 的渐进式披露** | Skill 的 `description` 与正文分离的设计：平时只有一行描述进 context，命中才展开全文 | **上下文是稀缺资源，要按需加载而不是全量注入**。这个思路可以直接迁移到"制度摘要先进 context、细节按需检索" | §七、§二 L0 | 阶段 0 之前 |

> **为什么第 1 项排第一**：整个设计的起点是"为什么现有方案不行"。先把这个想透，后面所有取舍才有依据。很多人一上来就读 MemGPT，结果学了一堆机制却不知道自己项目哪里用得上。

### 第二梯队：做权限继承和写作管线时读

| # | 资源 | 读什么 | 学什么 | 服务本文 |
|---|---|---|---|---|
| 4 | **Mem0**（`github.com/mem0ai/mem0`） | 重点读**写入管线**，不是读取：怎么从对话里 LLM 抽事实 → 去重 → 和已有记忆比对后决定 `ADD`/`UPDATE`/`DELETE` | **写入时的加工比读取时的技巧重要**。它的"先查有没有、再决定加还是改"这个流程，就是你 `MemoryWriteGuard` 要护住的那条路径 | §四 |
| 5 | **Zep / Graphiti**（`github.com/getzep/graphiti`） | **双时态（bi-temporal）** 的实现：`valid_from`/`valid_to`（何时为真）与 `created_at`（何时被记录）两个时间轴怎么分开存、查询时怎么选 | **supersede 而非 delete** 的具体做法。§四 说它和你的 `DocumentVersionResolver` 是同一套观 —— 读完这个你能确认这个判断，也能看出差异 | §四 |
| 6 | **LangGraph 的 checkpointer / store** | `thread`（会话）/ `checkpoint`（状态快照，可回放）/ `store`（跨会话长期记忆，按 namespace 隔离）三分法 | **状态可回放**这个能力怎么设计；以及 namespace 隔离 —— 你的 `owner_id` 隔离是同一件事，但你要多一层密级 | §二、§五 |
| 7 | **Letta / MemGPT**（`github.com/letta-ai/letta`） | 先读 **MemGPT 论文**（比读源码快），重点看它**为什么**要分层、以及"上下文当虚拟内存管理"的论证；再扫一眼 `core memory` 的自编辑工具 | **分层的动机**（不是机制）。另外记住官方那句忠告：**"如果记忆对你只是对文档做检索，别上 Letta，一个数据库加明确的检索策略更好审查"** —— 这句话本身就是对"该不该自建"的判据 | §二 |

### 第三梯队：做完之后复盘 / 补理论

| # | 资源 | 学什么 | 为什么值得 |
|---|---|---|---|
| 8 | **Generative Agents**（`github.com/joonspk-research/generative_agents`） | 记忆召回打分公式：`score = relevance(向量相似) × recency(时间衰减) × importance(LLM 打分)` | **"分层之后怎么召回"的最经典答案**，代码量很小。你已有 `RrfFusion` 做多路融合，加一个 recency 乘子是很自然的延伸 |
| 9 | **Temporal**（或任何 durable execution 引擎） | activity 的幂等 + 重试 + "副作用之后崩溃"的处理 | §五 §5.4 那个坑的工业级解法。Java 生态里概念相通 |
| 10 | **认知科学的那四个术语**（搜 `working memory / episodic / semantic / procedural memory`） | 术语来源：Tulving 的记忆分类 | **面试时能说清术语来源，和只会说"我分了四层"是两个层次**。也方便你去搜更多资料 —— 关键词对了，能找到的东西完全不同 |

### 从零开始的最小路径（如果只做一件事）

```
1. 读 Spring AI 的 MessageChatMemoryAdvisor 源码     ← 30 分钟，知道"为什么不能用现成的"
2. 读 Spring Batch 的三张元数据表                    ← 1 小时，拿到断点续传的成熟模型
3. 读 MemGPT 论文的前半部分（动机部分）              ← 1 小时，建立分层的概念框架
```

**三个读完，你就能把本文的所有取舍重新推一遍**——而且能指出本文哪里做得不够。这比读完十个开源项目更有用。

### 一个学习方法的建议

这些项目（MemGPT / Mem0 / Zep）**都没有权限概念**。所以读它们的时候，**不要问"这个能不能用"，要问"它还缺什么"**：

| 它们的假设 | 银行场景下的现实 | 你要补的 |
|---|---|---|
| 记忆是属于用户的，按 owner 过滤够了 | 用户有权读≠有权长期持有结论 | 密级继承（§三 D2） |
| 记忆一旦写入，长期有效 | 制度会废止，结论会过期 | 与 `DocumentVersionResolver` 联动（§四 巡检①） |
| 记忆是优化项，错了就错了 | 记忆错了是合规事件 | 审计哈希链 + 可查可删（§四） |
| 上下文是唯一成本 | 还有越权成本和审计成本 | 存指针不存内容（§一基调） |

**这四条差距，就是本项目在记忆这块的全部差异化。** 它们不是"更好的实现"，是"不同的问题定义"——面试时讲这个，比讲"我用了 Mem0"有价值得多。

---

### 理解校正记录（复习用 · 2026-09-23）

> **来历**：这份记录产生于做「第一梯队 #1」——读 `MessageChatMemoryAdvisor` 源码时，先口述了一遍"为什么不能用它"，然后逐条校正。
> **用法**：遮住"校正"一栏，先自己说一遍，再对。**只记需要校正的，不记已经说对的。**
> **为什么值得单独留**：这些不是知识盲点，是**直觉的偏差**——而直觉偏差最难自查，因为"听起来都对"。

#### A 级 —— 会直接导致设计出错的

**A1. 把"总结（摘要）"当成解法**

| | |
|---|---|
| 原话 | "没有总结和取舍，用户的对话并不是所有的都需要记下来的" |
| 判定 | 方向对，**但"总结"这个解法在你项目里是错的** |
| 错在哪 | 摘要**有损，且破坏引用归因**。你刚做完 `NumericGuardrailAdvisor`：金额/比例/期限必须在原文里有出处。上一轮"年利率 1.35%"被摘要成"讨论了存款利率"，下一轮用户问"刚才那个利率"，模型要么答不出（体验：上一句刚说的忘了），要么编一个被拦（体验：答了又撤回） |
| 校正 | **摘要只能用来定位，不能用来承载事实。** 摘要进上下文（"上一轮聊的是 XX 制度"），数字按指针回原文取。→ §二 L1 理由④ |
| 一句话钩子 | **摘要负责"找得到"，原文负责"答得对"。** |

**A2. 按"消息"粒度决定丢弃**

| | |
|---|---|
| 原话 | "用户在让你写代码的同时问了一句不相干的事情，比如天气，这种是需要丢弃的" |
| 判定 | 结论对，**但粒度错了** |
| 错在哪 | 一条消息里可以同时有该丢的和必须记住的：「今天天气不错啊，顺便问下 XX 客户的评级是多少」——一半噪音，一半密级 4 |
| 校正 | **丢弃的单位是"事实"，不是"消息"。** 这正是 L1（消息流水）/ L2（蒸馏事实）必须分两层、而不能一层加个过滤器的理由。→ §四 写入策略 |
| 一句话钩子 | **按消息丢，会连机密一起丢。** |

**A3. 漏掉"归属"这个维度（本项目最致命）**

| | |
|---|---|
| 原话 | 三条理由：消耗 token / 没有取舍 / 该丢的没丢 |
| 判定 | **三条全对，但全是"效果问题"——漏掉了真正的命门** |
| 错在哪 | 在这个项目里 `MessageChatMemoryAdvisor` 是**合规问题**不是效果问题：答案全文（含密级 4 的结论）和引文标签（暴露"哪份制度、哪一版、哪一条"）会随历史回放，绕过 `accessFilter`，让 `SessionAuthService` 的 TOCTOU 防护失效 |
| 校正 | **效果问题可以容忍，权限问题不能。** 判断"能不能用"要先问"它是不是权限模型的旁路"。→ §一 表格 #1–#5 |
| 一句话钩子 | **检索层权限是"每次现查"的；记忆层若是"整段回放"的，它就是旁路。** |

#### B 级 —— 方向对，但机制讲不准（讲不清就等于没懂）

**B1. "这样做会比较消耗 token"**

| | |
|---|---|
| 校正 | 不是"每轮多一点"，是 **O(N²)**：第 k 轮要重发前 k 轮的全部历史。N 轮会话总输入 ≈ N² 条，**每一轮都在为前面所有轮重复付费** |
| 项目特有 | 你的答案带 `[1][2]` 引文和完整条文，**比一般对话长得多**；`MessageWindowChatMemory` 默认 `maxMessages = 20`，一轮 = 2 条（user + assistant），**所以只够 10 轮，在你这个场景可能实际只够 5–6 轮** |
| 一句话钩子 | **窗口记忆是按"平方"计费的。** |

**B2. "冗余，以免上一句用户说了什么下一句就忘记了"**

| | |
|---|---|
| 校正 | 机制不是"内容太多装不下"，是**按"条数"裁**（`MessageWindowChatMemory.java:105-124`）：一条"你好"和一段 10KB 的粘贴**占用同样一格**；超窗就从最老的开始删，`SystemMessage` 永不删 |
| 延伸 | 所以工业实现改用 **token 预算**而不是条数预算（如 Hermes Agent 的 "token-budgeted memory"）——**判断依据从"几条"换成"多少 token"，是这个领域公认的第一次修正** |
| 一句话钩子 | **窗口按条算账，一条大消息吃掉一格。** |

#### C 级 —— 分类不全

**C1. 三类分法漏了"会变的事实"**

| | |
|---|---|
| 你的分类 | 长期（用户信息）/ 短期（当前事件）/ 丢弃（单次噪音） |
| 漏掉的 | **曾经为真、但可能已经变了的事实** —— "这个季度重点抓 XX"、"当前对公存款利率 1.35%" |
| 为什么它不是前三类 | 不是永久画像（不该永久存）、不是当前事件（生命周期比一次会话长得多）、不是噪音（对下次对话有用） |
| 校正 | 这类**必须带 TTL**，更稳的是**存指针不存值**（记"用户关注利率"而非"利率是 1.35%"）。**银行场景这类特别多**（利率/政策/制度版本都在变） |
| 一句话钩子 | **"会变的事实"是第四类，它靠 TTL 活着。** |

#### D. 顺带记：本文档自己写错的一处

> 这条不是你的错，但同样值得复习——因为它是**流传很广的说法**，你以后读别人的文章还会再遇到。

| | |
|---|---|
| 错误说法 | "历史里躺着的是上一轮检索回来的**原文**"（本文 §一 初稿就是这么写的） |
| 实际情况 | 历史里是**用户原话（未增强）+ 答案全文**。`RetrievalAdvisor.augment()` 只影响发给模型的那一次调用，**不回流记忆** |
| 真正的决定因素 | **advisor 的 order**。`MessageChatMemoryAdvisor.before()` 存的是它**收到时**的那份消息；只有当 order 被挪到 `RetrievalAdvisor(0)` 之后，它看到的才是增强后的消息，**原文才会进历史** |
| 关键结论 | **order 不是风格问题，是安全问题。** 而这个改动在 code review 里看起来人畜无害 |
| 一句话钩子 | **原文进不进历史，取决于 advisor 的顺序，不取决于 advisor 的存在。** |

#### 校正记录的元规律（比具体条目更重要）

回头看这 6 条，**错误集中在两个方向上**——以后自查只查这两条：

| 偏差方向 | 涉及条目 | 自查问题 |
|---|---|---|
| **把"效果问题"当成了全部** | A3、B1、B2 | **"它会不会绕过权限？"** |
| **把"无损"当成了默认** | A1、A2、C1 | **"压缩/丢弃/分类之后，原来能做的事还做得到吗？"** |

> ⚠️ **第二类偏差在 RAG 场景里尤其危险**：你整个系统建立在"答案可追溯到原文"之上（引用归因 + 数字校验），而"压缩一下"这种看起来很自然的优化，**恰好拆的就是这个地基**。

## 附：本文与其它文档的衔接

| 本文内容 | 关联位置 | 关系 |
|---|---|---|
| §一 为什么不能用 `MessageChatMemoryAdvisor` | `BankAssistant.java:45-57`、`sql/rag_assistant_session.sql:8` | 缺口的事实依据 |
| §二 L4 | RAG-06 §五（版本管理）、RAG-06 §六（检索质量） | 不动，只说明它不是"记忆" |
| §三 权限继承 | `SessionAuthService.buildAccessFilter()`（`:142`）、`PermissionAdvisor`（context 键） | **复用**，不新写 |
| §四 巡检① | `DocumentVersionResolver`（RAG-06 §五） | **复用**版本表 |
| §四 审计哈希链 | RAG-04 #13「审计表 + 哈希链」 | **已复用并落地**：`org.agent.audit.AuditHashChain` 建在通用包里，**#13 只差消费方**（见 RAG-06 §十） |
| §五 场景 3 入库断点 | `RagDocumentIngestService`、`IngestManifestLoader`（现为纯解析器，无进度） | 扩成 checkpoint |
| §六 阶段 0 | RAG-04 #8 剩余项「指代消解」 | **备好前置**：历史进 `context`；改写层消费它才算是解锁该待做项。§5.5 的摘要又给它添了一条理由（摘要只做到"定位"，"按指针回原文取"要靠 #8） |
| §六 阶段 2 / §5.5 摘要 | RAG-06 §十 / §十一 | 已实现，实现完的章节按本文头部规则迁去 06 |
| §五 幂等 | RAG-04 #12「高风险话题人工复核状态机」 | **必须一起设计**（重复派单） |

