# 银行 RAG —— 已实现功能导览（代码复习提纲）

> **本文只记已经跑起来的东西**：代码方法、设计取舍、单测清单、复习自测题。
> 待做功能与排期依据在《银行RAG-04-待做功能优先级》，两文分开维护 ——
> 那份文档会因为"做完一个划掉一个"而频繁变动，这份则只增不改。
>
> 已实现四个功能：时效落地（§三）· 引用归因与拒答（§四）· 入库版本管理（§五）· 检索质量（§六）。
>
> 阅读顺序建议：先看 §一 的全景调用链建立地图，再按 §三→§六 逐个读。
> 每个功能都按「解决什么问题 → 改造前状态 → 代码方法表 → 设计取舍 → 单测」组织。

## 一、先看全景：一次对话的完整调用链

```
POST /api/session/chat  {sessionId, sessionToken, message, bizDate?}
  └─ SessionController.chat → parseBizDate() → BankAssistant.chat(..., LocalDate)
       └─ ChatClient(advisors=[PermissionAdvisor, RetrievalAdvisor])
            ├─ ① PermissionAdvisor    (order = HIGHEST_PRECEDENCE，最先)
            │      三层校验 → 把 envelope + accessFilter 写进 context
            ├─ ② RetrievalAdvisor     (order = 0，在权限之后)
            │      decide()
            │        ├─ QueryExpander.expand()    术语归一化 → 多路查询变体
            │        ├─ 逐路 similaritySearch()    每路都套同一个 accessFilter
            │        ├─ RrfFusion.fuse()          多路融合 + 跨路去重（单路时跳过）
            │        ├─ 权限门禁 passesGate()      剔除越权
            │        ├─ 时效门禁 TemporalGate      剔除已废止 / 未生效
            │        ├─ 截断到 topK                ← 在两层门禁之后
            │        └─ 放行 or 拒答（拒答完全不调模型）
            └─ ③ ChatModel 调用（仅在放行时发生）

POST /api/kb/ingest
  └─ RagDocumentIngestService.ingestDir()
       ├─ 阶段一 collect()                        全部解析 + 权限预检
       │    └─ 任一文档不合法 → 整体中止（向量库里不留半份知识库）
       ├─ 阶段二 DocumentVersionResolver.resolve() 按 doc_id 收敛版本、推导旧版失效日
       └─ 阶段三 write()                           逐单元写库
            └─ 旧版本照常入库，靠时效区间挡在现行口径之外（不删除）
```

**关键设计**：两个 advisor 通过 `context` 单向传递数据，不直接互相引用。
`PermissionAdvisor` 是**生产者**（写 `CONTEXT_ENVELOPE` / `CONTEXT_ACCESS_FILTER`），
`RetrievalAdvisor` 是**消费者**。这样权限逻辑和检索逻辑可以独立演化。

## 二、权限三把锁（项目原有基础，先读懂它才能改检索）

| 位置 | 方法 | 职责 |
|---|---|---|
| `PermissionAdvisor` | `checkPermission(request)` | 从 advisorParams 取 sessionId/token → `SessionAuthService.verifyAndLoad` 三层校验（会话有效性 / 快照新鲜度 auth_version / 配额），**fail-closed**（抛异常即不进模型） |
| `RetrievalAdvisor` | `passesGate(doc, envelope)` | 检索回来的每篇再查一次：`kb_id ∈ 会话可检索库 && doc_level ≤ 会话密级 && orgPath ∈ org_codes`，任一不符即丢弃 |
| `RetrievalAdvisor` | `prepareForUser(doc, envelope)` | 密级 < 5 时对代码块脱敏；**脱不干净就整块丢弃**（宁缺毋滥） |

**要理解的两点**：
1. **下推 + 旁路复核 = 双保险**。过滤条件通过 `SearchRequest.filterExpression(accessFilter)` 下推给向量库执行，检索回来后再用 `passesGate` 复核一遍。为什么两道？因为下推依赖 metadata 干净、依赖向量库实现正确，不能作为唯一防线。
2. `passesGate` 不含 `is_code` 判定 —— 代码块对低密级用户走"脱敏/丢弃"而不是"拒绝访问"，这是两种不同的处置语义。

---

## 三、功能一：时效落地

