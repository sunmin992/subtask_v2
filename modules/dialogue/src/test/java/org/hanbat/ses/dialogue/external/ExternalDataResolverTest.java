package org.hanbat.ses.dialogue.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hanbat.ses.core.model.Range;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.model.VarType;
import org.hanbat.ses.core.resolve.OpenSlot;
import org.hanbat.ses.dialogue.control.ResolvedSlot;
import org.hanbat.ses.template.model.ValueSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 3단 폴백이 실제로 세 단인지 확인한다.
 *
 * <p>폴백은 "평소에는 보이지 않는 경로"라서 시험하지 않으면 조용히 썩는다. 1단이 늘 성공하는
 * 개발 환경에서는 2·3단이 한 번도 실행되지 않고, 정작 필요한 순간 — 외부 시스템이 멎었을 때 —
 * 처음 실행된다. 여기서는 각 단을 하나씩 꺼 가며 확인한다.
 */
class ExternalDataResolverTest {

    private static final ExternalQuery QUERY =
            new ExternalQuery("evcharge", "ev-charging", "급속기.평균충전시간", "평균충전시간", "분");

    // ------------------------------------------------------------ 폴백 순서

    @Test
    @DisplayName("세 단이 모두 살아 있으면 실시간 조회가 이긴다")
    void livePreferredOverEverythingElse() {
        Resolver r = resolverWith(
                fake("live-api", SourceTier.LIVE, 30.0),
                fake("bundled", SourceTier.BUNDLED, 34.0));

        ExternalValue value = r.resolver().resolve(QUERY).orElseThrow();

        assertThat(value.tier()).isEqualTo(SourceTier.LIVE);
        assertThat(value.value()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("실시간 조회가 답하면 캐시에 갈무리된다")
    void liveResultIsSnapshotted() {
        Resolver r = resolverWith(fake("live-api", SourceTier.LIVE, 30.0));

        r.resolver().resolve(QUERY);

        assertThat(r.cache().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("실시간이 죽으면 캐시가 답하고, 원 출처는 그대로 남는다")
    void cacheAnswersWhenLiveIsDown() {
        FakeProvider live = fake("live-api", SourceTier.LIVE, 30.0);
        Resolver r = resolverWith(live, fake("bundled", SourceTier.BUNDLED, 34.0));

        r.resolver().resolve(QUERY);       // 갈무리를 만든다
        live.down = true;                  // 외부 시스템이 멎는다

        ExternalValue value = r.resolver().resolve(QUERY).orElseThrow();

        assertThat(value.tier()).isEqualTo(SourceTier.CACHED);
        assertThat(value.value()).isEqualTo(30.0);
        // 어느 단을 거쳤는지와 누가 준 값인지는 둘 다 남아야 한다.
        assertThat(value.sourceId()).isEqualTo("live-api");
        assertThat(value.note()).contains("live-api");
    }

    @Test
    @DisplayName("실시간도 캐시도 없으면 내장 데이터셋까지 내려간다")
    void bundledIsTheLastResort() {
        Resolver r = resolverWith(
                down(fake("live-api", SourceTier.LIVE, 30.0)),
                fake("bundled", SourceTier.BUNDLED, 34.0));

        ExternalValue value = r.resolver().resolve(QUERY).orElseThrow();

        assertThat(value.tier()).isEqualTo(SourceTier.BUNDLED);
        assertThat(value.value()).isEqualTo(34.0);
    }

    @Test
    @DisplayName("세 단이 모두 답하지 못하면 빈 값 — 그다음은 질문이다")
    void emptyWhenNoTierAnswers() {
        Resolver r = resolverWith(down(fake("live-api", SourceTier.LIVE, 30.0)));

        assertThat(r.resolver().resolve(QUERY)).isEmpty();
    }

    @Test
    @DisplayName("주입 순서가 뒤바뀌어도 폴백 순서는 단계가 정한다")
    void tierOrderDoesNotDependOnInjectionOrder() {
        Resolver r = resolverWith(
                fake("bundled", SourceTier.BUNDLED, 34.0),
                fake("live-api", SourceTier.LIVE, 30.0));

        assertThat(r.resolver().resolve(QUERY).orElseThrow().tier())
                .isEqualTo(SourceTier.LIVE);
    }

    @Test
    @DisplayName("공급자가 예외를 던져도 대화는 멈추지 않는다")
    void providerFailureDoesNotPropagate() {
        // 규약은 "예외를 던지지 않는다"이지만, 규약을 어기는 구현이 하나 들어왔다고
        // 해서 대화 전체가 멎으면 폴백을 세 단으로 나눈 의미가 없다.
        ExternalDataProvider broken = new ExternalDataProvider() {
            @Override
            public String sourceId() {
                return "broken";
            }

            @Override
            public SourceTier tier() {
                return SourceTier.LIVE;
            }

            @Override
            public Optional<ExternalValue> lookup(ExternalQuery query) {
                throw new IllegalStateException("연결이 끊겼습니다");
            }
        };
        Resolver r = resolverWith(broken, fake("bundled", SourceTier.BUNDLED, 34.0));

        assertThatCode(() -> r.resolver().resolve(QUERY)).doesNotThrowAnyException();
        assertThat(r.resolver().resolve(QUERY).orElseThrow().tier())
                .isEqualTo(SourceTier.BUNDLED);
    }

    // ------------------------------------------------------------ 채택 조건

    @Test
    @DisplayName("단위가 어긋난 값은 채택하지 않는다 — 실행은 되고 숫자만 틀리는 종류의 버그다")
    void rejectsValueWithIncompatibleUnit() {
        Resolver r = resolverWith(
                fake("bundled", SourceTier.BUNDLED, 34.0, "대"));

        Map<String, ExternalDataResolver.Resolution> filled = r.resolver()
                .resolveAll("evcharge", "ev-charging", List.of(chargingTimeSlot(true)));

        assertThat(filled).isEmpty();
    }

    @Test
    @DisplayName("도메인이 추론을 허락하지 않은 슬롯은 건드리지 않는다")
    void skipsSlotsThatForbidInference() {
        Resolver r = resolverWith(fake("bundled", SourceTier.BUNDLED, 34.0));

        assertThat(r.resolver().resolveAll("evcharge", "ev-charging",
                List.of(chargingTimeSlot(false)))).isEmpty();
        assertThat(r.resolver().resolveAll("evcharge", "ev-charging",
                List.of(chargingTimeSlot(true)))).containsKey("급속기.평균충전시간");
    }

    @Test
    @DisplayName("채택된 값에는 출처가 붙는다")
    void acceptedValueCarriesProvenance() {
        Resolver r = resolverWith(fake("bundled", SourceTier.BUNDLED, 34.0));

        SlotProvenance p = r.resolver()
                .resolveAll("evcharge", "ev-charging", List.of(chargingTimeSlot(true)))
                .get("급속기.평균충전시간")
                .provenance();

        assertThat(p.source()).isEqualTo(FillSource.EXTERNAL_DATA);
        assertThat(p.tier()).isEqualTo(SourceTier.BUNDLED);
        assertThat(p.sourceId()).isEqualTo("bundled");
        assertThat(p.source().stated()).isFalse();
        assertThat(p.describe()).contains("외부 데이터").contains("내장 데이터셋");
    }

    // ------------------------------------------------------------ 조립

    private record Resolver(ExternalDataResolver resolver, SnapshotCacheProvider cache) {
    }

    private Resolver resolverWith(ExternalDataProvider... providers) {
        SnapshotCacheProvider cache =
                new SnapshotCacheProvider(ExternalDataProperties.disabled());
        List<ExternalDataProvider> all = new ArrayList<>(List.of(providers));
        all.add(cache);
        return new Resolver(new ExternalDataResolver(all, cache), cache);
    }

    private static FakeProvider fake(String id, SourceTier tier, Object value) {
        return fake(id, tier, value, "분");
    }

    private static FakeProvider fake(String id, SourceTier tier, Object value, String unit) {
        return new FakeProvider(id, tier, value, unit);
    }

    private static FakeProvider down(FakeProvider p) {
        p.down = true;
        return p;
    }

    private static final class FakeProvider implements ExternalDataProvider {

        private final String id;
        private final SourceTier tier;
        private final Object value;
        private final String unit;
        private boolean down;

        FakeProvider(String id, SourceTier tier, Object value, String unit) {
            this.id = id;
            this.tier = tier;
            this.value = value;
            this.unit = unit;
        }

        @Override
        public String sourceId() {
            return id;
        }

        @Override
        public SourceTier tier() {
            return tier;
        }

        @Override
        public boolean available() {
            return !down;
        }

        @Override
        public Optional<ExternalValue> lookup(ExternalQuery query) {
            return down ? Optional.empty()
                    : Optional.of(new ExternalValue(value, unit, id, tier,
                    Instant.parse("2026-06-30T00:00:00Z"), null));
        }
    }

    /** 급속기의 평균 충전시간 슬롯 하나. */
    private static ResolvedSlot chargingTimeSlot(boolean inferable) {
        VarDef var = new VarDef("평균충전시간", VarType.DOUBLE, "분", null, null, inferable,
                new Range(5.0, 120.0, List.of()));
        SesAnchor anchor = SesAnchor.variable(
                "충전소/충전기종류/급속충전기/충전기", "ent-fast-charger", "평균충전시간");
        ValueSlot spec = new ValueSlot("급속기.평균충전시간", anchor, VarType.DOUBLE, "분",
                new Range(5.0, 120.0, List.of()), 30.0, inferable, null, List.of());
        return new ResolvedSlot("급속기.평균충전시간",
                OpenSlot.value("충전소/충전기종류/급속충전기/충전기", anchor, var, 3), spec);
    }
}
