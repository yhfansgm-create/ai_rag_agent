package org.agent.memory;

import org.agent.memory.distill.CompactionRequest;
import org.agent.memory.dao.MemoryChangeLogRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话摘要/压缩的全链路（A1 校正的<b>反面</b>）：<b>让模型不听话</b>。
 *
 * <h2>这一段针对的是文档里一句"承诺"</h2>
 * RAG-07 §5.5 的提示词会禁止摘要记录金额/比例/期限，附录 A1 又把"把摘要当解法"
 * 列为<b>已知偏差</b>，校正结论是「摘要只能定位、数字按指针回原文取」。
 * 但"写在提示词里"是<b>承诺</b>，不是<b>机制</b> —— 模型完全可能写着"20 万元"交回来，
 * 而它一旦落库，就会作为"定位信息"进入此后每一轮的上下文（一份摘要能顶好几轮）。
 * <p>
 * 所以本类的做法与 {@link L2SemanticMemoryChainTest} 是同一个套路：
 * <b>把那个说"不"的模型打进桩里，看闸门是不是真的存在</b>。压缩器被 stub 成恒定返回
 * 一句夹带金额的摘要 —— 于是"这份摘要有没有落库"这件事只可能由
 * {@code SessionSummaryService} 里那道 {@code NumericExtractor} 终审决定。
 *
 * <h2>⚠️ 为什么这道终审必须写在 {@code SessionSummaryService} 而不是只写在压缩器实现里</h2>
 * 只写在 {@code LlmSummaryWriter} 里的话，它就只是<b>某个实现的属性</b>：
 * 换一个实现、或像本类这样把实现打桩掉，"摘要不许夹带事实性数字"这条约束就消失了。
 * 放在策略层，它才是 {@code SessionSummaryService} 的<b>契约</b>，打桩也照样验得动 ——
 * 这与 L2 读侧"SQL 下推 + Java 再复核一遍"是同一套手法。
 *
 * <h2>断言里刻意<b>不</b>出现的东西</h2>
 * 不断言"prompt 里不含『起存金额』"：{@link #highDoc()} 的正文
 * "大额存单起存金额的相关规定。"本来就通过检索进了 prompt。断言一个<strong>同名的</strong>片段，
 * 会得到一个恒假失败（或恒真通过）的断言 —— 那是把"测试写错了"伪装成"机制生效了"。
 * 所以只断言真正只属于那份违规摘要的片段：单位「万元」与整句。
 */
@SpringBootTest(properties = "rag.vectorstore.enabled=false")
@ActiveProfiles("local")
@DisplayName("会话摘要/压缩（全链路打桩：模型违规 → 整份丢弃）")
class SummaryContainsNumberChainTest extends L1ChainScaffold {

    /**
     * 压缩器交回来的<b>违规</b>摘要：讲的是主题，但顺手把金额写进去了 ——
     * 形状取自 A1 自己的例子（"上一轮『年利率 1.35%』被摘要成『讨论了存款利率』"）。
     */
    private static final String BAD_SUMMARY = "上一轮讨论了大额存单的起存金额，起点是 20 万元";

    /** 前 4 轮触发一次压缩；第 5 轮用来验"没落库的东西也不会进 prompt" */
    private static final List<String> TURNS = List.of(
            Q1, Q2, Q3, "柜面取现的授权流程是什么", "那它要谁批");

    @BeforeEach
    void stubCompactorWithBadText() {
        when(summaryWriter.summarize(any())).thenReturn(BAD_SUMMARY);
    }

    @Test
    @DisplayName("★ 模型交回夹带金额的摘要 → 整份丢弃（不落库、不注入），宁可让窗口滑走")
    void summaryWithFactsIsDiscardedNotStored() {
        stubRetrieval(List.of(highDoc()));
        List<MemoryChangeLogRow> chainBefore = chainSnapshot();

        chat(TURNS.subList(0, 4));

        // ★ 先证明这一次压缩**真的跑到了调压缩器**，且只跑了一次。
        //   少了这一步，"没落库"可能只是因为压缩压根没触发（窗口算法不对、开关关了），
        //   那是本类最容易发生的假通过
        ArgumentCaptor<CompactionRequest> captor = ArgumentCaptor.forClass(CompactionRequest.class);
        verify(summaryWriter, times(1)).summarize(captor.capture());
        // 顺带锁住"折叠的是哪几轮"：夹带数字的诱惑恰恰来自第 1 轮那份密级 4 的制度原文
        assertThat(captor.getValue().sourceTurns())
                .extracting(CompactionRequest.SourceTurn::turnNo)
                .containsExactly(1);

        // 核心断言：整份丢弃 —— 不是截断、不是"只留主题"、不是把数字抹掉。
        // 截出来的定位信息是假的，而它会被长期携带。差的一份还会取代掉上一版好的
        assertThat(sessionSummaryDao.selectActive(session.sessionId(), LocalDateTime.now())).isNull();
        // 什么都没落库 ⇒ 审计链上一行都没多（审计行是"变更"的凭证，没有变更就没有行）
        assertAppendedRows(chainBefore, 0);

        // 再走一轮：第 5 轮的窗口 = 第 3~5 轮，第 1 轮已经滑出去了。
        // 平时撑住它的正是那份摘要，而这次没有摘要 —— 于是那一轮<strong>真的丢了</strong>
        chat(TURNS.subList(4, 5));

        assertThat(prompts).hasSize(5);
        String fifth = textOf(prompts.get(4));
        // 先证明这一轮真的组成了 prompt（历史在、问题在）—— 否则下面的"不含"可能只是空 prompt
        assertThat(fifth).contains(TURNS.get(2));
        // 违规摘要的任何一个片段都不该出现。「万元」是刻意选的：它不受空格/半全角的写法差异影响
        // （打桩文本里写的是「20 万元」，若哪天有人把空格改了，断言不会跟着失效）
        assertThat(fifth).doesNotContain("万元").doesNotContain(BAD_SUMMARY);
    }

    /** 逐轮驱动。与 {@link SummaryChainTest} 同一个写法（两个类各自独立，刻意不抽到脚手架） */
    private void chat(List<String> turns) {
        for (String turn : turns) {
            assistant.chat(session.sessionId(), session.sessionToken(), turn);
        }
    }
}
