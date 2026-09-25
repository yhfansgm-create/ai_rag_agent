/**
 * 检索增强生成（RAG）：入库、检索、输出校验三块能力，外加一个共用的时效门禁。
 *
 * <h2>四个子包的分工</h2>
 * <table border="1">
 *   <caption>子包职责</caption>
 *   <tr><th>子包</th><th>职责</th><th>入口类</th></tr>
 *   <tr><td>{@link org.agent.rag.ingest}</td><td>文档 → 切分 → 向量化 → 写库</td>
 *       <td>{@code RagDocumentIngestService}</td></tr>
 *   <tr><td>{@link org.agent.rag.retrieve}</td><td>查询扩展 → 多路检索 → 融合 → 两层门禁 → 拼引用</td>
 *       <td>{@code RetrievalAdvisor}</td></tr>
 *   <tr><td>{@link org.agent.rag.guard}</td><td>答案里的金额/比例/期限回到原文核验</td>
 *       <td>{@code NumericGuardrailAdvisor}</td></tr>
 *   <tr><td>{@code org.agent.rag.config} / {@code .web}</td>
 *       <td>向量库与数据源装配（{@code RagVectorStoreConfig}）／入库路由（{@code KbController}）</td>
 *       <td>—</td></tr>
 * </table>
 * 拆分依据是<b>实际跨类引用</b>，不是按名字归类。两处容易归错：
 * <ul>
 *   <li>{@code CodeSniffer} 归 {@code ingest}（入库时判定 {@code is_code}），
 *       {@code CodeRedactor} 归 {@code retrieve}（检索时按会话密级脱敏）。名字像，用途不同。</li>
 *   <li>{@link org.agent.rag.TemporalGate} 留在<b>本包根</b>而不是任一子包 ——
 *       入库侧（{@code DocumentVersionResolver}）和检索侧（{@code RetrievalAdvisor}）
 *       都直接用它。放任一子包都会造出"入库依赖检索"这类子包间依赖。</li>
 * </ul>
 *
 * <h2>依赖方向</h2>
 * 单向依赖 {@code permission}（取 {@code SessionEnvelope} 与 {@code accessFilter}）。
 * <b>入库侧与检索侧之间没有依赖</b> —— 它们通过向量库里的 metadata 约定通信，
 * 而不是通过 Java 调用。
 *
 * <h2>读哪块先看哪</h2>
 * 调<b>检索效果</b>看 {@code RetrievalAdvisor.decide()}（五步流水线都在这一个方法里）；
 * 查<b>入库问题</b>看 {@code RagDocumentIngestService} 的三阶段
 * （{@code collect} / {@code resolve} / {@code write}）。
 */
package org.agent.rag;
