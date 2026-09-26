package org.agent.memory.dao;

import org.agent.memory.model.MemoryActorType;
import org.agent.memory.model.MemoryChangeAction;
import org.agent.memory.model.MemoryEntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试：连真实 MySQL 验证 {@code memory_change_log} 的追加与读回，不调 LLM。
 * <p>
 * <b>前置条件</b>：本机 MySQL(127.0.0.1:3306/fan_ai_agent) 在运行，且已执行
 * {@code sql/memory_change_log.sql} 建表（⚠️ 带 {@code --default-character-set=utf8mb4}，见该文件头）。
 * <p>
 * <b>这个 DAO 层最值得验的三件事</b>：
 * <ol>
 *   <li><b>枚举列存的是名字不是序号</b> —— 若哪天有人给枚举加上 {@code @EnumValue} 或改成
 *       序号存储，往枚举中间插一项就会让<b>整张审计表的历史记录语义漂移</b>，
 *       而且是静默的。所以这里直接断言列里的字面量</li>
 *   <li><b>JSON 列经 MySQL 往返后被规范化了</b>（重排键、抹空白）—— 这条直接决定了
 *       {@code AuditHashChain} 必须先规范化再哈希。不这么做的话，事后 SELECT 回来重新校验
 *       会<b>假失败</b>，而"假失败"比"不校验"更糟：它会让真篡改淹没在噪音里</li>
 *   <li><b>{@code GUARD_REJECT} 行的 {@code fact_uid} 可以为 NULL</b> —— 这是对 RAG-07 §A.3
 *       的显式偏离（拒写的候选从未成为一条记忆，NOT NULL 会逼出一个假 id）</li>
 * </ol>
 * <b>本表是全库共享的 append-only 表</b>（没有 session/owner 那样的天然隔离维度），
 * 所以这里<b>不</b>断言"表里只有我这几行" —— 那种断言会依赖别处没有写入，
 * 是跨测试的隐性耦合。所有断言都做成"相对参照点"的形式（见
 * {@code selectHeadHashForUpdate} 那条：先读参照点，再插自己，再断言）。
 */
@SpringBootTest(properties = "rag.vectorstore.enabled=false")
@ActiveProfiles("local")
class MemoryChangeLogDaoContextTest {

