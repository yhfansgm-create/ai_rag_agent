package org.agent.memory.distill;

import org.agent.memory.model.FactDraft;

import java.util.List;

/**
 * 事实抽取的 <b>seam</b>（接缝）：把"从一轮对话里提出候选事实"这件事抽象成一个接口。
 *
 * <h2>为什么值得为它单独定义一个接口</h2>
 * 全链路打桩测试里，本接口被 {@code @MockitoBean} 替换掉，于是
 * "记忆从这一轮写下去了吗"这件事可以在<b>完全不产生额外模型调用</b>的前提下断言。
 * <p>
 * 这不是洁癖，是硬约束：{@code L1ChainScaffold} 的既有断言 {@code assertThat(prompts).hasSize(2)}
 * 数的是 {@code chatModel.call()} 的次数。蒸馏若直接调模型，就会往这个列表里插进去
 * 若干条<b>与回答无关的</b> prompt —— 于是这个断言从"这一轮只调了一次模型"
 * 变成"这一轮调了几次模型"，一个原本在验链路的断言被改成了在验实现细节。
 * <p>
 * 同理，{@link SummaryWriter} 是第二个 seam。两个 seam 合起来，
 * "模型说什么"与"Java 怎么处理模型说的话"在测试里可以分别控制 ——
 * 而后者才是本阶段真正要证明的东西。
 *
 * <h2>⚠️ 实现契约：<b>绝不抛异常</b></h2>
 * 模型调用失败、结构化输出解析失败、模型答了一堆散文 —— 一律返回<b>空列表</b>，
 * 只打 WARN。理由：
 * <ul>
 *   <li>调用方在异步线程上（{@code memoryDistillExecutor}），异常抛进去只会被
 *       {@code ThreadPoolExecutor} 吞掉，症状是"什么都没发生"，比日志里的 WARN 更难查</li>
 *   <li>而且"这一轮没抽出事实"与"模型挂了"在<b>结果上是同一件事</b>：
 *       都不写记忆。既然处理动作相同，就不该让它们走两条路径</li>
 * </ul>
 * 但<b>空列表不等于静默</b>：每个失败分支都<b>必须</b>留下一条带异常栈的 WARN。
 * 记忆的失败是允许的（记忆是锦上添花），记忆的失败<b>不可见</b>是不允许的
 * （会让"L2 永远不写"变成一个查不出来的现象）。
 */
public interface FactDistiller {

    /**
     * 从一轮对话里抽出候选事实。
     * <p>
     * ⚠️ 返回的是<b>候选</b>，不是"要写入的事实"：能不能写由 {@code FactWritePolicy} 判定。
     * 实现类不要在这里做任何策略判断（不认识就不输出、值超长就截断……），
     * 那会让拒绝理由在审计里消失。
     *
     * @param request 本轮输入；{@code null} 视为"没有可抽的内容"→ 空列表
     * @return 候选列表，<b>永不为 null</b>；无候选返回空列表
     */
    List<FactDraft> distill(DistillRequest request);
}
