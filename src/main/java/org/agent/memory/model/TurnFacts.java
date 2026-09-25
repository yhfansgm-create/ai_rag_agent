package org.agent.memory.model;

import java.time.LocalDate;
import java.util.List;

/**
 * 本轮问答待落库的事实（写入侧模型）。
 * <p>
 * 与 {@link ChatTurn} 的区别：本类型是"刚发生的这一轮"，<b>还没有轮号</b> ——
 * turn_no 在写入时按会话内 {@code MAX(turn_no) + 1} 生成，属存储细节，不该由抽取方操心。
 * ChatTurn 则是"库里已经存在的某一轮"。
 *
 * @param sessionId   所属会话
 * @param userId      归属人工号（写入时快照；读取时用它做归属复核）
 * @param bizDate     本轮实际生效的业务日期（未传则为今天，口径复用 {@code RetrievalAdvisor}）
 * @param authVersion 本轮权限版本；<b>仅审计用，不参与读取过滤</b>（RAG-07 §三 D3.3）
 * @param userMessage 用户原话，<b>必须是未被 {@code augment()} 增强的那一份</b>（见 TurnExtractor）
 * @param answerText  最终答案全文；拒答轮存的是拒答文案
 * @param decision    本轮结论
 * @param refusalReason 拒答原因；{@code PROCEED} 轮为 null
 * @param citedRefs   本轮引文指针；拒答轮为空列表
 * @param clearanceLevel 该轮内容可能反映的最高密级，由 {@code MemoryWriteGuard} 算出。
 *                   <b>读取判定用</b>：读历史时按本轮实况信封比对，所以用户权限一旦降级，
 *                   旧轮的高密级内容就不再进 prompt（RAG-07 §一 表 #2）。
 *                   与 {@code authVersion} 的区别：那个只留痕，这个真的参与过滤
 */
public record TurnFacts(
        String sessionId,
        String userId,
        LocalDate bizDate,
        long authVersion,
        String userMessage,
        String answerText,
        AnswerDecision decision,
        String refusalReason,
        List<CitedChunkRef> citedRefs,
        int clearanceLevel
) {
}