**解决的问题**：制度会废止、会被新版替代。引用已废止条款指导今天的业务是事故级缺陷。
**改造前状态**：`DocMetadata` 一直有 `effectiveFrom` / `effectiveUntil` / `version` 字段，也解析正常，
但**从没写进 chunk metadata**，检索侧自然也就无从判定 —— 字段形同虚设。

### 新增 `TemporalGate.java`（时效门禁）

| 方法 | 说明 |
|---|---|
| `State stateOf(Map metadata, LocalDate bizDate)` | 主入口：判定某 chunk 在业务日期当天的状态 |
| `static State judge(LocalDate from, LocalDate until, LocalDate bizDate)` | 纯函数形式的判定核心，把 IO 与判断分离，**所以能直接写单测** |
| `boolean usable(Map, LocalDate)` | `EFFECTIVE` 或 `UNDATED` 即可用 |
| `static LocalDate parse(Object raw)` | 容忍 ISO 字符串 / `LocalDate` / SnakeYAML 的 `Date` 三种写法 |
| `static String iso(LocalDate d)` | 入库统一写 ISO 字符串 |

```java
public enum State { EFFECTIVE, NOT_YET, EXPIRED, UNDATED }
```

**三个需要理解的设计取舍**：

1. **闭区间** `[from, until]`：`effective_until` 当天仍算有效。这类边界最容易被写错，单测里专门覆盖。
2. **缺字段放行（UNDATED），不 fail-closed**。这是全篇最反直觉的一点：
   - 权限必须 fail-closed（查不到授权 → 不给看），因为漏一个就是越权事故；
   - 时效若 fail-closed，**存量文档会因为没补时效字段而整体检索不到**，PoC 会直接变空库。
   - 所以缺字段按"未声明时效"放行并留痕，由入库侧逐步补齐。
   - **同一个系统里两种相反的默认策略并存，判断依据是"哪种失败更不可接受"。** 这是很好的面试素材。
3. **脏数据容错**：`from > until`（区间不自洽）→ 按 UNDATED 放行并 WARN，不把文档"判死"；
   日期解析失败 → 同样按未声明处理。原则是**不让脏数据造成静默的数据不可见**。

### 入库侧写入（`RagDocumentIngestService.toDocument`）

```java
putIfNotNull(metadata, "doc_title", meta.title());
putIfNotNull(metadata, TemporalGate.META_EFFECTIVE_FROM,  TemporalGate.iso(meta.effectiveFrom()));
// 失效日：作者写了就用作者的；没写则由版本收敛推导出来（§五）
LocalDate until = meta.effectiveUntil() != null ? meta.effectiveUntil() : derivedUntil;
putIfNotNull(metadata, TemporalGate.META_EFFECTIVE_UNTIL, TemporalGate.iso(until));
```

**为什么存 ISO 字符串而不是日期对象**：`PgVectorStore` 把 metadata 序列化成 jsonb，
日期对象依赖 Jackson 的 `JavaTimeModule`（不一定注册），而 ISO 字符串在 jsonb 里
**字典序等价于时间序**，可直接比较、无序列化风险。

> `derivedUntil` 那一行是 §五 接进来的 —— **时效功能的第一个版本只写作者声明的失效日**，
> 结果是：作者不回头补旧版失效日，旧版就一直"生效中"，时效管控在最常见的场景下失效。
> **字段写得进去 ≠ 功能真的管用**，这是 §五 存在的理由。

### 检索侧消费（`RetrievalAdvisor.decide`）

```java
LocalDate bizDate = bizDate(request);            // 从 advisor param 读，缺省今天
for (Document d : permitted) {
    TemporalGate.State st = temporalGate.stateOf(d.getMetadata(), bizDate);
    if (st == TemporalGate.State.EXPIRED || st == TemporalGate.State.NOT_YET) { stale++; continue; }
    usable.add(d);
}
if (usable.isEmpty()) {
    return Decision.refuse(REFUSAL_STALE, TEXT_STALE);   // 全被时效剔除 → 拒答
}
```

**业务日期的贯通路径**（一个完整的"参数穿过所有层"的例子）：

