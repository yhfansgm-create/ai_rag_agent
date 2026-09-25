/**
 * 学习与评测用的 playground，<b>不在银行 RAG 主链路上</b>。
 *
 * <h2>为什么它单独一个包</h2>
 * 这两个类原来挤在一个叫 {@code org.agent.App} 的包里 —— 包名首字母大写，
 * 而且和真实的业务代码混在一起，读代码时"这个类到底算不算主链路"要靠猜。
 * 单独成包、包名点明用途之后，{@code api} / {@code rag} / {@code memory} / {@code permission}
 * 里剩下的就都是生产代码了。
 *
 * <h2>两个类各自的用途</h2>
 * <ul>
 *   <li>{@link org.agent.playground.RagApp} —— <b>检索评测工具，有实用价值</b>。
 *       {@code retrieve(query)} 是<b>不调 LLM 的纯检索</b>，用来把"召回质量"和"生成质量"
 *       分开看；调 topK、换 embedding 模型时应该用它，而不是问一句看答案好坏。
 *       它的单测会把命中结果打到 stdout（含 {@code doc_id} / {@code doc_level} / 章节）。</li>
 *   <li>{@link org.agent.playground.LoveApp} —— Spring AI 教程留下的单轮问答 demo，
 *       与银行业务无关。留着的价值是<b>反面参照</b>：它直接用
 *       {@code InMemoryChatMemoryRepository} 当记忆，而 {@code org.agent.memory}
 *       花了整个模块去解决"记忆回放会绕过权限门禁" —— 两个实现一对比，差距一眼可见。</li>
 * </ul>
 *
 * <h2>⚠️ 它们都是 {@code @Component}，会被 Spring 装配</h2>
 * 也就是说应用启动时这两个类会被创建（开销很小，但确实会发生），
 * 只是<b>没有任何生产代码调用它们</b>，唯一的调用方是 {@code src/test} 下各自的单测。
 * 如果哪天要清理，删掉它们不影响主链路；但在那之前，
 * {@code RagApp.retrieve} 是调检索参数时最顺手的工具，建议留着。
 *
 * <h2>依赖方向</h2>
 * 不依赖 {@code permission}，不依赖 {@code memory} —— 这正是它"不在主链路"的技术标志：
 * 它<b>没有权限门禁</b>，谁调都能取到任何密级的内容，所以只能用于本地评测。
 */
package org.agent.playground;
