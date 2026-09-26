package org.agent.audit;

import org.agent.audit.AuditHashChain.AuditEntry;
import org.agent.audit.AuditHashChain.Mismatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 哈希链的单测。
 * <p>
 * 这组测试要证明的是<b>"篡改真的会被发现"</b>。一个永远返回"链完整"的校验器
 * 看起来和真的一模一样 —— 而它会让人误以为审计是可信的，那比没有审计更糟。
 * 所以这里的核心用例都是<b>先伪造、再校验</b>：改字段、改 prev_hash、重排、删行，各一条。
 */
class AuditHashChainTest {

    private static final List<String> P0 = List.of("FACT", "uid-1", "WRITE", "AUTO");
    private static final List<String> P1 = List.of("FACT", "uid-1", "SUPERSEDE", "AUTO");
    private static final List<String> P2 = List.of("SUMMARY", "uid-2", "WRITE", "AUTO");

    /** 按链序造一条完整的链（每条的 prevHash 指向前一条的 hash） */
    private static List<AuditEntry> chain(List<List<String>> payloads) {
        List<AuditEntry> entries = new ArrayList<>();
        String prev = null;
        for (List<String> payload : payloads) {
            String hash = AuditHashChain.hash(prev, payload);
            entries.add(new AuditEntry(prev, hash, payload));
            prev = hash;
        }
        return entries;
    }

    /** 造一条"prev_hash 被改过、且 hash 也照着重算过"的记录 —— 即一次像样的伪造尝试 */
    private static AuditEntry forged(String wrongPrevHash, List<String> payload) {
        return new AuditEntry(wrongPrevHash, AuditHashChain.hash(wrongPrevHash, payload), payload);
    }

    private static List<Mismatch> verify(List<AuditEntry> entries) {
        return AuditHashChain.verify(entries);
    }

    @Nested
    @DisplayName("完整性")
    class Intact {

        @Test
        @DisplayName("三条链式 → 无任何不一致")
        void cleanChain() {
            assertThat(verify(chain(List.of(P0, P1, P2)))).isEmpty();
        }

        @Test
        @DisplayName("单条（链首 prevHash 为 null）→ 干净")
        void singleEntry() {
            assertThat(verify(chain(List.of(P0)))).isEmpty();
        }

        @Test
        @DisplayName("空链 / null → 无不一致（「什么都没有」不是「不一致」）")
        void emptyChain() {
            assertThat(verify(List.of())).isEmpty();
            assertThat(verify(null)).isEmpty();
        }

        @Test
        @DisplayName("哈希是 64 位十六进制（SHA-256）")
        void hashShape() {
            assertThat(AuditHashChain.hash(null, P0)).matches("[0-9a-f]{64}");
        }
    }

    @Nested
    @DisplayName("★ 篡改：每一种都要被发现，且理由要对")
    class Tampering {

        @Test
        @DisplayName("改中间一行的字段、但没重算 hash（笨的改法）→ 那一条 HASH_MISMATCH")
        void tamperedPayloadIsCaughtByItsOwnHash() {
            List<AuditEntry> entries = new ArrayList<>(chain(List.of(P0, P1, P2)));
            // 攻击者把中间那条的动作从 SUPERSEDE 改成 WRITE，但没法把 hash 也改对
            entries.set(1, new AuditEntry(entries.get(1).prevHash(),
                    entries.get(1).hash(),
                    List.of("FACT", "uid-1", "WRITE", "AUTO")));

            assertThat(verify(entries)).containsExactly(
                    new Mismatch(1, Mismatch.Reason.HASH_MISMATCH));
        }

        @Test
        @DisplayName("★ 改内容**并重算自己的 hash**（像样的伪造）→ 变成它的下一条接不上")
        void forgedPayloadBreaksTheNextLink() {
            List<AuditEntry> entries = new ArrayList<>(chain(List.of(P0, P1, P2)));
            List<String> newPayload = List.of("FACT", "uid-1", "WRITE", "AUTO");

            // 重算了自己的 hash ⇒ 它自己看着完全正常，破绽落在**下一条的 prev_hash** 上。
            // 这正是两个理由必须分开的原因：同一个"改中间行"的动作，改法不同，
            // 露出的破绽在不同的行、不同的理由上。
            entries.set(1, new AuditEntry(entries.get(1).prevHash(),
                    AuditHashChain.hash(entries.get(1).prevHash(), newPayload),
                    newPayload));

            assertThat(verify(entries)).containsExactly(
                    new Mismatch(2, Mismatch.Reason.PREV_HASH_NOT_LINKED));
        }