```
ChatRequest.bizDate (String, 可选)
  → SessionController.parseBizDate()      // 非法格式按"未指定"处理，不顶掉整次对话
  → BankAssistant.chat(..., LocalDate)    // 新增重载，旧的三参方法委托给它
  → spec.param(RetrievalAdvisor.PARAM_BIZ_DATE, ...)
  → RetrievalAdvisor.bizDate(request)     // 解析失败 WARN + 退回今天
```

为什么业务日期要能传？因为要能回答"**这笔业务发生在 X 日，当时该按哪一版制度办**"——
这是审计场景的真实需求，也是把"时效"从静态属性变成可回溯维度的关键。

---

## 四、功能二：引用归因 + 拒答

### 核心：`Decision` 把"放行/拒答"变成显式类型

```java
private record Decision(ChatClientRequest request, String refusalReason, String refusalText) {
    static Decision proceed(ChatClientRequest r)      { return new Decision(r, null, null); }
    static Decision refuse(String reason, String text) { return new Decision(null, reason, text); }
    boolean refused() { return refusalText != null; }
}
```

用 `record` 做决策对象的三个好处：不可变、语义自解释、两个工厂方法让调用点读起来像自然语言。
`decide()` 返回它之后，`adviseCall` 只有两行分支：

```java
Decision d = decide(request);
if (d.refused()) return refusalResponse(request, d);   // 不调模型
ChatClientResponse response = chain.nextCall(d.request());
auditCitations(d.request(), response);                 // 后置校验
return response;
```

### 拒答：本地构造响应，完全不经过模型

```java
private static ChatClientResponse refusalResponse(ChatClientRequest request, Decision d) {
    request.context().put(CONTEXT_REFUSAL_REASON, d.refusalReason());
    ChatResponse resp = new ChatResponse(List.of(new Generation(new AssistantMessage(d.refusalText()))));
    return new ChatClientResponse(resp, request.context());
}
```

四种拒答原因（`REFUSAL_*` 常量），**要能说出每种对应哪种故障**：

| 常量 | 触发条件 | 用户可见文案 |
|---|---|---|
| `REFUSAL_NO_HIT` | 检索结果为空 | 未在授权范围内检索到资料 |
| `REFUSAL_PERMISSION_FILTERED` | 有命中但全被权限门禁剔除 | 同上（**故意不区分**，避免暴露"存在但你无权看"） |
| `REFUSAL_STALE` | 有命中但全部已废止/未生效 | 文件已废止或尚未生效 |
| `REFUSAL_KB_UNAVAILABLE` | 未配置 VectorStore | 知识库当前不可用 |

> **顺手修掉的隐患**：原来 `VectorStore` 缺失时是"直通让 LLM 用自身知识回答"，
> 日志里甚至写着"LLM 将基于自身知识回答（非授权资料）"。那等于输出无授权、不可溯源的内容，
> 与"拒答优先"原则直接冲突，已改为拒答。**这是"读旧代码要带着质疑"的实例。**

### 引用归因：编号 + 出处

```java
private static String citedContext(List<Document> docs)   // 拼 [1] [2] [3]...
private static String sourceLabel(Document doc)           // 来源：《标题》（doc_id） 版本 X · 章节
private static String augment(query, docs, staleCount, bizDate)  // 原问题 + 作答约束 + 资料
```

prompt 里同时告诉模型**三件事**：只用授权资料、引用要标编号、资料不足就说"资料中未提及"。
另外把"另有 N 篇已废止未提供"也写进去 —— 让模型知道**存在但被有意排除**的东西，避免它自己脑补。

### 越界引用检测（后置校验）

```java
static List<Integer> invalidCitations(String answer, int max)  // 找出不在 [1,max] 的编号
private void auditCitations(ChatClientRequest request, ChatClientResponse response)
private static String answerText(ChatClientResponse response)
```

越界 → `context.put(CONTEXT_CITATION_VIOLATIONS, bad)` + WARN 日志，标记该答案需人工复核。

**当前只检测不重写**，原因写在注释里：重写要重建 `ChatResponse`，会丢掉 token 用量等元数据。
升级方向：越界时降级为拒答，或做真正的"片段对齐"（要求 `[n]` 能落到原文某一段落）。
**能说清"为什么现在不做到那一步"比直接做掉更能体现工程判断。**

---

## 五、功能三：入库版本管理

