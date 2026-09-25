/**
 * 银行 RAG 助手 —— 代码地图与阅读入口。
 *
 * <h2>这个项目是什么</h2>
 * 面向<b>银行制度问答</b>场景的 RAG 助手。一条链路是：
 * 文档入库 → 向量检索 → 权限/时效两层门禁 → 生成答案 → 输出侧数字一致性校验。
 * 为什么这么设计、放弃了哪些做法，见仓库 {@code docs/银行RAG-0*.md}。
 *
 * <h2>包结构与依赖方向</h2>
 * <pre>
 *   api ─────────→ permission          (应用层：HTTP 入口 + 对话门面)
 *    ├───────────→ rag.retrieve / rag.guard
 *    └───────────→ memory
 *
 *   rag.* ───────→ permission          (检索/入库只向权限内核要 accessFilter)
 *   memory ──────→ permission, rag.retrieve
 *   support ←───── 被以上各家使用（叶子，不依赖任何人）
 * </pre>
 * 包<b>内部</b>还有两条窄依赖，都不是环：{@code rag.guard → rag.retrieve}
 * （只为一个 context key 常量），{@code rag.ingest} / {@code rag.retrieve} → {@code rag}
 * （共用 {@code TemporalGate}）。
 * 依赖方向<b>严格单向</b>。{@code permission} 是唯一的底座，它<b>不</b>反向依赖
 * api / rag / memory —— 这条是刻意维持的：一旦它反过来 import 上面任何一个包就会成环，
 * 而成环之后"谁能改谁"就没有答案了。
 * <p>
 * 有两个看着"位置奇怪"的类是这个约束的直接结果，挪动前请先读原因：
 * <ul>
 *   <li>{@code permission.CreateSessionRequest/Response} —— 它俩同时是
 *       {@code SessionAuthService.createSession} 的<b>返回契约</b>和 HTTP body。
 *       跟着 Controller 搬进 {@code api} 会让 permission 反向依赖 api，所以留在原处。</li>
 *   <li>{@code rag.TemporalGate} —— 入库侧（{@code DocumentVersionResolver}）和
 *       检索侧（{@code RetrievalAdvisor}）都用它。放进任一子包都会造成子包间依赖，
 *       所以留在 {@code rag} 根。</li>
 * </ul>
 *
 * <h2>建议阅读顺序</h2>
 * 从"一次请求的生命周期"往外扩，而不是按目录字母序：
 * <ol>
 *   <li>{@code api.BankAssistant} —— 先看类注释里的 advisor 管线，建立全景</li>
 *   <li>{@link org.agent.permission} —— 三层鉴权。<b>先读它</b>，因为 rag 与 memory
 *       都依赖它；不读它，后面的 {@code accessFilter}、{@code SessionEnvelope} 都是天书</li>
 *   <li>{@link org.agent.rag.retrieve} —— 检索核心，以 {@code RetrievalAdvisor.decide()} 为主线</li>
 *   <li>{@link org.agent.rag.ingest} —— 入库三阶段（收集 → 版本收敛 → 写库）</li>
 *   <li>{@link org.agent.rag.guard} —— 输出侧数字校验</li>
 *   <li>{@link org.agent.memory} —— 记忆层（L1 情景记忆），注意它的 order 是安全边界</li>
 *   <li>{@link org.agent.playground} —— 最后再看，学习/评测用，不在主链路</li>
 * </ol>
 * 更细的"带着什么问题读"见仓库根目录 {@code README.md}。
 *
 * <h2>⚠️ 启动类的位置是承重的</h2>
 * Spring 组件扫描和 MyBatis {@code @Mapper} 发现都以
 * {@link org.agent.YuAiAgentApplication} 所在包为根。子包重构不影响，
 * 但把启动类挪进新包会让这两者失效。
 */
package org.agent;
