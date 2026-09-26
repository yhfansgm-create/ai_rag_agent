package org.agent.memory;

import org.agent.memory.dao.MemoryChangeLogRow;
import org.agent.memory.dao.SessionSummaryRow;
import org.agent.memory.model.MemoryChangeAction;
import org.agent.memory.model.MemoryEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 会话摘要/压缩的全链路（A1 校正的<b>正面</b>：摘要只说"聊过什么"，一句事实性数字都不带）。
 *
 * <h2>触发条件为什么是"第 4 轮"—— 这个数不是拍出来的</h2>
 * L1 窗口 = {@code window-turns: 3} × 一轮两行 = 6 行（{@code MemoryProperties.promptRowLimit()}）。
 * 压缩的判据是"有轮次滑出了窗口且未被上一版摘要覆盖"，而窗口起点 = 窗口内<b>最小轮号</b>：
 * <pre>
 *   第 1 轮后：库里 2 行，窗口 = [1]        → 起点 1，没滑出
 *   第 2 轮后：库里 4 行，窗口 = [1,2]      → 起点 1，没滑出
 *   第 3 轮后：库里 6 行，窗口 = [1,2,3]    → 起点 1，没滑出
 *   第 4 轮后：库里 8 行，窗口只取最近 6 行 = [2,3,4] → 起点 2 &gt; 1 ⇒ 折叠 [1, 1]
 * </pre>
 * （{@code ChatMessageDao.selectRecentTurns} 是 {@code ORDER BY turn_no DESC LIMIT 6}，
 * 所以"最近 6 行"在 4 轮之后正好把第 1 轮挤出去。）
 * <p>
 * 于是第 4 轮后应有一版摘要覆盖 <b>{@code [1, 2)}</b>（左闭右开）：
 * {@code coversThroughTurnNo = 被折叠的最后一轮 + 1}，与 {@code coversFromTurnNo = 1} 相邻。
 * "每一轮要么在窗口里、要么在摘要里"这条完备性就是靠这个区间成立的。
 *
 * <h2>为什么断言"注入"必须再走一轮</h2>
 * 摘要是在<b>本轮回答之前</b>读的，而 v1 是在第 4 轮<b>回答之后</b>才生成的 ——
 * 所以第 4 轮的 prompt 里不可能有它。第 5 轮的 prompt 里才有，而第 5 轮自己又会压出 v2
 * （覆盖 {@code [2, 3)}，把 v1 取代掉）。<b>注入与取代都发生在第 5 轮，顺序是先读后写</b> ——
 * 这正是"摘要版本化"要保住的东西：事后能复现"当时模型看到的是哪一版"。
 *
 * <h2>打桩</h2>
 * 压缩器是 seam（{@code @MockitoBean}），返回一句<b>不含任何数字</b>的中文 ——
 * 于是一次模型调用都不会多出来（{@code prompts} 的数量仍严格等于轮数）。
 */
@SpringBootTest(properties = "rag.vectorstore.enabled=false")
@ActiveProfiles("local")
@DisplayName("会话摘要/压缩（全链路打桩：合规摘要）")
class SummaryChainTest extends L1ChainScaffold {

    /**
     * 打桩的摘要文本：一个阿拉伯数字都没有，也刻意不碰"元/万/%/天/月/年"这些单位 ——
     * 于是它<strong>过得了</strong> {@code NumericExtractor} 那道终审（配对类用的是违规文本）。
     */
    private static final String SUMMARY_TEXT = "上一轮讨论了《大额存单管理办法》里的起存金额怎么定";

    /** 摘要块的声明里的一句 —— 它只可能来自 {@code MemoryBlockRenderer.SUMMARY_HEADER} */
    private static final String SUMMARY_HEADER_FRAGMENT = "本会话较早内容的定位摘要";

    /** 前 4 轮触发一次压缩；第 5 轮用来验注入（见类注释） */
    private static final List<String> TURNS = List.of(
            Q1, Q2, Q3, "柜面取现的授权流程是什么", "那它要谁批");

