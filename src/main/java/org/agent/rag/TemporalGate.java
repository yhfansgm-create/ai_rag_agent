package org.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Map;

/**
 * 时效门禁 —— 判断一篇文档在"业务发生日"当天是否有效。
 * <p>
 * 银行 RAG 与互联网 RAG 最致命的差异之一：制度会废止、会被新版替代。
 * 引用一份已废止的文件去指导今天的业务，是事故级缺陷，而绝大多数 RAG demo 都没做这件事。
 * <p>
 * 判定区间为<b>闭区间</b> {@code [effective_from, effective_until]}：
 * <ul>
 *   <li>{@code bizDate < effective_from} → 尚未生效</li>
 *   <li>{@code bizDate > effective_until} → 已废止（until 当天仍算有效）</li>
 *   <li>字段缺失 → {@link State#UNDATED} <b>放行</b></li>
 * </ul>
 * <b>为什么缺字段是放行而不是拒绝</b>：权限必须 fail-closed（查不到授权就不给看），
 * 但时效若 fail-closed，存量文档会因为没补时效字段而<b>整体检索不到</b>，PoC 会直接变成空库。
 * 所以缺字段按"未声明时效"放行并留痕，由入库侧逐步补齐 —— 这是务实取舍，不是疏漏。
 */
@Slf4j
@Component
public class TemporalGate {

    /** chunk metadata 键：生效起始日（ISO-8601 字符串 yyyy-MM-dd） */
    public static final String META_EFFECTIVE_FROM = "effective_from";
    /** chunk metadata 键：失效日（ISO-8601 字符串 yyyy-MM-dd） */
    public static final String META_EFFECTIVE_UNTIL = "effective_until";

    public enum State {
        /** 生效区间覆盖业务日期，可用 */
        EFFECTIVE,
        /** 尚未生效 */
        NOT_YET,
        /** 已废止 */
        EXPIRED,
        /** 未声明时效字段 —— 放行 */
        UNDATED
    }

    /** 该文档在 {@code bizDate} 当天是否可用（UNDATED / EFFECTIVE 均视为可用） */
    public boolean usable(Map<String, Object> metadata, LocalDate bizDate) {
        State s = stateOf(metadata, bizDate);
        return s == State.EFFECTIVE || s == State.UNDATED;
    }

    public State stateOf(Map<String, Object> metadata, LocalDate bizDate) {
        return judge(parse(metadata.get(META_EFFECTIVE_FROM)),
                parse(metadata.get(META_EFFECTIVE_UNTIL)),
                bizDate);
    }

    static State judge(LocalDate from, LocalDate until, LocalDate bizDate) {
        if (from == null && until == null) {
            return State.UNDATED;
        }
        // 脏数据：起始晚于结束，区间不自洽 —— 无法判断，按未声明处理并留痕
        if (from != null && until != null && from.isAfter(until)) {
            log.warn("时效区间非法（effective_from={} 晚于 effective_until={}），按未声明时效放行", from, until);
            return State.UNDATED;
        }
        if (from != null && bizDate.isBefore(from)) {
            return State.NOT_YET;
        }
        if (until != null && bizDate.isAfter(until)) {
            return State.EXPIRED;
        }
        return State.EFFECTIVE;
    }

    /**
     * 解析 metadata 里的日期：入库统一写 ISO 字符串，但容忍 YAML/SnakeYAML 可能给出的 Date 与 LocalDate。
     * 无法解析 → null（按未声明处理，不让脏数据把文档"判死"）。
     */
    public static LocalDate parse(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate d) {
            return d;
        }
        if (raw instanceof Date d) {
            return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        String s = String.valueOf(raw).strip();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            log.warn("无法解析的日期字段：{}，按未声明时效处理", s);
            return null;
        }
    }

    /** LocalDate → ISO-8601 字符串（yyyy-MM-dd）。jsonb 中字符串字典序等价于时间序，便于下推过滤。 */
    public static String iso(LocalDate d) {
        return d == null ? null : d.toString();
    }
}