**解决的问题**：「同一份制度有三个版本，哪版是现行的？」
**和 §三 的关系**：这是时效功能的**上游**。`TemporalGate` 会问"这条 chunk 今天能不能用"，
但只有当每条 chunk 身上带着**正确**的 `effective_until`，它才问得出正确答案。
现实中写制度的人只写"这版从哪天生效"，**很少回头去补上一版的失效日** ——
本节就是替他们把这件事算出来。

### 新增 `DocumentVersionResolver.java`（版本收敛）

| 方法 / 类型 | 说明 |
|---|---|
| `record DatedDoc(docId, version, effectiveFrom, effectiveUntil)` | 只取收敛所需的四个字段，**从而能脱离 Spring 与向量库做纯逻辑单测** |
| `record Adjustment(effectiveUntil, supersededBy, current)` | 对单个文档的裁定：补的失效日、被谁取代、是否现行版 |
| `record Resolution(adjustments, warnings)` | `adjustments` 与入参**同序同长**；`warnings` 是需要人工复核的填报问题 |
| `Resolution resolve(List<DatedDoc>, LocalDate today)` | 主入口，按 `doc_id` 归组后逐组收敛 |
| `static int compareVersion(String, String)` | 版本号比较，"1.10" > "1.9"、"v10" > "v2" |
| `private static List<String> segments(String)` | 把版本串切成连续的数字段 / 字母段：`v1.10-beta` → `[v, 1, 10, beta]` |

**核心规则**：同一 `doc_id` 的多个版本里，`effective_from` 最晚的那版是现行版；
其余版本若作者未声明 `effective_until`，自动推导为**其直接继任者生效日 − 1 天**。

**三个必须讲清楚的设计取舍**：

1. **为什么是"继任者生效日 − 1 天"，而不是"现行版生效日 − 1 天"？**
   两版时两者等价，三版就错了：

   ```
   v1.0 (2024-01-01) → v2.0 (2024-07-01) → v3.0 (2025-01-01)

   用"现行版生效日 − 1"：v1.0 止于 2024-12-31，v2.0 也止于 2024-12-31
                        → v1.0 和 v2.0 区间重叠半年，违反"任意时点只有一条现行条款"
   用"继任者生效日 − 1"：v1.0 止于 2024-06-30，v2.0 止于 2024-12-31   ✅ 首尾相接
   ```

   **这个 bug 是被单测抓出来的** —— `threeVersions_chainCorrectly` 一开始就红了。
   规则对错不靠推理自洽，靠能列举出的反例。

2. **为什么不删旧版本？** 银行要能回答"2023 年那笔业务当时依据的是哪一版"，删除等于销毁证据。
   旧版本照常入库，只是被时间区间挡在现行口径之外 —— 顺带一个工程好处：
   **本功能完全不依赖向量库的 delete 能力**，是纯元数据操作。

3. **`doc_id` 缺失的文档不参与收敛**。没有稳定标识就无法判断谁是谁的新版，
   硬猜只会把不相关的文档互相标成"已废止"——**那比不收敛更危险**。

### 支撑前提：chunk id 里含 version

```java
String chunkKey = docId + "@" + version + "#" + chunkNo;   // 派生确定性 UUID
```

同一 `doc_id` 的**不同版本是不同 UUID**，因此多版本可以共存、互不覆盖。
**这正是"不删旧版本"能在技术上成立的前提** —— 如果 id 只按 `docId + chunkNo` 生成，
第二次入库就把第一版覆盖掉了，版本管理无从谈起。

### 入库管道改为三阶段（`RagDocumentIngestService`）

| 阶段 | 方法 | 职责 |
|---|---|---|
| 一、收集 | `collect(dir, files)` → `collectMarkdown` / `collectManifest` | 解析 + 权限预检，产出 `List<Unit>`（元数据已就绪、正文已就绪） |
| 二、收敛 | `versionResolver.resolve(...)` | 拿**全局视野**做版本收敛，WARN 写入 `IngestReport.warnings` |
| 三、写入 | `write(store, unit, adj)` → `toDocument(...)` | 逐单元写库，把 `derivedUntil` / `supersededBy` 注进 metadata |

