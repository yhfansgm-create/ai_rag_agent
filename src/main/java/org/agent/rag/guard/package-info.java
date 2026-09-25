/**
 * 输出侧防线：把答案里出现的金额 / 比例 / 期限，逐个回到检索到的原文里找出处。
 *
 * <h2>它拦的是什么</h2>
 * 检索对了、权限也对，答案仍可能<b>把数字编错</b>：原文是 {@code 0.5‰} 而答案写 {@code 0.5%}
 * （差 10 倍）、原文是 {@code T+1} 而答案写 {@code 5 个工作日}。
 * 这类错误在银行场景是实质风险，而向量检索和权限门禁都拦不住 —— 它们管的是"资料对不对、该不该给"，
 * 不管"模型有没有算错"。
 *
 * <h2>三个类的分工</h2>
 * <ul>
 *   <li>{@link org.agent.rag.guard.NumericExtractor} —— 从文本里抽出数字实体
 *       （支持阿拉伯数字、中文数字、单位换算、百分比前缀写法），归一成可比较的
 *       {@code Mention}。<b>逻辑量最大的一块</b>，边界用例看它的单测</li>
 *   <li>{@link org.agent.rag.guard.NumericConsistencyChecker} —— 做集合差：
 *       答案里有、原文里找不到的即为违规</li>
 *   <li>{@link org.agent.rag.guard.NumericGuardrailAdvisor} —— 挂链路的那个，
 *       {@code getOrder = 10}，在<b>最内层</b></li>
 * </ul>
 *
 * <h2>⚠️ 它为什么必须在最内层，以及"模型已经调完了怎么还能拒答"</h2>
 * 它要等两件事：检索结果落进 context（{@code RetrievalAdvisor} 是生产者，order 更外层），
 * 以及模型把答案生成完。所以它的 order 比检索大。
 * <p>
 * 拒答的机制是<b>换掉整个响应</b>：模型确实已经调完、token 确实已经花了，
 * 但违规答案不会吐给用户。这是刻意的取舍 —— 让用户拿到错数字的代价，
 * 比多花一次 token 大得多。对应地，{@code TEXT_UNVERIFIED_NUMBER_TEMPLATE} 里
 * 只报"第几处未找到出处"，不回显那个可疑数字。
 *
 * <h2>依赖方向</h2>
 * <b>不依赖 {@code permission}</b> —— 它校验的是数字，不是权限；也不知道会话密级。
 * <p>
 * 对 {@code rag.retrieve} 有一条<b>很窄的编译期依赖</b>，值得说清楚：
 * {@code NumericGuardrailAdvisor} import 了 {@code RetrievalAdvisor}，<b>但只为了引用
 * {@code RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS} 这个 context key 常量</b>，
 * 不调用它的任何方法。数据传递本身是单向的 —— 检索侧往 context 里写，本包从 context 里读。
 * 依赖方向 {@code guard → retrieve} 是单向的，{@code retrieve} 不反向依赖本包。
 * <p>
 * 如果哪天想彻底切断这条编译期依赖，可以把 context key 常量抽到一个独立的
 * "advisor 契约"包里，让两侧都只依赖那个包 —— 那是一次独立重构，收益是消除
 * {@code guard} 对 {@code retrieve} 内部常量的耦合。
 */
package org.agent.rag.guard;
