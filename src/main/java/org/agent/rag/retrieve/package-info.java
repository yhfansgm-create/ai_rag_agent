/**
 * 检索侧：查询扩展 → 多路召回 → 融合去重 → 权限/时效两层门禁 → 拼带编号引用。
 *
 * <h2>入口：看一个方法就够</h2>
 * {@link org.agent.rag.retrieve.RetrievalAdvisor#adviseCall} 只是壳，
 * 全部逻辑在私有的 {@code decide()} 里，五步按顺序：
 * <ol>
 *   <li>{@link org.agent.rag.retrieve.QueryExpander} —— 术语归一化，一路问题变成多路查询变体
 *       （词表来自 {@link org.agent.rag.retrieve.TermDictionary}）</li>
 *   <li>逐路 {@code similaritySearch()} —— <b>每一路都套同一个 accessFilter</b>，
 *       过滤是下推到向量库的，不是捞回来再筛</li>
 *   <li>{@link org.agent.rag.retrieve.RrfFusion} —— 多路融合 + 跨路去重（只有一路时跳过）</li>
 *   <li>两层门禁：权限（{@code passesGate}）+ 时效（{@link org.agent.rag.TemporalGate}）</li>
 *   <li><b>截断到 topK</b>，然后放行或拒答</li>
 * </ol>
 * <p>
 * ⚠️ 两个顺序问题改之前先想清楚：
 * <ul>
 *   <li><b>截断必须在两层门禁之后</b>。放在前面会让"被门禁剔掉的候选"白占 topK 名额，
 *       表现为"明明库里有合规资料却拒答"。</li>
 *   <li><b>拒答路径完全不调模型</b>。拒答响应是本地构造的（{@code refusalResponse}），
 *       不是让 LLM 说"我不知道" —— 后者可能被 prompt 注入带偏。</li>
 * </ul>
 *
 * <h2>脱敏为什么在这个包而不是 {@code ingest}</h2>
 * {@link org.agent.rag.retrieve.CodeRedactor} 在<b>检索时</b>按当前会话密级决定
 * 代码块是否可见；{@code ingest.CodeSniffer} 在<b>入库时</b>判定 {@code is_code} 元数据。
 * 一个管"是什么"，一个管"给谁看"，所以分属两侧。注意 {@code CodeRedactor.PLACEHOLDER}
 * 是包级私有 —— 它只给同包的单测看。
 *
 * <h2>依赖方向</h2>
 * 依赖 {@code org.agent.permission}（读 {@code SessionEnvelope}、用
 * {@code PermissionAdvisor} 的 context key）。<b>不依赖 ingest，也不依赖 memory</b>。
 */
package org.agent.rag.retrieve;