```java
// toDocument 新增的两个参数
putIfNotNull(metadata, TemporalGate.META_EFFECTIVE_UNTIL, TemporalGate.iso(
        meta.effectiveUntil() != null ? meta.effectiveUntil() : derivedUntil));  // 作者优先，其次推导值
putIfNotNull(metadata, "superseded_by", supersededBy);   // 审计线索：这版被谁取代
```

**为什么必须分两阶段**：要判断"哪版是现行的"，就得先看见同一 `doc_id` 的**全部**版本 ——
边扫边写的话，先写入的版本还不知道后面有更新的版本存在，失效日无从推导。
**顺带得到第二个好处**：校验失败发生在**任何写入之前**，不会留下"入库了一半"的知识库。
（这个好处有专门的测试：`invalidDocAbortsWholeIngest` 断言 `verify(vectorStore, never()).add(any())`。）

### `IngestReport`：入库结果要能自证

```java
public record IngestReport(int documents, int chunks, List<String> superseded, List<String> warnings)
```

`POST /api/kb/ingest` 的返回体从 `{"ingestedChunks": N}` 扩成上述五个字段。
**为什么 warnings 要返给调用方而不是只打日志**：失效日的推导依赖 front-matter 的填写质量，
而填写质量只有运维 / 制度管理员能补 —— 必须让调用方看见。典型告警：

| 告警 | 含义 |
|---|---|
| 现行版未声明 `effective_from` | 失效日只能按"今天"倒推，**结果依赖入库当天**，重跑可能不一样，必须补齐 |
| 已声明的失效日晚于继任版生效日 | 两版出现**重叠期**，合规上不可接受 |
| 推导出的失效日早于该版生效日 | 区间为空，该版本**从未生效**，日期多半填错了 |

### 一个明确的边界：单文件入库不做收敛

```java
public int ingestFile(Path file)   // ← 不做版本收敛
```

收敛的前提是看得见同一 `doc_id` 的其他版本，单文件调用给不了这个视野，硬做只能靠猜。
所以它保留"原样入库"的语义（供测试与增量补录使用），正常入库走 `ingestDir()`。
**能说清"这个接口在什么情况下不该用"比它能干什么更有价值。**

### 单测（15 个）

| 测试类 | 覆盖 |
|---|---|
| `DocumentVersionResolverTest`（9） | 两版/三版链式收敛、作者声明优先、重叠告警、缺生效日兜底、版本号数值比较、生效日优先于版本号、区间为空告警、单版/缺 `doc_id` 不受影响 |
| `RagIngestVersionTest`（6） | **端到端闭环**：front-matter → 收敛 → chunk metadata → `TemporalGate` 真的拦得住旧版本；非法文档整体中止；单文件不收敛；缺目录报错 |

> 其中 `derivedUntil_isHonouredByRetrievalGate` 是**刻意跨了"入库"和"检索"两层**来断言的：
> 只验证 ingest 算出了失效日还不够，必须证明那个失效日真的被 `TemporalGate` 消费、旧版本真的查不到，
> 功能才算闭环。**"跨越模块边界的断言"是集成测试真正该做的事。**

### 手工验证（不用写代码）

```bash
# 1. 把 application-local.yml 的 rag.kb.dir 指向演示目录
rag:
  kb:
    dir: ./kb-test-data/version-demo     # 两版同名制度（DOC-VER-DEMO-001，v1.2 与 v2.0）

# 2. 入库，看返回体
curl -X POST http://localhost:8080/api/kb/ingest
# → superseded: ["DOC-VER-DEMO-001（version=1.2）→ 已被 version=2.0 取代，推导失效日 2024-12-31"]

# 3. 问"大额取款多少需要授权" → 只应命中 v2.0 的"10 万元 / 双人授权"
# 4. 带业务日期回溯：{"message":"...","bizDate":"2024-06-01"} → 只应命中 v1.2 的"5 万元 / 主管授权"

# 5. 不想改配置的话，直接把演示目录当知识库：
cp kb-test-data/version-demo/*.md kb-test-data/valid/   # 再把 rag.kb.dir 还原
```

**第 3、4 步的对比是这个功能最有说服力的演示**：同一句问题，换业务日期得到两个不同的、
各自正确的答案 —— 这才是"时效可回溯"的完整含意。

---