        @Test
        @DisplayName("★ 诚实边界：把整条后缀都重算一遍 → 库内校验不出来（所以需要外部锚点）")
        void fullSuffixRewriteIsUndetectableWithinTheDatabase() {
            // 这不是缺陷，是这个机制的适用边界，必须写成一条测试而不是只写在注释里：
            // 有数据库写权限的人可以改动一行、然后把它**以及它之后所有行**的 hash 重算一遍。
            // 重算后的链在库内自洽 ⇒ verify 干净。要做到"改了必被发现"，
            // 需要链外的锚点（如每次追加后把 head hash 打到日志/外部存储）——
            // 那是生产加固，本阶段记为缺口（见 sql/memory_change_log.sql 头部）。
            List<AuditEntry> entries = new ArrayList<>(chain(List.of(P0, P1, P2)));

            List<String> p1 = List.of("FACT", "uid-1", "WRITE", "AUTO");
            String hash1 = AuditHashChain.hash(entries.get(0).hash(), p1);
            String hash2 = AuditHashChain.hash(hash1, P2);
            entries.set(1, new AuditEntry(entries.get(0).hash(), hash1, p1));
            entries.set(2, new AuditEntry(hash1, hash2, P2));

            assertThat(verify(entries)).isEmpty();
            assertThat(entries.get(1).payload()).as("内容确实被改过").isEqualTo(p1);
        }

        @Test
        @DisplayName("伪造一条 prev_hash 指向别处的记录（hash 也算对了）→ PREV_HASH_NOT_LINKED")
        void forgedPrevHashIsCaughtByTheLink() {
            List<AuditEntry> entries = new ArrayList<>(chain(List.of(P0, P1, P2)));
            entries.set(2, forged("f".repeat(64), P2));

            assertThat(verify(entries)).containsExactly(
                    new Mismatch(2, Mismatch.Reason.PREV_HASH_NOT_LINKED));
        }

        @Test
        @DisplayName("删掉中间一行 → 后面的接不上")
        void deletedRowBreaksTheChain() {
            List<AuditEntry> entries = chain(List.of(P0, P1, P2));
            entries.remove(1);

            assertThat(verify(entries)).containsExactly(
                    new Mismatch(1, Mismatch.Reason.PREV_HASH_NOT_LINKED));
        }

        @Test
        @DisplayName("重排行序 → 接不上（链的行序不靠物理 id，靠 prev_hash）")
        void reorderedRowsBreakTheChain() {
            List<AuditEntry> entries = chain(List.of(P0, P1, P2));
            List<AuditEntry> reordered = List.of(entries.get(1), entries.get(0), entries.get(2));

            assertThat(verify(reordered)).isNotEmpty();
        }

        @Test
        @DisplayName("把链首的 prevHash 改成看似有值 → 它自己接不上，而且它的下一条也接不上了")
        void fakeHeadIsCaught() {
            List<AuditEntry> entries = new ArrayList<>(chain(List.of(P0, P1)));
            entries.set(0, forged("a".repeat(64), P0));

            // ⚠️ 是两条，不是一条 —— 这一点很值得注意：
            // 伪造链首会**连带**改掉它自己的 hash（hash 里含 prevHash），
            // 于是下一条存着的 prevHash 也接不上了。也就是说越靠前的行被改，
            // 露出的破绽越多；反过来，越靠后改动越"安静"（见上面那条后缀重写的用例）。
            assertThat(verify(entries)).containsExactly(
                    new Mismatch(0, Mismatch.Reason.PREV_HASH_NOT_LINKED),
                    new Mismatch(1, Mismatch.Reason.PREV_HASH_NOT_LINKED));
        }
    }

    @Nested
    @DisplayName("★ 分隔符：把「ab」+「c」拼起来 vs 「a」+「bc」，必须哈希不同")
    class Separation {

