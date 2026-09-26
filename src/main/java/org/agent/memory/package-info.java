/**
 * 记忆层：<b>L1 情景记忆</b>（本会话最近 N 轮）+ <b>L2 语义记忆</b>（跨会话的稳定事实）
 * + <b>会话摘要/压缩</b>（滑出窗口的那一段压成定位信息）。
 *
 * <h2>它解决的是什么问题</h2>
 * 主链路原本<b>完全无状态</b> —— 同一会话里问两次"那它呢？"，第二句是在真空里发生的。
 * 本包补上多轮对话的连续性，同时<b>不让记忆变成权限模型的旁路</b>。
 * 为什么不能直接挂 Spring AI 自带的 {@code MessageChatMemoryAdvisor}、为什么
 * {@code getOrder()} 必须是默认值，{@link org.agent.memory.MemoryAdvisor} 的类注释
 * 讲了整整几节（那是本项目最值得读的一段注释，也是改动前必读的一段）。
 *
 * <h2>三层各自的"一句话定位"（别把它们混起来讲）</h2>
 * <table border="1">
 *   <caption>三层记忆</caption>
 *   <tr><th>层</th><th>存什么</th><th>活多久</th><th>为什么需要它</th></tr>
 *   <tr><td>L1 情景</td><td>本会话的轮次（结论 + 指针）</td><td>窗口 = 最近 N 轮</td>
 *       <td>指代消解、多轮连续</td></tr>
 *   <tr><td>摘要</td><td>滑出窗口那段的<b>定位</b>描述</td><td>会话内，版本化</td>
 *       <td>不出现"两边都没有"的偶然丢失（RAG-07 §5.5）</td></tr>
 *   <tr><td>L2 语义</td><td>用户自述的<b>稳定事实</b></td><td>跨会话，带 TTL</td>
 *       <td>同一件事不必每开一次会话就重说一遍</td></tr>
 * </table>
 * ⚠️ <b>三层不是"越多越好"的堆叠，各有各的失败模式</b>：L1 怕成为权限旁路（已用
 * "按本轮实况信封过滤"堵住）、摘要怕夹带事实性数字（已用 {@code NumericExtractor}
 * 机械拦下）、L2 怕把模型的推测记成事实（已用复述验证拦下）。
 * 三句话钩子：<b>L1 负责"接得上"，摘要负责"找得到"，原文负责"答得对"。</b>
 *
 * <h2>分层与职责</h2>
 * <table border="1">
 *   <caption>包内分层</caption>
 *   <tr><th>位置</th><th>内容</th><th>约定</th></tr>
 *   <tr><td>本包根</td><td>{@code MemoryAdvisor}（Spring 装配 + catch 边界）、
 *       {@code TurnExtractor} / {@code HistoryMessageMapper} / {@code MemoryBlockRenderer}
 *       / {@code FactGrounding} / {@code FactRedlines} / {@code FactValueNormalizer}
 *       / {@code FactWritePolicy} / {@code ContextBudget}（无状态纯函数）、
 *       四个不可变配置对象</td>
 *       <td>纯函数类做成 {@code final class} + 私有构造；<b>本阶段的安全性几乎都在这一层</b></td></tr>
 *   <tr><td>{@code model}</td><td>{@code TurnFacts}（写侧）/ {@code ChatTurn}、{@code RecalledFact}、
 *       {@code SessionSummary}（读侧）/ {@code FactKey}（词表唯一真相源）/
 *       {@code FactDraft} / {@code FactScope} / {@code MemoryStatus} / 审计枚举</td>
 *       <td>不可变 record，尽量不依赖 Spring AI</td></tr>
 *   <tr><td>{@code dao}</td><td>四组 {@code XxxDao}（MyBatis 注解式 {@code @Mapper}）+ 对应 row</td>
 *       <td>列名显式 {@code AS camelCase}；物理 {@code id} 不进 record，一律用业务键
 *       （{@code fact_uid} / {@code summary_uid}）；<b>不提供只按 {@code status} 筛的通用查询</b></td></tr>
 *   <tr><td>{@code service}</td><td>{@code ChatMemoryService}（L1）、{@code SemanticMemoryService}
 *       + {@code MemoryFactWriter}（L2）、{@code SessionSummaryService} + {@code SummaryWriterService}
 *       （摘要）、{@code MemoryAuditAppender} / {@code MemoryChangeLogVerifier}（审计链）</td>
 *       <td><b>每层各有唯一的读写策略出口</b>；只有 {@code *Writer*} 那两个是事务边界，
 *       且都是独立 bean（同类自调用会让 {@code @Transactional} 静默失效）</td></tr>
 *   <tr><td>{@code distill}</td><td>{@code FactDistiller} / {@code SummaryWriter} 两个 seam 接口
 *       + 各自的 LLM 实现 + 入参/出参模型</td>
 *       <td>{@code seam} 的存在是为了"全链路测试既走真代码、又不产生额外模型调用"</td></tr>
 *   <tr><td>{@code config}</td><td>{@code MemoryDistillConfig}：两个配置 {@code @Bean}
 *       + 有界队列蒸馏线程池</td>
 *       <td>配置做成 {@code @Bean} 的理由是"有四个消费方，不许多处现造默认值"</td></tr>
 * </table>
 *
 * <h2>⚠️ 失败隔离的方向与本包的 catch 边界</h2>
 * 记忆读写失败的唯一处理方式是<b>降级 + 告警，而且方向是 fail-open</b>：
 * 读不到就当作没有那一层，写不进就只记 warn，对话照常进行。
 * 这与权限层的 fail-closed 是<b>刻意相反</b>的 —— 权限拦不住是事故，记忆坏了只是体验差。
 * <p>
 * 边界<b>不止一处，但纪律只有一条</b>（DAO 与服务层一律 fail-fast、不吞异常）：
 * <ul>
 *   <li>{@code MemoryAdvisor}：同步段。读侧三条通路（历史 / 事实 / 摘要）<b>各自</b>一个
 *       try-catch —— 一张表出问题不该把另外两层一起拖死；写侧外面一层，
 *       两个异步提交点各一层</li>
 *   <li><b>异步任务体自带 try-catch</b>（{@code SemanticMemoryService.runDistill} /
 *       {@code SessionSummaryService.runCompaction}）：它们<b>不在 advisor 的调用栈上</b>，
 *       外抛进 {@code ThreadPoolExecutor} 只会被线程池吞掉，且日志里看不出是谁的任务</li>
 * </ul>
 * 所以"抛出来了"这件事在单测里仍然可以断言 —— 隔离边界是<b>枚举得出</b>的，不是散落各处的。
 *
 * <h2>权限：三条不能忘的规则</h2>
 * <ol>
 *   <li><b>判定依据只许来自本轮实况信封</b>，任何一层都不许拿行里存的密级快照当判据
 *       （快照只用于留痕与复核）</li>
 *   <li><b>不过就是整份丢弃</b>：L1 整轮丢、摘要整份丢、L2 整条丢。
 *       绝不出现"半轮""半份摘要"——被腰斩的记忆是<b>假事实</b>，比少一条危险得多</li>
 *   <li>L1 的密级条件在 Java 侧筛（保窗口语义），L2 的下推进 SQL 再在 Java 侧复核
 *       （保"LIMIT N 真的返回 N 条可读事实"）。<b>这个差异是有理由的，别把它统一掉</b> ——
 *       理由写在 {@code SemanticMemoryService} 的类注释里</li>
 * </ol>
 *
 * <h2>依赖方向</h2>
 * 依赖 {@code permission}（读 {@code SessionEnvelope} 作判据与审计字段）、
 * {@code rag.retrieve}（读 {@code RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS} 这个 context key
 * 来记录"本轮引用了哪些 chunk"）、{@code rag.guard}（复用 {@code NumericExtractor}
 * 校验摘要里不许出现事实性数字）与 {@code org.agent.audit}（哈希链）。
 * <b>不依赖 {@code api}</b>。
 * <p>
 * 完整设计（L0/L1/L2 分层、断点续传）见 {@code docs/银行RAG-07-记忆分层与断点续传设计.md}；
 * 已实现的部分见 {@code docs/银行RAG-06-*} 的功能八/九。
 */
package org.agent.memory;