## 六、功能四：检索质量 —— 术语归一化 + 多路召回 + RRF 融合

**解决的问题**：「员工用口语问，制度用条文写，怎么对上？」
**具体错位**：员工问"**年假**能休几天"，制度里写的是"**带薪年休假**"。
这种**精确术语的错位**靠向量相似度救不了 —— 两个词的语义确实很近，但制度原文里根本没有"年假"二字，
chunk 上也不会有任何词法重合。**这是纯向量检索的天花板。**

### 决策一：用词典，不用 LLM 改写查询

| | 词典改写 | LLM 改写 |
|---|---|---|
| 覆盖度 | 只覆盖登记过的词条 | 广 |
| 确定性 | **同一问题永远同一改写结果**，可复现、可回归测试 | 不稳定，今天对的明天可能错 |
| 可审计 | 依据是白纸黑字的词条，能解释"为什么这么搜" | 说不清依据 |
| 幻觉风险 | **无**（不会把"年假"改成"年终奖"） | 有 |

银行场景下后三条是硬要求，所以**词典打底**。但**扩展位留给 LLM**：
`RrfFusion.fuse()` 只关心"有几路有序结果"，不关心每路是怎么来的 ——
要接 LLM 改写，在 `QueryExpander.expand()` 里再追加一路变体即可，**融合层一行都不用改**。
这就是 RAG-05 讲的"**机制可复用，策略必须重定**"：RRF 是机制，改写来源是策略。

> 顺带兑现了排期时写下的判断：P4 #8 的排期理由里写着"**改写本身会引入幻觉，所以要保留原问题做多路召回**"。
> 实现时这条被硬编码成了规则 —— `expand()` 产出的第 1 路**永远是原问题**。
> **改写错了也还有兜底**，这是排期理由直接变成代码约束的例子。

### 决策二：`TermDictionary` —— 业务术语表（`src/main/resources/kb-terms.yml`）

```yaml
terms:
  - canonical: 带薪年休假                     # 制度里的标准写法（希望命中的写法）
    aliases: ["年假", "公休假", "带薪休假"]   # 员工平时会用的说法
```

| 方法 | 说明 |
|---|---|
| `TermDictionary(String location)` | 加载 `rag.retrieval.terms-file`（默认 `classpath:kb-terms.yml`） |
| `static TermDictionary empty()` / `of(List<Term>)` | 测试用：空词典 / 直接给定词条 |
| `List<Term> terms()` | 词条只读视图 |

**降级策略**：文件不存在、格式错、词条缺字段 → **WARN 并按空词典处理，不让应用起不来**。
判断依据和 §三 的 `UNDATED` 放行是同一套逻辑：**术语表是增强项，缺了只是召回差一点，
不该阻断整个知识库服务**；而空词典时行为等价于"没有查询扩展"，功能可安全降级。

**这张表是业务资产，不是开发配置** —— 来源应该是"检索失败日志"（把用户问了但没召回的问题捞出来人工归类），
由业务部门维护。放 YAML 而不是写进代码，就是为了让业务能改。

### 决策三：`QueryExpander` —— 确定性改写

```java
public List<String> expand(String query) {
    Set<String> variants = new LinkedHashSet<>();
    variants.add(q);                                   // ① 原问题恒为第 1 路
    for (Term t : dictionary.terms()) {
        for (String alias : t.aliases())               // ② 口语 → 制度用语（主方向）
            if (q.contains(alias)) variants.add(q.replace(alias, t.canonical()));
        if (q.contains(t.canonical()))                 // ③ 制度用语 → 口语（反向，成本近零）
            for (String alias : t.aliases()) variants.add(q.replace(t.canonical(), alias));
    }
    return variants.stream().limit(maxVariants).toList();   // ④ 去重 + 限流
}
```

`LinkedHashSet` 保证**去重且保序**；截断永远从尾部截，所以丢掉的一定是次要变体。

### 决策四：`RrfFusion` —— 倒数排名融合

```
score(d) = Σᵢ 1 / (k + rankᵢ(d))        rank 从 1 开始，k 默认 60
```

**为什么用 RRF 而不是加权求和**：向量余弦相似度与关键词分数量纲完全不同，加权求和要先归一化，
而**归一化参数换一批数据就失效**。RRF 只用**排名**不用分数 —— 免调参、对分数尺度不敏感。
这是它在工业界成为默认做法的原因。

