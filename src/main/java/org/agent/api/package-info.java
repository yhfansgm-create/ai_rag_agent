/**
 * 应用层：对外 HTTP 入口 + 对话门面。
 *
 * <h2>这个包负责什么</h2>
 * <ul>
 *   <li>{@link org.agent.api.BankAssistant} —— <b>主链路门面</b>。装配 advisor 链，
 *       对外只暴露 {@code chat(sessionId, token, message[, bizDate])}。
 *       想搞清"一次对话经过几个 advisor、顺序由什么决定"，从它的类注释读起。</li>
 *   <li>{@link org.agent.api.SessionController} —— 对外路由：
 *       {@code POST /session}、{@code POST /session/chat}、
 *       {@code POST /session/{id}/close}。
 *       ⚠️ 路由<b>没有 {@code /api} 前缀</b>（项目未配置
 *       {@code server.servlet.context-path}），而 {@code docs/} 下多篇文档写的是
 *       {@code /api/...}，属过时写法 —— 细节与原因见该类注释</li>
 *   <li>{@code ChatRequest} / {@code ChatReply} —— 对话端点的请求/响应体</li>
 * </ul>
 *
 * <h2>这个包不负责什么</h2>
 * <ul>
 *   <li><b>不做鉴权判断</b>。Controller 只做参数解析（如把 {@code bizDate} 的
 *       格式错误降级为"未指定"），真正的校验在 {@code PermissionAdvisor} 里 fail-closed。
 *       所以 {@code SessionAuthException} 是从 advisor 链里抛出来、一路冒泡到这里的。</li>
 *   <li><b>不建会话</b>。会话的创建/关闭属于权限域，逻辑在
 *       {@code permission.SessionAuthService}；这里只是转发。</li>
 * </ul>
 *
 * <h2>依赖方向</h2>
 * 单向依赖 {@code permission} / {@code rag} / {@code memory}，<b>不被它们依赖</b>。
 * 它是依赖图的最上游 —— 把 {@code BankAssistant} 从 {@code permission} 搬到这里，
 * 正是为了解开原先 {@code permission ↔ rag} 和 {@code permission ↔ memory} 两个环。
 * <p>
 * ⚠️ 因此 {@code CreateSessionRequest/Response} <b>没有</b>跟着 {@code SessionController}
 * 搬进来：它们是 {@code SessionAuthService.createSession} 的返回契约，搬进来会让
 * {@code permission → api} 反向依赖，等于用新环换旧环。原因也记在
 * {@link org.agent.permission} 里。
 */
package org.agent.api;
