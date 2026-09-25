package org.agent.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.agent.memory.MemoryProperties;
import org.agent.memory.dao.ChatMessageDao;
import org.agent.memory.dao.ChatMessageRow;
import org.agent.memory.model.AnswerDecision;
import org.agent.memory.model.ChatTurn;
import org.agent.memory.model.CitedChunkRef;
import org.agent.memory.model.TurnFacts;
import org.agent.permission.SessionEnvelope;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L1 情景记忆的业务编排：读窗口 + 写一轮。
 *
 * <h2>一条策略，一个出口</h2>
 * "哪些轮次允许进 prompt"的全部规则都集中在 {@link #loadRecentTurns} 里。
 * 这样审计读取策略时只需要看一个方法，而不是满项目找散落的 if。
 *
 * <h2>这里刻意<b>没有</b>做什么（避免后来者误以为是漏了）</h2>
 * <ul>
 *   <li><b>没有按权限版本过滤</b>。{@code perm_auth_version} 只用于审计"这条记忆是在什么权限下产生的"，
 *       <b>不参与读取判定</b> —— 这是 RAG-07 §三 D3.3 与 §A.1 列注释写死的规则
 *       （"记忆可以过期，权限不能过期"）。而且 L1 的行里根本没有内容侧密级
 *       （{@code clearance_level} 是阶段 1 才加的列），"用本轮实况信封重判"无从下手。
 *       读历史时按权限过滤整个属于阶段 1「记忆过滤」的交付物。</li>
 *   <li><b>没有复用 {@code SessionAuthService.buildAccessFilter}</b>。那个 filter 是给向量召回用的
 *       （{@code kb_id in [...] && doc_level <= N && org_codes in [...]}），
 *       而 L1 是"按 session_id 结构化查询"，两者不是一回事。阶段 1 做 L2 语义记忆时才该复用它。</li>
 *   <li><b>没有吞异常</b>。DAO / JSON 解析失败一律向上抛，由 {@code MemoryAdvisor} 统一兜。
 *       隔离边界只有一处才好审计，也让本类的单测能干净地断言"抛出来了"而不是"被吞了"。</li>
 * </ul>
 */
@Service
public class ChatMemoryService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final ChatMessageDao dao;
    private final ObjectMapper objectMapper;

    public ChatMemoryService(ChatMessageDao dao, ObjectMapper objectMapper) {
        this.dao = dao;
        this.objectMapper = objectMapper;
    }

    /**
     * 读窗口：取最近 N 轮，按时间正序返回（供注入 prompt）。
     * <p>
     * 过滤规则，逐条都有理由：
     * <ol>
     *   <li><b>会话范围</b> —— SQL 已按 {@code session_id} 限定</li>
     *   <li><b>归属复核</b> —— {@code user_id} 与本轮信封不符的整轮丢弃。
     *       会话串号或脏数据的兜底，对应 RAG-07 §三 的威胁 D1</li>
     *   <li><b>拒答轮不进 prompt</b> —— 把"未在您的授权范围内检索到资料"这段文案喂回模型，
     *       只会强化它的拒答倾向，而且那段文案里没有任何可用于指代消解的事实</li>
     *   <li><b>残轮整轮丢弃</b> —— 一轮必须 user + assistant 都在。只有一半说明写到一半被打断了，
     *       半轮喂给模型会让它以为"用户问了但没人回答过"，比不给更糟</li>
     * </ol>
     * <p>
     * <b>已知损失</b>：规则 3 有个代价 —— 如果上一轮是拒答，用户紧接着问"那它呢？"，
     * 模型看不到指代对象。可接受（用户通常会重问），但这是个明确知道的损失，不是没想到。
     *
     * @param envelope 本轮实况权限信封（读取判定的唯一依据）
     */
    public List<ChatTurn> loadRecentTurns(SessionEnvelope envelope, MemoryProperties props) {
        List<ChatMessageRow> rows = dao.selectRecentTurns(envelope.sessionId(), props.promptRowLimit());
        if (rows.isEmpty()) {
            return List.of();
        }

        // 按 turn_no 归组。用 LinkedHashMap 保持 SQL 给出的顺序（turn_no DESC，最近的轮在前）
        Map<Integer, List<ChatMessageRow>> byTurn = new LinkedHashMap<>();
        for (ChatMessageRow row : rows) {
            byTurn.computeIfAbsent(row.turnNo(), k -> new ArrayList<>(2)).add(row);
        }

        List<ChatTurn> turns = new ArrayList<>(byTurn.size());
        for (List<ChatMessageRow> group : byTurn.values()) {
            ChatTurn turn = toTurn(group, envelope);
            if (turn != null) {
                turns.add(turn);
            }
        }

        // ⚠️ 反转的是**轮序**，不是整个列表 —— 轮内顺序（user 在 assistant 之前）
        // 已经在 toTurn 里定好了。对 rows 直接 reverse() 会把轮内顺序也翻掉。
        Collections.reverse(turns);
        return List.copyOf(turns);
    }

    /**
     * 写一轮：两行（user + assistant）共享同一个 turn_no，由 DAO 一条 INSERT 提交。
     * <p>
     * turn_no 在这里生成（会话内 {@code MAX + 1}）而不是在抽取侧 —— 它是存储细节，
     * 抽取方不该操心。并发下可能撞号，被唯一键拒掉后由上层失败隔离吞掉，
     * 详细取舍见 {@link ChatMessageDao#selectMaxTurnNo}。
     */
    public void appendTurn(TurnFacts facts, MemoryProperties props) {
        int turnNo = dao.selectMaxTurnNo(facts.sessionId()) + 1;
        LocalDateTime expiresAt = props.retentionEnabled()
                ? LocalDateTime.now().plusDays(props.retentionDays())
                : null;
        // 引文指针只挂在 assistant 行：问题是用户提的，引文是答案的
        String refsJson = toRefsJson(facts.citedRefs());

        dao.insertTurn(List.of(
                row(facts, turnNo, ROLE_USER, facts.userMessage(), null, null, expiresAt),
                row(facts, turnNo, ROLE_ASSISTANT, null, facts.answerText(), refsJson, expiresAt)));
    }

    /**
     * 轮级字段（decision / refusalReason / bizDate / authVersion）两行写入相同的值 ——
     * 这样"整轮"在 SQL 里就是一个可判定的单位，不会出现半轮里两行结论不一致。
     */
    private static ChatMessageRow row(TurnFacts facts, int turnNo, String role,
                                      String userMessage, String answerText, String refsJson,
                                      LocalDateTime expiresAt) {
        return new ChatMessageRow(facts.sessionId(), facts.userId(), turnNo, role,
                userMessage, answerText, refsJson,
                facts.decision(), facts.refusalReason(), facts.bizDate(), facts.authVersion(),
                null, expiresAt);
    }

    /** 组内挑出 user / assistant 两行；残轮或归属不符返回 null 表示丢弃 */
    private ChatTurn toTurn(List<ChatMessageRow> group, SessionEnvelope envelope) {
        ChatMessageRow user = null;
        ChatMessageRow assistant = null;
        for (ChatMessageRow row : group) {
            if (ROLE_USER.equals(row.role())) {
                user = row;
            } else if (ROLE_ASSISTANT.equals(row.role())) {
                assistant = row;
            }
        }
        if (user == null || assistant == null) {
            return null;
        }
        if (!envelope.userId().equals(user.userId())) {
            return null;
        }
        if (user.answerDecision() == AnswerDecision.REFUSED) {
            return null;
        }
        return new ChatTurn(user.turnNo(), user.userMessage(), assistant.answerText(),
                parseRefs(assistant.citedChunkRefs()));
    }

    private String toRefsJson(List<CitedChunkRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(refs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cited_chunk_refs 序列化失败", e);
        }
    }

    /**
     * 解析引文指针。解析失败向上抛（不静默返回空列表）—— 装作"这轮没引文"会掩盖数据损坏，
     * 而失败隔离边界在 {@code MemoryAdvisor}，那里会把整轮读取降级成"无历史"。
     */
    private List<CitedChunkRef> parseRefs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<CitedChunkRef>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cited_chunk_refs 解析失败", e);
        }
    }
}