    @BeforeEach
    void stubCompactor() {
        when(summaryWriter.summarize(any())).thenReturn(SUMMARY_TEXT);
    }

    @Test
    @DisplayName("① 第 4 轮触发压缩：覆盖区间 [1,2)、折叠 1 轮、密级取被压轮次的 max、审计链完整")
    void compactionFoldsTheTurnThatSlidOutOfWindow() {
        stubRetrieval(List.of(highDoc()));
        List<MemoryChangeLogRow> chainBefore = chainSnapshot();
        chat(TURNS.subList(0, 4));

        SessionSummaryRow row = sessionSummaryDao.selectActive(session.sessionId(), LocalDateTime.now());
        assertThat(row).isNotNull();
        assertThat(row.userId()).isEqualTo(USER);
        assertThat(row.summaryText()).isEqualTo(SUMMARY_TEXT);
        // 区间是"读到了什么就声称覆盖什么"（见 SessionSummaryService.runCompaction）：
        // 折叠了第 1 轮，于是覆盖 [1, 2) —— through 是"被折叠的最后一轮 + 1"
        assertThat(row.coversFromTurnNo()).isEqualTo(1);
        assertThat(row.coversThroughTurnNo()).isEqualTo(2);
        assertThat(row.sourceTurnCount()).isEqualTo(1);
        // 密级由 Java 按"被压缩轮次的 max"算，模型无权输出。
        // 第 1 轮引用的是密级 4 的资料 ⇒ 整份摘要就是 4——
        // 少了这一条，一份摘要能顶好几轮，阶段 1 修的那个旁路会被整段绕过
        assertThat(row.clearanceLevel()).isEqualTo(HIGH_DOC_LEVEL);
        assertThat(row.validTo()).isNull();
        assertThat(row.expiresAt()).isNotNull();

        // 审计：这一版摘要留下了 WRITE 记录。
        // ⚠️ 断言落在**这一版的 summaryUid** 上（审计表全库共享，见 assertAppendedRows 的注释）
        assertThat(chainSnapshot())
                .anyMatch(log -> row.summaryUid().equals(log.factUid())
                        && log.entityType() == MemoryEntityType.SUMMARY
                        && log.action() == MemoryChangeAction.WRITE);
        // 第一版没有前身 ⇒ 只留一行 WRITE（有前身时会是 SUPERSEDE + WRITE 两行）
        assertAppendedRows(chainBefore, 1);
    }

    @Test
    @DisplayName("② 第 5 轮：覆盖第 1 轮的摘要块真的进了 prompt，且带着「仅供定位」的声明")
    void summaryBlockIsInjectedNextTurn() {
        stubRetrieval(List.of(highDoc()));
        chat(TURNS.subList(0, 4));
        // 先确认前 4 轮真的压出了一版摘要 —— 否则下面的"注入了"可能只是巧合
        assertThat(sessionSummaryDao.selectActive(session.sessionId(), LocalDateTime.now())).isNotNull();

        chat(TURNS.subList(4, 5));

        assertThat(prompts).hasSize(5);
        String fifth = textOf(prompts.get(4));
        // 第 1 轮已经滑出窗口（第 5 轮的窗口 = [3,5]），它能留在 prompt 里只有一条路：摘要块。
        // 摘要文本与声明都只在摘要块里出现（历史里的原话与答案都不含它们）
        assertThat(fifth).contains(SUMMARY_TEXT);
        assertThat(fifth).contains(SUMMARY_HEADER_FRAGMENT);
        // 声明是安全件：摘要块防的是"被当作出处用"，措辞与事实块不同（见 MemoryBlockRenderer）
        assertThat(fifth).contains("不是事实来源");
    }

    /** 逐轮驱动。抽出来是因为两个用例都要跑同样的前 4 轮 */
    private void chat(List<String> turns) {
        for (String turn : turns) {
            assistant.chat(session.sessionId(), session.sessionToken(), turn);
        }
    }
}
