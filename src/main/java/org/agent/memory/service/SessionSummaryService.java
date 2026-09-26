package org.agent.memory.service;

import lombok.extern.slf4j.Slf4j;
import org.agent.memory.MemoryProperties;
import org.agent.memory.SummaryProperties;
import org.agent.memory.dao.ChatMessageDao;
import org.agent.memory.dao.ChatMessageRow;
import org.agent.memory.dao.SessionSummaryDao;
import org.agent.memory.dao.SessionSummaryRow;
import org.agent.memory.distill.CompactionRequest;
import org.agent.memory.distill.SummaryWriter;
import org.agent.memory.model.AnswerDecision;
import org.agent.memory.model.SessionSummary;
import org.agent.permission.SessionEnvelope;
import org.agent.rag.guard.NumericExtractor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * 会话摘要的唯一策略出口：读（{@link #loadSummary}）与压缩的提交（{@link #scheduleCompaction}）。
 *
 * <h2>它解决的是哪个具体现象</h2>
 * L1 的窗口是<b>硬滑窗</b>：第 5 轮时第 1 轮就<b>整段消失</b>。摘要把"滑出窗口的那一段"
 * 压成一句定位信息继续留在上下文里，于是"3 轮以前但很相关的内容"不再直接丢掉。
 *
 * <h2>⚠️ 但它<b>只负责"找得到"，不负责"答得对"</b></h2>
 * RAG-07 附录 A1 把"把摘要当成解法"列为<b>已知偏差</b>：摘要<b>有损，且破坏引用归因</b>
 * （上一轮「年利率 1.35%」被摘要成「讨论了存款利率」，下一轮问「刚才那个利率」就答不出了）。
 * 校正结论是"摘要只能定位、数字按指针回原文取"，而它的<b>机械化落实</b>不在这里 ——
 * 在 {@code LlmSummaryWriter}（摘要文本必须过 {@code NumericExtractor}），
 * 本类负责的是这条落实的<b>最后一道校验</b>：不合格就不落库，见 {@link #runCompaction}。
 *
 * <h2>权限：整份摘要按本轮实况信封判定，不过就整份丢弃</h2>
 * 摘要把高密级轮次的内容<b>压缩后长期携带</b>，所以它比 L1 更需要小心 ——
 * 不继承密级的话它是一条比 L1 旁路<b>更宽</b>的路（<b>一份摘要能顶好几轮</b>）。
 * 两道落实：
 * <ul>
 *   <li>写入时 {@code clearance_level = max(被压缩轮次的密级, 上一版摘要的密级)}，
 *       由本类算，<b>模型无权输出</b>。上一版那一项不是可省的：
 *       {@code previousSummary} 的文本会被折进新摘要，若只取本次轮次的 max，
 *       高密级内容就能<b>降级搭车</b>溜进新版</li>
 *   <li>读取时按本轮信封判 {@code 1 <= level <= 信封密级}，<b>整份不过就整份丢弃</b> ——
 *       与 L1"残轮整轮丢弃"同一手法，绝不出现"半份摘要"</li>
 * </ul>
 * 与 L2 一致：{@code perm_auth_version} <b>只留痕，不参与判定</b>。
 *
 * <h2>⚠️ 本类没有事务，也不该有</h2>
 * 落库是 {@link SummaryWriterService} 的短事务。事务边界往上挪一格就会包住压缩
 * （一次 LLM 往返，秒级），那是本阶段最大的风险点，完整论证见 {@code MemoryFactWriter} 的类注释。
 *
 * <h2>失败隔离</h2>
 * 读侧 fail-fast（异常交给 {@code MemoryAdvisor} 的 catch 边界降级成"本轮没有摘要"）；
 * <b>异步任务体自带 try/catch</b>（它不在 advisor 的栈上，外抛只会被线程池吞掉且看不出是谁）。
 */
@Slf4j
@Service
public class SessionSummaryService {

    private final SessionSummaryDao summaryDao;
    private final ChatMessageDao chatMessageDao;
    private final SummaryWriter compactor;
    private final SummaryWriterService summaryWriterService;
    private final ExecutorService distillExecutor;
    private final SummaryProperties props;

    /**
     * 摘要的"不许夹带事实性数字"终审（A1 校正）。
     * <p>
     * 照 {@code LlmSummaryWriter} 的先例<b>直接 new 而不是注入</b>：它无状态、纯函数、
     * 只依赖自带的词表。为它开一个 {@code @Bean} 或加一个构造参数，
     * 换来的只是"构造器又长一个"（那正是本项目已经在收敛的东西）。
     * <p>
     * ⚠️ 它是 {@code NumericExtractor} 的<b>第三个</b>消费方（前两个：输出侧答案校验、
     * {@code LlmSummaryWriter}）。三处复用同一个判定器，是为了让"什么算事实性数字"
     * <b>只有一个出口</b> —— 三处各自写一套正则，迟早会有一套对不上。
     */
    private final NumericExtractor numericExtractor = new NumericExtractor();

    public SessionSummaryService(SessionSummaryDao summaryDao,
                                 ChatMessageDao chatMessageDao,
                                 SummaryWriter compactor,
                                 SummaryWriterService summaryWriterService,
                                 @Qualifier("memoryDistillExecutor") ExecutorService distillExecutor,
                                 SummaryProperties props) {
        this.summaryDao = summaryDao;
        this.chatMessageDao = chatMessageDao;
        this.compactor = compactor;
        this.summaryWriterService = summaryWriterService;
        this.distillExecutor = distillExecutor;
        this.props = props;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 取本会话当前有效、且本轮信封读得到的那份摘要。
     *
     * <h2>为什么这里不像 L2 那样把密级条件下推进 SQL</h2>
     * {@code SessionSummaryDao.selectActive} 的 WHERE 里<b>只有</b>
     * {@code session_id} / {@code valid_to} / {@code expires_at}，密级是在这里判的。
     * 三个理由，与 L2 下推的理由不冲突（那一条针对的是"该用户最多 N 条"这种<b>预算</b>语义）：
     * <ul>
     *   <li>摘要的读取是"<b>取本会话当前那一版</b>"，唯一键保证至多一条 ⇒
     *       没有 {@code LIMIT}、也就没有"取几条"的预算问题。下推不带来任何收益</li>
     *   <li>判据留在 Java 侧，单测能直接喂进"不该返回的那一份"并断言被丢掉 ——
     *       不依赖数据库真的返回错东西（与 L2 的 Java 复核同一个作用）</li>
     *   <li>摘要<b>无法按密级部分保留</b>（整份要么读要么不读），
     *       所以"能不能读"这个决定的输入越少越好</li>
     * </ul>
     *
     * @param envelope 本轮实况权限信封 —— 读取判定的<b>唯一</b>依据
     * @return 可注入的摘要；关掉开关、信封为空、本会话还没有摘要、或信封读不到时返回 {@code null}
     */
    public SessionSummary loadSummary(SessionEnvelope envelope) {
        if (!props.enabled() || envelope == null || !hasText(envelope.sessionId())) {
            return null;
        }
        SessionSummaryRow row = summaryDao.selectActive(envelope.sessionId(), LocalDateTime.now());
        return toReadable(row, envelope);
    }

    /**
     * 策略复核 + 转成读侧模型。五个拒绝理由共用同一个返回 {@code null} 的出口 ——
     * 与 {@code ChatMemoryService.toTurn} / {@code SemanticMemoryService.toRecalled} 同一手法：
     * "绝不返回不该返回的行"由构造保证，将来加第 6 个理由也不会漏掉某个分支。
     *
     * @return 可读的那份摘要；{@code null} 表示丢弃
     */
    private SessionSummary toReadable(SessionSummaryRow row, SessionEnvelope envelope) {
        if (row == null || row.summaryUid() == null) {
            return null;
        }
        // ① 有效性。SQL 已经滤过 valid_to / expires_at，这里是"策略只在 Java 侧成立"的复核：
        //    万一有人改坏了 SQL（或另加了一条查询路径），这里仍然拦得住
        if (row.validTo() != null) {
            return null;
        }
        // ② 归属复核：会话串号或脏数据的兜底，与 ChatMemoryService.toTurn 规则 2 同款
        if (envelope.userId() == null || !envelope.userId().equals(row.userId())) {
            return null;
        }
        // ③ 密级：判据是**本轮信封**，不是行里的快照列。下界 1 的理由同 ChatMemoryService.readableAt
        int level = row.clearanceLevel();
        if (level < 1 || level > envelope.dataClearanceLevel()) {
            return null;
        }
        // ④ 过期：到期不删行，只是不再注入。与 L1/L2 同一口径
        LocalDateTime now = LocalDateTime.now();
        if (row.expiresAt() != null && !row.expiresAt().isAfter(now)) {
            return null;
        }
        // ⑤ 退化区间：覆盖区间为空或为负的摘要没有任何定位能力，而它"存在"这件事
        //    会让 scheduleCompaction 以为"已经覆盖过了"从而永久停止压缩 —— 静默停摆，
        //    比少一份摘要难查得多。丢弃并留 WARN
        if (row.coversThroughTurnNo() <= row.coversFromTurnNo()) {
            log.warn("摘要覆盖区间退化，不予注入：summaryUid={} from={} through={}",
                    row.summaryUid(), row.coversFromTurnNo(), row.coversThroughTurnNo());
            return null;
        }
        if (!hasText(row.summaryText())) {
            return null;
        }
        // ⑥ 超预算整份丢弃，**绝不截断** —— 被腰斩的摘要会变成一条错误的定位信息
        //    （与 MemoryBlockRenderer 的取舍一致）。写入侧本已拦过一次，这里是读取侧的兜底：
        //    配置把 max-summary-chars 调小之后，库里那些"按旧预算写的"摘要会撞上这一条
        if (row.summaryText().length() > props.maxSummaryChars()) {
            log.warn("摘要超出当前预算，整份丢弃（不截断）：summaryUid={} length={} budget={}",
                    row.summaryUid(), row.summaryText().length(), props.maxSummaryChars());
            return null;
        }
        return new SessionSummary(row.summaryUid(), row.sessionId(), row.summaryText(),
                row.coversFromTurnNo(), row.coversThroughTurnNo(), level,
                row.sourceTurnCount(), toInstant(row.expiresAt()));
    }

    // ------------------------------------------------------------------
    // 压缩的提交
    // ------------------------------------------------------------------

    /**
     * 提交一次压缩（<b>异步</b>）。调用方在写完 L1 之后调用它，见 {@code MemoryAdvisor}。
     *
     * <h2>为什么这里<b>不做</b>任何判据就提交</h2>
     * "要不要压缩"这件事需要两次查库（本会话当前摘要、L1 窗口起点）。
     * 在这里查一遍、到了任务体里再查一遍，就是两次查询加上一个竞态窗口；
     * 而任务体是同线程的同一个方法，判据在那儿只有一个出口。
     * 代价是每轮多提交一个任务 —— 队列满时会被 {@code WarnAndDiscardPolicy} 打一条 WARN 丢掉，
     * 而那个任务本来也可能是"什么都不用做"。<b>可接受的浪费换掉一个竞态</b>。
     *
     * <h2>为什么是异步</h2>
     * 压缩是一次完整的 LLM 往返（秒级）。同步会让每一次 {@code chatClient.call()}
     * 多等一个 RTT —— 用户的回答已经生成好了，却还在等一块"锦上添花"的定位信息。
     * 与 {@code SemanticMemoryService.scheduleDistill} 共用同一个有界队列（见 {@code MemoryDistillConfig}）：
     * 两者都是"回答完成之后才做的、失败了只是体验差"的后台 LLM 任务，
     * 分成两个池只会让每一半都更难判断"到底是谁把队列塞满了"。
     *
     * @param envelope    本轮实况信封 —— 会话范围与密级的依据
     * @param memoryProps L1 的配置，用来算窗口大小（{@code promptRowLimit()}）。
     *                    ⚠️ 它是<b>方法参数</b>而不是构造依赖：本类只借它一个数，
     *                    而 L1 的配置面要零改动（照 {@code ChatMemoryService} 的先例）
     */
    public void scheduleCompaction(SessionEnvelope envelope, MemoryProperties memoryProps) {
        if (!props.enabled() || envelope == null || memoryProps == null) {
            return;
        }
        if (!hasText(envelope.sessionId())) {
            return;
        }
        distillExecutor.execute(() -> runCompaction(envelope, memoryProps));
    }

    /**
     * 异步任务体：判断该不该压缩 → 读出待压缩轮次 → 调压缩器 → 校验 → 落库。
     *
     * <h2>覆盖区间是"读到了什么就声称覆盖什么"，不是"算出来该覆盖什么"</h2>
     * 正常情况两者相同：{@code [上一版的 through, 本会话窗口起点)} 里的每一轮都读得到，
     * 于是区间与公式一致。但当区间里<b>真的缺了某一轮</b>（行被清理、轮号有洞）时，
     * 按公式写就是<b>声称覆盖了一道从没读过的轮次</b> —— 而"每一轮要么在窗口里、
     * 要么在摘要里"这条完备性正是靠覆盖区间成立的，一句假话就能让那个洞永远查不出来。
     * 所以这里用<b>实读到的轮号</b>算区间，缺轮时留一条 WARN。
     * <p>
     * 方向性也顺带对了：区间只会<b>变窄</b>（更诚实），不会变宽（不会把没读到的轮次说成读过了）。
     *
     * <h2>⚠️ 校验不合格就整份丢弃，宁可让窗口滑走</h2>
     * 这是 A1 那条已知偏差在本类的落点，三条拒绝理由：
     * <ul>
     *   <li>压缩器返回 {@code null} / 空白 —— 模型失败，或它自己判定两次都违反了"不许出现事实性数字"</li>
     *   <li>超预算 —— <b>不截断</b>。截出来的定位信息是假的，而它会被长期携带</li>
     *   <li>结果为空 —— 没有任何定位能力</li>
     * </ul>
     * 三者的共同点是"<b>不落库</b>而不是"落一份差的"。差的那份会取代掉上一版<b>好</b>的摘要，
     * 于是"压缩失败"这个瞬时问题会升格成"上一版的定位能力也没了"这个持久问题。
     */
    private void runCompaction(SessionEnvelope envelope, MemoryProperties memoryProps) {
        try {
            LocalDateTime now = LocalDateTime.now();
            SessionSummaryRow previous = summaryDao.selectActive(envelope.sessionId(), now);

            // 归属复核：摘要是会话级的，而会话可能被换人用（会话串号，RAG-07 §三 威胁 D1）。
            // 此时**放弃整次压缩**而不是"当它不存在" —— 后者的下一步会插一份属于本用户的
            // 新摘要，同时把别人那一版留在原地（同一会话两条有效摘要，唯一键也会拒），
            // 而 coverage 区间还是从别人的 through 推出来的，纯属编造
            if (previous != null && !envelope.userId().equals(previous.userId())) {
                log.warn("摘要压缩放弃：本会话当前摘要归属他人 sessionId={}",
                        envelope.sessionId());
                return;
            }

            int from = previous == null ? 1 : previous.coversThroughTurnNo();
            int windowStart = windowStartTurnNo(envelope.sessionId(), memoryProps);
            if (windowStart <= 0) {
                // 窗口里一行都没有 ⇒ 谈不上"有轮次滑出窗口"（空会话、或这事发生在 L1 写入之前）
                return;
            }
            if (windowStart <= from) {
                // 没有新的轮次滑出窗口。**这是绝大多数轮次的正常路径** ——
                // 也是"每轮都提交一个任务"这个选择的代价所在：大多数任务到这里就返回了
                return;
            }

            List<ChatMessageRow> rows = chatMessageDao.selectTurnRange(
                    envelope.sessionId(), from, windowStart - 1);
            List<CompactionRequest.SourceTurn> sourceTurns = toSourceTurns(rows);
            if (sourceTurns.isEmpty()) {
                // 区间非空但一行都没读到。**返回而不是编一个区间出来**，见方法注释
                log.warn("摘要压缩跳过：[{}, {}] 区间内没有可读轮次 sessionId={}",
                        from, windowStart - 1, envelope.sessionId());
                return;
            }
            int coversFrom = sourceTurns.get(0).turnNo();
            int coversThrough = sourceTurns.get(sourceTurns.size() - 1).turnNo() + 1;
            if (coversThrough - coversFrom != windowStart - from) {
                log.warn("摘要覆盖区间与实读轮次不一致（区间内有洞）："
                                + "期望 [{} , {}) 实读 [{}, {}) sessionId={}",
                        from, windowStart, coversFrom, coversThrough, envelope.sessionId());
            }

            CompactionRequest request = new CompactionRequest(
                    previous == null ? null : previous.summaryText(),
                    sourceTurns, props.maxSummaryChars());
            String text = compactor.summarize(request);
            if (!hasText(text)) {
                log.warn("摘要压缩未产出可用文本（模型失败或两次都违规），本版不落库 sessionId={}",
                        envelope.sessionId());
                return;
            }
            if (text.length() > props.maxSummaryChars()) {
                log.warn("摘要压缩产出超预算，整份丢弃（不截断）：length={} budget={} sessionId={}",
                        text.length(), props.maxSummaryChars(), envelope.sessionId());
                return;
            }
            // ★ A1 校正的**最后一道闸门**：摘要里出现金额/比例/期限即丢弃。
            //   ⚠️ 与 LlmSummaryWriter 里那道检查是**刻意重复**的，不是冗余：
            //     ① 那边负责"打回重写一次"（只有它能再调模型），这边负责"落库前的终审"；
            //     ② 更重要的是，检查若只写在 seam 实现里，它就成了**某个实现的属性**而不是
            //        这一层的属性 —— 换一个实现、或测试里打桩掉实现，约束就消失了。
            //        放在这里，"摘要不许夹带事实性数字"才是 SessionSummaryService 的契约，
            //        链路测试打桩压缩器也照样能验（SummaryContainsNumberChainTest 正是这么做的）。
            //     这与 L2 读侧"SQL 下推 + Java 再复核一遍"是同一套手法。
            List<NumericExtractor.Mention> mentions = numericExtractor.extract(text);
            if (!mentions.isEmpty()) {
                // ⚠️ 只记**命中几处**，不记命中的原文 —— 与 FactRedlines 的审计纪律一致：
                //    把违规内容抄进日志等于给它又留了一份拷贝。要看命中的是哪几类，
                //    正常情况下 LlmSummaryWriter 那道检查已经拦下并记过详情了，
                //    能走到这里说明那道的"打回重写"也没救回来
                log.warn("摘要含事实性数字，整份丢弃（不落库、不截断）：命中 {} 处 sessionId={}",
                        mentions.size(), envelope.sessionId());
                return;
            }

            // 密级 = max(本次轮次的 max, 上一版摘要)。**模型无权输出**，见类注释
            int clearance = previous == null ? 1 : previous.clearanceLevel();
            for (ChatMessageRow row : rows) {
                clearance = Math.max(clearance, row.clearanceLevel());
            }

            LocalDateTime expiresAt = props.retentionDays() > 0
                    ? now.plusDays(props.retentionDays())
                    : null;
            SessionSummaryRow incoming = new SessionSummaryRow(UUID.randomUUID().toString(),
                    envelope.sessionId(), envelope.userId(),
                    coversFrom, coversThrough, clearance,
                    text, sourceTurns.size(),
                    now, null, null, envelope.authVersion(), expiresAt, null);

            SessionSummaryRow written = summaryWriterService.write(previous, incoming);
            if (written != null) {
                log.info("摘要压缩落库：sessionId={} covers=[{}, {}) 折叠 {} 轮 密级={} 字符数={}",
                        envelope.sessionId(), coversFrom, coversThrough,
                        sourceTurns.size(), clearance, text.length());
            }
        } catch (Exception e) {
            // ⚠️ 任务体不在 advisor 的栈上：外抛只会被 ThreadPoolExecutor 吞掉，
            // 症状是"什么都没发生" —— 比日志里的一条 WARN 难查得多
            log.warn("摘要压缩任务失败，本版不落库（不影响本轮回答）", e);
        }
    }

    /**
     * L1 窗口的起点轮号 = 窗口内最小的 {@code turn_no}；窗口为空返回 0。
     *
     * <h2>⚠️ 为什么用"再查一次"而不是复用 advisor 手里那份历史</h2>
     * advisor 手里那份是 {@code ChatMemoryService.loadRecentTurns} 的<b>过滤后</b>结果
     * （拒答轮、残轮、密级不过的轮次都已丢弃）。用它算窗口起点，得到的数会
     * <b>偏大</b>，于是 {@code through} 偏大、压缩区间把还在窗口里的轮次也折进去 ——
     * 那份"重叠"是无害的（同样的内容在摘要与历史里各出现一次），但<b>依赖一个副作用</b>：
     * 一旦过滤规则的改动方向反过来，重叠就变成缝隙，那才是静默丢内容。
     * 直接查原始的最近 N 行，得到的是窗口<b>本身</b>的起点，与过滤规则解耦。
     * <p>
     * 代价是每轮多一次索引查询（{@code session_id + turn_no DESC}），且它在异步线程上。
     * <p>
     * ⚠️ 另一个方向的不精确是<b>故意的</b>：这个查询发生在本轮 L1 写入<b>之后</b>，
     * 所以窗口里已经含本轮。窗口起点因此只会比"回答时的窗口"更靠后或相同 ——
     * 方向仍是"少折一轮"（更诚实），不会把没读到的轮次说成读过了。
     */
    private int windowStartTurnNo(String sessionId, MemoryProperties memoryProps) {
        List<ChatMessageRow> recent = chatMessageDao.selectRecentTurns(
                sessionId, memoryProps.promptRowLimit());
        int min = 0;
        for (ChatMessageRow row : recent) {
            if (min == 0 || row.turnNo() < min) {
                min = row.turnNo();
            }
        }
        return min;
    }

    /**
     * 行 → 压缩输入。按轮号归组，一轮一条。
     *
     * <h2>拒答轮只贡献"未获答复"标记</h2>
     * {@code answered = (answerDecision != REFUSED)}，且拒答轮的 {@code answerText} 传
     * {@code null} —— 两个理由（拒答文案没有定位价值、反复喂回去会强化拒答倾向）见
     * {@code CompactionRequest.SourceTurn}，这里不重复。但"用户问了 XX 未获答复"<b>要</b>记：
     * 下一轮用户说"那它呢"时那是唯一的定位线索。
     *
     * <h2>残轮直接丢</h2>
     * 没有 user 行的组（写到一半被打断）定位不出"用户问了什么"，塞进去只会消耗预算。
     * 与 L1"残轮整轮丢弃"同一取舍，只是这里丢的是"压缩输入"而不是"prompt 输入"。
     *
     * <h2>截断按<b>每条消息</b>算</h2>
     * {@code sourceTurnMaxChars} 分别作用在用户原话与答案上，所以一轮最多 2×。
     * 不合成一个"本轮总量"的理由：那样长答案会把用户原话挤成空 —— 而用户原话正是
     * 定位信息的主要来源（摘要是"用户问了什么"，不是"助手答了什么"）。
     * <b>真正的硬要求只是"输入有界"</b>，2× 仍然有界。
     */
    private List<CompactionRequest.SourceTurn> toSourceTurns(List<ChatMessageRow> rows) {
        Map<Integer, ChatMessageRow> userRows = new LinkedHashMap<>();
        Map<Integer, ChatMessageRow> assistantRows = new LinkedHashMap<>();
        for (ChatMessageRow row : rows) {
            if ("user".equals(row.role())) {
                userRows.put(row.turnNo(), row);
            } else if ("assistant".equals(row.role())) {
                assistantRows.put(row.turnNo(), row);
            }
        }

        int cap = props.sourceTurnMaxChars();
        List<CompactionRequest.SourceTurn> turns = new ArrayList<>(userRows.size());
        for (Map.Entry<Integer, ChatMessageRow> entry : userRows.entrySet()) {
            int turnNo = entry.getKey();
            ChatMessageRow user = entry.getValue();
            ChatMessageRow assistant = assistantRows.get(turnNo);
            boolean answered = user.answerDecision() != AnswerDecision.REFUSED;
            String answerText = answered && assistant != null
                    ? truncate(assistant.answerText(), cap)
                    : null;
            turns.add(new CompactionRequest.SourceTurn(turnNo,
                    truncate(user.userMessage(), cap), answerText, answered));
        }
        // ⚠️ **自己排序，不依赖 SQL 的 ORDER BY**：这里的顺序有两个消费方，
        // 而它们对错序的容忍度完全不同 ——
        //  ① 提示词里第 1 轮要排在第 5 轮前面（错序只是读起来别扭）
        //  ② 调用方用 get(0) / get(size-1) 算覆盖区间（**错序会算出错的区间**，
        //     而"每一轮要么在窗口里、要么在摘要里"这条完备性正是靠它成立的）
        // 同样的手法在 SemanticMemoryService.readReadable 里用过：两段查询各自有序，
        // 合起来不一定有序，所以整体重排。DAO 的 @Select 里确实写了 ORDER BY turn_no ASC，
        // 但"安全相关的区间"不该建立在"我记得它写了"之上。
        turns.sort(Comparator.comparingInt(CompactionRequest.SourceTurn::turnNo));
        return List.copyOf(turns);
    }

    /**
     * 超长截断 + 可见的省略标记。
     * <p>
     * 带标记而不是静默切掉：压缩器看到 {@code （后略）} 才知道"这里本来就断了"，
     * 不会试图把半句话补完 —— 补出来的就是编的。
     */
    private static String truncate(String text, int cap) {
        if (text == null || cap <= 0 || text.length() <= cap) {
            return text;
        }
        return text.substring(0, cap) + "（后略）";
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