**k 的作用**：k 越小，头部排名优势越大；k=60 是原论文默认值，用来平滑掉单路的偶然性。
**稳定性**：`List.sort` 是稳定排序 + `LinkedHashMap` 记录首次出现顺序 ⇒ 分数相同时按首次出现排，
**同问同答**（银行场景的隐性要求）。

| 方法 | 说明 |
|---|---|
| `List<T> fuse(List<List<T>> rankedLists, Function<T,String> keyFn)` | 泛型 + 取 key 的函数，**因此能脱离 Spring AI 的 `Document` 做纯逻辑单测** |

### 决策五：接进 `RetrievalAdvisor.retrieve()`

```java
private List<Document> retrieve(VectorStore store, String query, String accessFilter) {
    List<String> queries = queryExpander.expand(query);
    if (queries.isEmpty()) return List.of();
    List<List<Document>> ranked = new ArrayList<>();
    for (String q : queries) {
        ranked.add(store.similaritySearch(SearchRequest.builder()
                .query(q).topK(candidatesPerQuery)          // ← 放大候选，不是 topK
                .filterExpression(accessFilter)             // ← 每一路都套同一个权限过滤
                .build()));
    }
    if (ranked.size() == 1) return ranked.get(0);           // 无扩展变体 → 跳过融合
    return rrfFusion.fuse(ranked, Document::getId);
}
```

**三个必须能讲出来的点**：

1. **数据锁不能被查询改写绕过**。多出来的路只是换个问法，`accessFilter` 一条都不放宽。
   有测试专门断言这一点（两路请求拿到的 `filterExpression` 都非空）。
   **任何"让检索更聪明"的改造，第一件事都是确认它没有放宽安全边界。**
2. **候选集要放大，截断要放在门禁之后**。`candidates-per-query`（默认 `topK*3`）比 `topK` 宽，
   因为候选后面还要过权限门禁和时效门禁；先截断再剔除的话，被剔掉的名额就白白浪费 ——
   "命中 30 篇但 5 篇无权"会退化成只剩 1 篇。这是本次改造**顺带修掉的检索质量问题**。
3. **单路时行为与改造前完全等价**（RRF 单路保序），所以这次改造对既有测试零破坏。
   **"新功能默认不改变旧行为"是能安全上线的关键。**

### 配置项（`application-local.yml`）

| 键 | 默认 | 说明 |
|---|---|---|
| `rag.retrieval.top-k` | 5 | 最终注入 prompt 的条数（在两层门禁剔除之后截断） |
| `rag.retrieval.candidates-per-query` | `topK*3` | 每路召回条数；填 0 表示自动 |
| `rag.retrieval.max-query-variants` | 3 | 查询变体上限（原问题恒占 1 路） |
| `rag.retrieval.rrf-k` | 60 | RRF 平滑常数 |
| `rag.retrieval.terms-file` | `classpath:kb-terms.yml` | 业务术语表位置 |

### 与"受阻的 hybrid 检索"是什么关系

原规划的 hybrid = **BM25 词法路 + 向量路 + RRF**，卡在云 RDS 能否装 `zhparser` 中文分词扩展。
本方案 = **多个查询变体的向量路 + RRF** —— **融合层是同一个机制，只是"多路的来源"换掉了**。
所以 BM25 那一侧的阻塞不再挡住整体方案；等 `zhparser` 可用了，再补一路真正的词法结果进来即可。

**"被环境卡住时，换掉那一层而不是放弃整个方案"** 是这次改造最值得讲的方法论，
也是 RAG-05"机制可复用、策略必须重定"的又一次落地。

### 单测（19 个）

| 测试类 | 覆盖 |
|---|---|
| `RrfFusionTest`（6） | 两路共识排第一、跨路一致胜过单路第一、跨路去重、单路保序、null 路/空元素容错、k 的影响 |
| `QueryExpanderTest`（9） | 别名改写且原问题在首位、无命中退化为单路、一词多命中、反向扩展、变体上限、去重、空词典、**术语表缺文件降级不抛**、自带词表端到端可用 |
| `RetrievalAdvisorTest` 新增（4） | **口语提问被术语改写那一路救回**、所有变体都没命中仍拒答、跨路重复被去重、截断在门禁之后 |