    @Autowired
    private MemoryChangeLogDao dao;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("往返：三个枚举列 + 两个 JSON 列 + 时间列都能原样读回")
    void insertAndReadBackRoundTrip() {
        String uid = UUID.randomUUID().toString();
        LocalDateTime now = now();
        MemoryChangeLogRow row = new MemoryChangeLogRow(
                MemoryEntityType.FACT, uid, MemoryChangeAction.SUPERSEDE,
                "{\"factValue\":\"对公\",\"validTo\":null}",
                "{\"factValue\":\"对私\",\"validTo\":\"" + now + "\"}",
                MemoryActorType.AUTO, null, "VALUE_CHANGED",
                "a".repeat(64), "b".repeat(64), now);

        assertThat(dao.insert(row)).isEqualTo(1);

        MemoryChangeLogRow got = dao.selectChain().stream()
                .filter(r -> uid.equals(r.factUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("插入的审计行没读回来"));

        assertThat(got.entityType()).isEqualTo(MemoryEntityType.FACT);
        assertThat(got.action()).isEqualTo(MemoryChangeAction.SUPERSEDE);
        assertThat(got.actorType()).isEqualTo(MemoryActorType.AUTO);
        assertThat(got.actorId()).isNull();
        assertThat(got.reason()).isEqualTo("VALUE_CHANGED");
        assertThat(got.prevHash()).isEqualTo("a".repeat(64));
        assertThat(got.hash()).isEqualTo("b".repeat(64));
        // ⚠️ createdAt 用的是 Java 传进去的值，不是 DB 的 CURRENT_TIMESTAMP ——
        // 时间戳必须在哈希的保护范围内，否则可以被随意改而不破坏链
        assertThat(got.createdAt()).isEqualTo(now);
        assertThat(got.beforeJson()).contains("对公");
        assertThat(got.afterJson()).contains("对私");
    }

    @Test
    @DisplayName("枚举列存的是**名字**，不是序号（防枚举重排导致历史记录语义漂移）")
    void enumColumnsStoreNamesNotOrdinals() {
        String uid = UUID.randomUUID().toString();
        dao.insert(new MemoryChangeLogRow(
                MemoryEntityType.SUMMARY, uid, MemoryChangeAction.GUARD_REJECT,
                null, "{\"factKey\":\"business_line\",\"pattern\":\"ID_CARD\",\"valueLength\":18}",
                MemoryActorType.SWEEP, "10086", "REDLINE_VALUE",
                null, "c".repeat(64), now()));

        String actionColumn = jdbcTemplate.queryForObject(
                "SELECT action FROM memory_change_log WHERE fact_uid = ?", String.class, uid);
        String entityColumn = jdbcTemplate.queryForObject(
                "SELECT entity_type FROM memory_change_log WHERE fact_uid = ?", String.class, uid);
        String actorColumn = jdbcTemplate.queryForObject(
                "SELECT actor_type FROM memory_change_log WHERE fact_uid = ?", String.class, uid);

        assertThat(actionColumn).isEqualTo("GUARD_REJECT");
        assertThat(entityColumn).isEqualTo("SUMMARY");
        assertThat(actorColumn).isEqualTo("SWEEP");
    }

    @Test
    @DisplayName("GUARD_REJECT 行：fact_uid 为 NULL（拒写的候选从未成为一条记忆，没有键可指）")
    void guardRejectRowHasNullFactUid() {
        // 这是对 RAG-07 §A.3「memory_id BIGINT NOT NULL」的显式偏离：
        // 在 NOT NULL 约束下只能编一个假 id，那比留空更糟 —— 审计里会凭空多出
        // 一堆指向不存在记忆的"外键"，事后根本分不出哪些是真记忆
        dao.insert(new MemoryChangeLogRow(
                MemoryEntityType.FACT, null, MemoryChangeAction.GUARD_REJECT,
                null, "{\"factKey\":\"business_line\",\"pattern\":\"BANK_CARD\",\"valueLength\":19}",
                MemoryActorType.AUTO, null, "REDLINE_VALUE",
                "d".repeat(64), "e".repeat(64), now()));

        List<MemoryChangeLogRow> chain = dao.selectChain();

        assertThat(chain).isNotEmpty();
        MemoryChangeLogRow last = chain.get(chain.size() - 1);
        assertThat(last.factUid()).isNull();
        assertThat(last.action()).isEqualTo(MemoryChangeAction.GUARD_REJECT);
        // 被拒的值（真实场景里会是那串银行卡号本身）**根本没有出现在这里** ——
        // after_json 只有这三个键。审计记录的是"拦下了什么类别、多长"，
        // 不是"拦下的内容"：把内容抄进去等于把疑似凭证又存了一份。
        // 顺带这条断言也覆盖了 JSON 列被 MySQL 规范化后的形状（键按字母序、冒号后一个空格）
        assertThat(last.afterJson())
                .isEqualTo("{\"factKey\": \"business_line\", \"pattern\": \"BANK_CARD\", \"valueLength\": 19}");
    }

    @Test
    @DisplayName("JSON 列经 MySQL 规范化：键按「先短后长、同长字典序」重排，空白被抹掉")
    void jsonColumnsAreNormalizedByMysql() {
        String uid = UUID.randomUUID().toString();
        // 故意写成**字典序**的样子 —— 这是最常见的规范化假设（Java 的 TreeMap 就给你这个），
        // 也正是本测试要证明"不能这么假设"的那个顺序
        String written = "{\"alpha\": 1,   \"zeta\": 2}";
        dao.insert(new MemoryChangeLogRow(
                MemoryEntityType.FACT, uid, MemoryChangeAction.WRITE,
                null, written,
                MemoryActorType.AUTO, null, null,
                null, "f".repeat(64), now()));

        String got = dao.selectChain().stream()
                .filter(r -> uid.equals(r.factUid()))
                .findFirst().orElseThrow().afterJson();

        // 实测结果：MySQL 的 JSON 对象**不是**按字典序存键，而是"键短的在前，同长再按字典序"
        // （zeta=4 < alpha=5，所以 zeta 跑到前面去了）。空白也被规范化成"冒号后一个空格"。
        assertThat(got)
                .as("写进去的字符串读不回来：键被重排、空白被抹掉")
                .isEqualTo("{\"zeta\": 2, \"alpha\": 1}")
                .isNotEqualTo(written);
        // 这条测试的全部意义：哈希绝不能直接吃"写入时那个字符串"，也绝不能假设
        // "MySQL 会按字典序存" —— 那样事后 SELECT 回来重新算哈希会**假失败**。
        // 而假失败比不校验更糟：它会让真篡改淹没在噪音里。
        // 对策是两边都过同一个 AuditHashChain.canonicalJson（它自己排键，不依赖 DB 的顺序），
        // 于是 canonical(读回的) == canonical(写入的) —— 幂等性在 AuditHashChain 那层验。
    }

    @Test
    @DisplayName("selectHeadHashForUpdate：返回链尾的 hash（先读参照点，再插自己，再断言）")
    void selectHeadHashForUpdateReturnsLatest() {
        // 本表是全库共享的 append-only 表，不能断言"表里只有我这些行"。
        // 所以做成相对参照点：只要两次调用之间只有我自己写入，断言就成立且不依赖其它测试
        String before = dao.selectHeadHashForUpdate();

        String uid = UUID.randomUUID().toString();
        String myHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        dao.insert(new MemoryChangeLogRow(
                MemoryEntityType.FACT, uid, MemoryChangeAction.WRITE,
                null, null, MemoryActorType.AUTO, null, null,
                before, myHash, now()));

        assertThat(dao.selectHeadHashForUpdate())
                .as("链尾 = 我刚插的那条的 hash，且我把它接到了读到的参照点之后")
                .isEqualTo(myHash);
    }

    @Test
    @DisplayName("selectChain：按 id ASC 返回，链上每条都能看到自己的 prev_hash")
    void selectChainReturnsRowsInInsertionOrder() {
        String uid = UUID.randomUUID().toString();
        String rootHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        dao.insert(new MemoryChangeLogRow(MemoryEntityType.FACT, uid, MemoryChangeAction.WRITE,
                null, null, MemoryActorType.AUTO, null, null, null, rootHash, now()));
        dao.insert(new MemoryChangeLogRow(MemoryEntityType.FACT, uid, MemoryChangeAction.SUPERSEDE,
                null, null, MemoryActorType.AUTO, null, null, rootHash, "9".repeat(64), now()));

        List<MemoryChangeLogRow> mine = dao.selectChain().stream()
                .filter(r -> uid.equals(r.factUid())).toList();

        assertThat(mine).hasSize(2);
        // id ASC ⇒ 先插的先出现。链的行序由 prev_hash 显式给出，id 只是全序的载体
        assertThat(mine.get(0).action()).isEqualTo(MemoryChangeAction.WRITE);
        assertThat(mine.get(0).prevHash()).isNull();
        assertThat(mine.get(1).action()).isEqualTo(MemoryChangeAction.SUPERSEDE);
        assertThat(mine.get(1).prevHash())
                .as("后一条的 prev_hash 指向前一条的 hash —— 这就是链")
                .isEqualTo(rootHash);
    }

    // ------------------------------------------------------------------

    /** 截到秒：DATETIME 无小数秒，带纳秒写进去再读回来会不等 */
    private static LocalDateTime now() {
        return LocalDateTime.now().withNano(0);
    }
}