        @Test
        @DisplayName("跨字段挪一个字符 → 哈希必须变（无分隔拼接的经典缺陷）")
        void fieldBoundariesAreRespected() {
            // 若实现是把字段直接拼起来，这两个输入会得到同一个哈希 ——
            // 于是一处内容改写可以被"把一个字符挪到隔壁字段"掩盖过去。
            String left = AuditHashChain.hash(null, List.of("ab", "c"));
            String right = AuditHashChain.hash(null, List.of("a", "bc"));

            assertThat(left).isNotEqualTo(right);
        }

        @Test
        @DisplayName("字段顺序变了 → 哈希也变（顺序是哈希的一部分）")
        void fieldOrderMatters() {
            assertThat(AuditHashChain.hash(null, List.of("a", "b")))
                    .isNotEqualTo(AuditHashChain.hash(null, List.of("b", "a")));
        }

        @Test
        @DisplayName("★ null 与空串必须哈希不同 —— 否则「把 NULL 改成空串」不留痕迹")
        void nullIsNotTheEmptyString() {
            List<String> withNull = Arrays.asList("a", null, "b");
            List<String> withEmpty = Arrays.asList("a", "", "b");

            assertThat(AuditHashChain.hash(null, withNull))
                    .isNotEqualTo(AuditHashChain.hash(null, withEmpty));
        }

        @Test
        @DisplayName("prevHash 参与哈希")
        void prevHashIsHashed() {
            assertThat(AuditHashChain.hash("aaa", P0))
                    .isNotEqualTo(AuditHashChain.hash("bbb", P0));
        }
    }

    @Nested
    @DisplayName("canonicalJson：与数据库的排键规则无关")
    class CanonicalJson {

        @Test
        @DisplayName("★ 对象键的顺序无关（这是「读回来重新校验不假失败」的前提）")
        void keyOrderIsIrrelevant() {
            String a = AuditHashChain.canonicalJson("{\"alpha\":1,\"zeta\":2}");
            String b = AuditHashChain.canonicalJson("{\"zeta\":2,\"alpha\":1}");

            assertThat(a).isEqualTo(b);
            assertThat(a).as("我们自己排键：字典序").isEqualTo("{\"alpha\":1,\"zeta\":2}");
        }

        @Test
        @DisplayName("★ 与 MySQL 读回来的形态也一致 —— 数据库排的是「短键在前」，我们排的是字典序")
        void matchesWhatTheDatabaseReturns() {
            // MySQL 把 {"alpha":1,"zeta":2} 存回来是 {"zeta": 2, "alpha": 1}（键短的在前）。
            // 两边都过本函数之后必须得到同一个串 —— 否则校验会假失败。
            String fromMysql = "{\"zeta\": 2, \"alpha\": 1}";

            assertThat(AuditHashChain.canonicalJson(fromMysql))
                    .isEqualTo(AuditHashChain.canonicalJson("{\"alpha\":1,\"zeta\":2}"));
        }

        @Test
        @DisplayName("空白无关；嵌套对象也递归排键；幂等")
        void whitespaceNestingAndIdempotence() {
            String messy = "{ \"b\" : { \"y\" : 2 , \"x\" : 1 } , \"a\" : [ 1 , 2 ] }";
            String canonical = AuditHashChain.canonicalJson(messy);

            assertThat(canonical).isEqualTo("{\"a\":[1,2],\"b\":{\"x\":1,\"y\":2}}");
            assertThat(AuditHashChain.canonicalJson(canonical))
                    .as("幂等：写入时算一次、读回来再算一次，必须一致")
                    .isEqualTo(canonical);
        }

        @Test
        @DisplayName("★ 数组的顺序保持不变（重排数组元素是真实的内容变化，不该被规范化抹平）")
        void arrayOrderIsPreserved() {
            assertThat(AuditHashChain.canonicalJson("[1,2]"))
                    .isNotEqualTo(AuditHashChain.canonicalJson("[2,1]"));
        }

        @Test
        @DisplayName("null / 空白 → null（调用方据此放进「没有值」的记号）")
        void nullAndBlank() {
            assertThat(AuditHashChain.canonicalJson(null)).isNull();
            assertThat(AuditHashChain.canonicalJson("   ")).isNull();
        }

        @Test
        @DisplayName("★ 非法 JSON 直接抛 —— 不退回原始字符串（否则哈希建立在一段未规范化的文本上）")
        void malformedJsonThrows() {
            assertThatThrownBy(() -> AuditHashChain.canonicalJson("{不是 JSON"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