> `colloquialQuery_rescuedByTermRewrite` 是这一节的主测试：mock 向量库对
> "年假能休几天"返回空、对"带薪年休假能休几天"返回结果 —— 精确复现了词汇错位。
> 改造前这题会走到 `REFUSAL_NO_HIT` 拒答，改造后被正确回答。
> **测试要复现的是问题本身的形状，不是代码的形状。**

---

## 七、可复用的 Spring AI 技能点（换个项目也用得上）

| 技能点 | 在本项目的体现 |
|---|---|
| **Advisor 链的 order 语义** | `PermissionAdvisor.getOrder() = HIGHEST_PRECEDENCE`（先鉴权），`RetrievalAdvisor.getOrder() = 0`（后检索）。**顺序即安全边界** |
| **用 context 在 advisor 间传数据** | `ChatClientRequest.context()` 是一个 Map，上游写、下游读。比 advisor 之间互相持有引用更解耦 |
| **advisorParams 往 context 里塞值** | `spec.param("sessionId", ...)` 的值会进 `request.context()`，这是 Spring AI 1.0.0 的行为（`MessageChatMemoryAdvisor` 同款用法） |
| **短路不调模型** | `adviseCall` 里不调 `chain.nextCall()` 而直接构造 `ChatClientResponse` 返回 —— 这是"拒答"能落地的前提 |
| **可测性设计** | 判定逻辑抽成 `static` 纯函数（`TemporalGate.judge`、`invalidCitations`），不依赖 Spring 容器就能单测；测试用 2 参便捷构造器 `new RetrievalAdvisor(provider, topK)` 避开容器 |

## 八、复习自测题（能答上来就算真懂了）

1. 为什么权限 fail-closed，而时效缺字段却 fail-open？两种默认策略的依据是什么？
2. 时效区间为什么用闭区间？`effective_until` 那天到底算不算有效？
3. 为什么 metadata 里的日期存字符串而不是 `LocalDate`？和 pgvector/jsonb 有什么关系？
4. `REFUSAL_NO_HIT` 和 `REFUSAL_PERMISSION_FILTERED` 为什么不给用户不同的文案？
5. `Decision` 为什么用 record 而不是返回 `null` 表示拒答？
6. 拒答为什么必须"不调用模型"？如果只是把拒答文案写进 prompt 会有什么问题？
7. 越界引用检测为什么只告警不重写答案？重写会付出什么代价？
8. 业务日期为什么要从 API 一路传到检索层，而不是在检索层直接取 `LocalDate.now()`？
9. `VectorStore` 缺失时原实现"让模型用自身知识回答"，为什么这在银行场景是错的？
10. 入库时 `putIfNotNull` 做了什么？为什么可选字段不写 null 进去？
11. 入库为什么必须分"收集 / 收敛 / 写入"三步？合成一步会丢什么？
12. 旧版本的失效日为什么是"**继任者**生效日 − 1 天"，而不是"现行版生效日 − 1 天"？举个三版的例子说明后者错在哪。
13. 为什么入库要保留旧版本不删？这对功能实现提出了什么额外要求（提示：向量库的 delete 接口）？
14. chunk id 里为什么要带 `version`？如果只按 `docId + chunkNo` 生成会发生什么？
15. 查询改写为什么选词典而不是 LLM？三个理由分别是什么？LLM 改写要接进来的话，改哪一层就够了？
16. `QueryExpander` 为什么把原问题固定放第 1 路？这对应排期表里的哪句风险判断？
17. RRF 为什么只用排名不用分数？加权求和在这里的具体困难是什么？
18. 多路召回时，**权限过滤条件**应该怎么处理？为什么这一点比召回率更重要？
19. 为什么把 `topK` 截断放在两层门禁**之后**？放前面会有什么可观测的后果？
20. 单路（术语表为空）时这次改造对既有行为有何影响？为什么这一点决定了改造能不能安全上线？
21. 术语表文件丢失时系统怎么表现？这和 §三 时效缺字段放行是不是同一套判断逻辑？
22. 原来的 hybrid 方案被 `zhparser` 卡住，本次是怎么绕开的？被换掉的是机制的哪一层？

