/**
 * L1 情景记忆：读最近 N 轮历史注入 prompt，轮末把"结论 + 指针"落库。
 *
 * <h2>它解决的是什么问题</h2>
 * 主链路原本<b>完全无状态</b> —— 同一会话里问两次"那它呢？"，第二句是在真空里发生的。
 * 本包补上多轮对话的连续性，同时<b>不让记忆变成权限模型的旁路</b>。
 * 为什么不能直接挂 Spring AI 自带的 {@code MessageChatMemoryAdvisor}、为什么
 * {@code getOrder()} 必须是默认值，{@link org.agent.memory.MemoryAdvisor} 的类注释
 * 讲了整整两节（那是本项目最值得读的一段注释，也是改动前必读的一段）。
 *
 * <h2>分层与职责</h2>
 * <table border="1">
 *   <caption>包内分层</caption>
 *   <tr><th>位置</th><th>内容</th><th>约定</th></tr>
 *   <tr><td>本包根</td><td>{@code MemoryAdvisor}（Spring 装配 + 唯一 catch 边界）、
 *       {@code TurnExtractor} / {@code HistoryMessageMapper}（无状态纯函数）、
 *       {@code MemoryProperties}（不可变配置对象）</td>
 *       <td>纯函数类做成 {@code final class} + 私有构造</td></tr>
 *   <tr><td>{@code model}</td><td>{@code TurnFacts}（写侧）/ {@code ChatTurn}（读侧）/
 *       {@code CitedChunkRef} / {@code AnswerDecision}</td>
 *       <td>不可变 record，尽量不依赖 Spring AI</td></tr>
 *   <tr><td>{@code dao}</td><td>{@code ChatMessageDao}（MyBatis 注解式 {@code @Mapper}）
 *       + {@code ChatMessageRow}</td>
 *       <td>列名显式 {@code AS camelCase}，不依赖 snake_case 自动映射</td></tr>
 *   <tr><td>{@code service}</td><td>{@code ChatMemoryService}</td>
 *       <td>唯一的编排点，只此一处说 DAO + model</td></tr>
 * </table>
 *
 * <h2>⚠️ 失败隔离的方向与本包当前的边界</h2>
 * <b>{@code MemoryAdvisor} 是全项目唯一 catch 异常的地方，而且是 fail-open</b>：
 * 记忆读写失败只记 warn、降级成"没有历史"，对话照常进行。
 * 这与权限层的 fail-closed 是<b>刻意相反</b>的 —— 权限拦不住是事故，记忆坏了只是体验差。
 * {@code ChatMemoryService} 与 DAO 保持 fail-fast，异常一律向上抛给这个唯一边界。
 *
 * <h2>⚠️ 类注释与实现的一处落差（读代码时注意）</h2>
 * {@code MemoryAdvisor} 类注释把对策写成"存指针不存内容 <b>+ 读取时按实况信封过滤</b>"，
 * 但<b>后一半尚未实现</b>：{@code ChatMemoryService} 的注释明说
 * "读历史时按权限过滤整个属于阶段 1「记忆过滤」的交付物"，且 L1 表里根本没有内容侧密级列。
 * 也就是说，<b>当前真正的防护只有"存指针不存内容"这一半</b>。
 * 完整设计（L0/L1/L2 分层、断点续传）见 {@code docs/银行RAG-07-记忆分层与断点续传设计.md}。
 *
 * <h2>依赖方向</h2>
 * 依赖 {@code permission}（读 {@code SessionEnvelope} 作审计字段）与
 * {@code rag.retrieve}（读 {@code RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS} 这个 context key
 * 来记录"本轮引用了哪些 chunk"）。<b>不依赖 {@code api}</b>。
 */
package org.agent.memory;
