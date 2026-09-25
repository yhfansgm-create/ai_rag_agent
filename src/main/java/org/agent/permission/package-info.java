/**
 * 权限内核：会话三层鉴权 + 检索侧门禁谓词。整个系统的底座。
 *
 * <h2>这个包负责什么</h2>
 * <ul>
 *   <li>{@link org.agent.permission.PermissionAdvisor} —— 挂在 advisor 链<b>最外层</b>
 *       （{@code getOrder} 取 {@code Integer.MIN_VALUE}）。校验通过后把
 *       {@code SessionEnvelope} 与 {@code accessFilter} 写进 context，
 *       供下游检索使用。<b>fail-closed</b>：抛异常即不进模型。</li>
 *   <li>{@link org.agent.permission.SessionAuthService} —— 三层校验的实际执行者：
 *       会话有效性 / 快照新鲜度（{@code auth_version}，防 TOCTOU）/ 配额。
 *       另外负责按需刷新权限快照。</li>
 *   <li>{@link org.agent.permission.SessionEnvelope} —— 一次会话的权限快照，
 *       是权限层交给检索层的唯一凭证。</li>
 *   <li>{@link org.agent.permission.UserPermissionProvider} —— 权限来源抽象。
 *       当前实现是 {@link org.agent.permission.MockUserPermissionProvider}（演示用），
 *       接真实 IAM 时换实现即可。</li>
 * </ul>
 *
 * <h2>这个包不负责什么</h2>
 * <ul>
 *   <li><b>不检索、不拼 prompt</b>。它只产出"能看什么"的约束（{@code accessFilter}），
 *       怎么用这个约束是 {@code rag.retrieve} 的事。</li>
 *   <li><b>不发 HTTP</b>。路由在 {@code api.SessionController}。</li>
 * </ul>
 *
 * <h2>依赖方向</h2>
 * <b>它不依赖 api / rag / memory 中的任何一个</b> —— 这条例外约束是刻意维持的。
 *
 * <h2>⚠️ 为什么两个 HTTP 契约 record 留在这个包里</h2>
 * {@link org.agent.permission.CreateSessionRequest} 与
 * {@link org.agent.permission.CreateSessionResponse} 命名像 Web DTO，
 * 看着该跟 {@code SessionController} 一起搬去 {@code org.agent.api}，
 * <b>但它们不能</b>：{@code CreateSessionResponse} 是
 * {@link org.agent.permission.SessionAuthService#createSession(String)} 的<b>返回类型</b>。
 * 搬去 {@code api} 之后本包会 import {@code api}，而 {@code api} 又 import 本包
 * （{@code PermissionAdvisor}）—— 立刻成环。
 * <p>
 * 它们本来就有双重身份：既是 service 的对外契约，也是 HTTP body。
 * 契约归属哪个模块，它们就留在哪个模块。要真正把它们挪走，得先让
 * {@code createSession} 返回一个本包自己的领域对象、由 Controller 去映射成 HTTP body ——
 * 那是独立的一次重构，不是"顺手挪一下"。
 */
package org.agent.permission;
