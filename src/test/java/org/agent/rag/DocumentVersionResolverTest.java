package org.agent.rag;

import org.agent.rag.DocumentVersionResolver.Adjustment;
import org.agent.rag.DocumentVersionResolver.DatedDoc;
import org.agent.rag.DocumentVersionResolver.Resolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入库版本收敛测试：哪版是现行的、旧版本失效日怎么推导。
 * 纯函数，不碰向量库。
 */
class DocumentVersionResolverTest {

    private final DocumentVersionResolver resolver = new DocumentVersionResolver();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 19);

    private static DatedDoc doc(String id, String version, String from, String until) {
        return new DatedDoc(id, version,
                from == null ? null : LocalDate.parse(from),
                until == null ? null : LocalDate.parse(until));
    }

    @Test
    @DisplayName("两个版本都没写失效日 → 旧版本失效日 = 现行版生效日 − 1 天，区间严丝合缝不重叠")
    void twoVersions_olderGetsDerivedUntil() {
        List<DatedDoc> docs = List.of(
                doc("D-1", "1.0", "2024-01-01", null),
                doc("D-1", "2.0", "2024-07-01", null));

        Resolution r = resolver.resolve(docs, TODAY);

        // 新版不动
        assertThat(r.adjustments().get(1).current()).isTrue();
        assertThat(r.adjustments().get(1).effectiveUntil()).isNull();
        // 旧版被推导出失效日，且正好是新版生效日的前一天
        Adjustment old = r.adjustments().get(0);
        assertThat(old.current()).isFalse();
        assertThat(old.effectiveUntil()).isEqualTo(LocalDate.of(2024, 6, 30));
        assertThat(old.supersededBy()).isEqualTo("2.0");
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    @DisplayName("作者已声明旧版本失效日 → 尊重人工判断，不覆盖")
    void authorDeclaredUntil_wins() {
        List<DatedDoc> docs = List.of(
                doc("D-1", "1.0", "2024-01-01", "2024-05-31"),   // 作者写了 5/31 失效
                doc("D-1", "2.0", "2024-07-01", null));

        Resolution r = resolver.resolve(docs, TODAY);

        assertThat(r.adjustments().get(0).effectiveUntil()).isNull();   // 不改写
        assertThat(r.adjustments().get(0).current()).isFalse();
        assertThat(r.adjustments().get(0).supersededBy()).isEqualTo("2.0");
        // 5/31 早于新版生效日 7/1 → 中间有"真空期"，但它不重叠，不算异常，只是不告警
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    @DisplayName("作者声明的失效日晚于现行版生效日 → 两版重叠，必须告警（合规不能出现两条并行的现行条款）")
    void overlappingRange_warns() {
        List<DatedDoc> docs = List.of(
                doc("D-1", "1.0", "2024-01-01", "2024-12-31"),   // 作者漏改了失效日
                doc("D-1", "2.0", "2024-07-01", null));

        Resolution r = resolver.resolve(docs, TODAY);

        assertThat(r.warnings()).hasSize(1);
        assertThat(r.warnings().get(0)).contains("重叠期").contains("D-1");
    }

    @Test
    @DisplayName("现行版没写生效日 → 按今天倒推，并告警说明结果依赖入库当天")
    void currentWithoutFromDate_fallsBackToTodayAndWarns() {
        List<DatedDoc> docs = List.of(
                doc("D-1", "1.0", "2024-01-01", null),
                doc("D-1", "2.0", null, null));                   // 新版没写生效日

        Resolution r = resolver.resolve(docs, TODAY);

        // 都没生效日 → 退回比版本号，2.0 > 1.0
        assertThat(r.adjustments().get(1).current()).isTrue();
        assertThat(r.adjustments().get(0).effectiveUntil()).isEqualTo(TODAY.minusDays(1));
        assertThat(r.warnings()).hasSize(1);
        assertThat(r.warnings().get(0)).contains("未声明 effective_from").contains("2026-09-19");
    }

    @Test
    @DisplayName("版本号按数字段比较：1.10 比 1.9 新（纯字符串比较会判反）")
    void versionCompare_isNumericAware() {
        assertThat(DocumentVersionResolver.compareVersion("1.10", "1.9")).isPositive();
        assertThat(DocumentVersionResolver.compareVersion("1.9", "1.10")).isNegative();
        assertThat(DocumentVersionResolver.compareVersion("1.2", "1.2")).isZero();
        assertThat(DocumentVersionResolver.compareVersion("v2", "v10")).isNegative();
        assertThat(DocumentVersionResolver.compareVersion("2", "v2")).isNegative();  // 数字段排字母段前
        assertThat(DocumentVersionResolver.compareVersion(null, "1")).isNegative();
        assertThat(DocumentVersionResolver.compareVersion(null, null)).isZero();
    }

    @Test
    @DisplayName("生效日优先于版本号判定现行版（补发的旧版号可能是回溯生效的）")
    void effectiveFromBeatsVersionNumber() {
        List<DatedDoc> docs = List.of(
                doc("D-1", "2024.2", "2024-01-01", null),   // 版本号大，但生效日早
                doc("D-1", "2024.1", "2024-07-01", null));  // 版本号小，但生效日晚 → 它才是现行版

        Resolution r = resolver.resolve(docs, TODAY);

        assertThat(r.adjustments().get(1).current()).isTrue();
        assertThat(r.adjustments().get(0).effectiveUntil()).isEqualTo(LocalDate.of(2024, 6, 30));
    }

    @Test
    @DisplayName("两版同一天生效 → 推不出有效区间，告警提示 front-matter 日期填重了")
    void sameEffectiveDate_warns() {
        List<DatedDoc> docs = List.of(
                doc("D-1", "1.0", "2024-07-01", null),
                doc("D-1", "2.0", "2024-07-01", null));   // 同日换版：旧版失效日会被推成 6/30，早于自己的生效日

        Resolution r = resolver.resolve(docs, TODAY);

        // 生效日打平 → 版本号裁决，2.0 为现行版
        assertThat(r.adjustments().get(1).current()).isTrue();
        assertThat(r.adjustments().get(0).effectiveUntil()).isEqualTo(LocalDate.of(2024, 6, 30));
        assertThat(r.warnings()).hasSize(1);
        assertThat(r.warnings().get(0)).contains("区间为空").contains("从未生效");
    }

    @Test
    @DisplayName("单版本、缺 doc_id、跨 doc_id 都不互相干扰")
    void nonVersionedDocsUntouched() {
        List<DatedDoc> docs = List.of(
                doc("D-1", "1.0", "2024-01-01", null),      // 独苗
                doc(null, "1.0", "2024-01-01", null),        // 没有 doc_id，不可判版本
                doc("D-2", "1.0", "2024-01-01", null),       // 另一个文档
                doc("D-2", "2.0", "2024-07-01", null));

        Resolution r = resolver.resolve(docs, TODAY);

        assertThat(r.adjustments().get(0).effectiveUntil()).isNull();   // 独苗不动
        assertThat(r.adjustments().get(1).effectiveUntil()).isNull();   // 缺 doc_id 不动
        assertThat(r.adjustments().get(2).effectiveUntil()).isEqualTo(LocalDate.of(2024, 6, 30));
        assertThat(r.adjustments()).hasSize(4);
    }

    @Test
    @DisplayName("三版本 → 中间那版也拿到失效日，逐段首尾相接")
    void threeVersions_chainCorrectly() {
        List<DatedDoc> docs = List.of(
                doc("D-1", "1.0", "2024-01-01", null),
                doc("D-1", "2.0", "2024-07-01", null),
                doc("D-1", "3.0", "2025-01-01", null));

        Resolution r = resolver.resolve(docs, TODAY);

        assertThat(r.adjustments().get(0).effectiveUntil()).isEqualTo(LocalDate.of(2024, 6, 30));
        assertThat(r.adjustments().get(1).effectiveUntil()).isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(r.adjustments().get(2).effectiveUntil()).isNull();
        assertThat(r.adjustments().get(2).current()).isTrue();
    }
}
