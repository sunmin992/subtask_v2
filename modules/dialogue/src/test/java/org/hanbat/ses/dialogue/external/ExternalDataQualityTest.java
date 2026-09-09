package org.hanbat.ses.dialogue.external;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hanbat.ses.core.model.*;
import org.hanbat.ses.core.resolve.OpenSlot;
import org.hanbat.ses.dialogue.control.ResolvedSlot;
import org.hanbat.ses.template.model.ValueSlot;
import org.junit.jupiter.api.Test;

class ExternalDataQualityTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ExternalDataProperties policy = ExternalDataProperties.disabled();
    private final SnapshotCacheProvider cache = new SnapshotCacheProvider(policy, CLOCK);
    private final ExternalQuery query = new ExternalQuery("ev", "sim", "time", "time", "분");

    private ExternalDataProvider provider(SourceTier tier, Object value, String unit, Instant observed) {
        return new ExternalDataProvider() {
            public String sourceId() { return tier.name(); }
            public SourceTier tier() { return tier; }
            public Optional<ExternalValue> lookup(ExternalQuery q) {
                return Optional.of(new ExternalValue(value, unit, tier.name(), tier, observed, "reference"));
            }
        };
    }
    private ExternalDataResolver resolver(ExternalDataProvider live) {
        return new ExternalDataResolver(List.of(live, cache,
                provider(SourceTier.BUNDLED, 34.0, "분", NOW)), cache, policy, CLOCK);
    }
    private ResolvedSlot slot() {
        VarDef var = VarDef.of("time", VarType.DOUBLE, "분", Range.between(5,600));
        SesAnchor anchor = SesAnchor.variable("station", "charger", "time");
        return new ResolvedSlot("time", OpenSlot.value("station", anchor, var, 1),
                new ValueSlot("time", anchor, VarType.DOUBLE, "분", Range.between(5,600),
                        30.0, true, null, List.of()));
    }
    private ExternalDataResolver.Resolution resolve(ExternalDataResolver r) {
        return r.resolveAll("ev", "sim", List.of(slot())).get("time");
    }

    @Test void convertsHoursBeforeCheckingRangeAndCaching() {
        var result = resolve(resolver(provider(SourceTier.LIVE, 6, "시간", NOW)));
        assertThat(result.value()).isEqualTo(360.0);
        assertThat(result.provenance().quality().reasons()).contains("UNIT_CONVERTED");
        assertThat(cache.lookup(query).orElseThrow().value()).isEqualTo(360.0);
        assertThat(cache.lookup(query).orElseThrow().unit()).isEqualTo("분");
    }
    @Test void invalidLiveDoesNotOverwriteHealthyCache() {
        cache.store(query, new ExternalValue(30.0, "분", "healthy", SourceTier.LIVE, NOW.minusSeconds(30), null));
        for (Object bad : List.of(-1, "30", Double.NaN, Double.POSITIVE_INFINITY, 601, Map.of("value",30))) {
            var result = resolve(resolver(provider(SourceTier.LIVE, bad, "분", NOW)));
            assertThat(result.value()).isEqualTo(30.0);
            assertThat(result.provenance().tier()).isEqualTo(SourceTier.CACHED);
            assertThat(cache.lookup(query).orElseThrow().sourceId()).isEqualTo("healthy");
        }
    }
    @Test void wrongUnitFallsThroughToBundled() {
        var result = resolve(resolver(provider(SourceTier.LIVE, 30.0, "대", NOW)));
        assertThat(result.value()).isEqualTo(34.0);
        assertThat(result.provenance().quality().reasons()).contains("UNIT_MISMATCH");
        assertThat(cache.size()).isZero();
    }
    @Test void missingAndFutureObservationAreRejected() {
        for (Instant observed : new Instant[]{null, NOW.plusSeconds(3600)}) {
            assertThat(resolve(resolver(provider(SourceTier.LIVE, 30.0, "분", observed)))
                    .provenance().tier()).isEqualTo(SourceTier.BUNDLED);
        }
    }
    @Test void currentStatusNeverUsesOldCacheOrBundledData() {
        cache.store(query, new ExternalValue(30.0, "분", "healthy", SourceTier.LIVE, NOW.minusSeconds(121), null));
        var r = resolver(provider(SourceTier.LIVE, 30.0, "분", NOW.minusSeconds(121)));
        assertThat(r.resolve(query, DataUsage.CURRENT_STATUS)).isEmpty();
        assertThat(cache.size()).isEqualTo(1);
    }
    @Test void recentCacheCanAnswerCurrentQuery() {
        cache.store(query, new ExternalValue(30.0, "분", "healthy", SourceTier.LIVE, NOW.minusSeconds(60), null));
        var r = resolver(provider(SourceTier.LIVE, -1, "분", NOW));
        var value = r.resolve(query, DataUsage.CURRENT_STATUS).orElseThrow();
        assertThat(value.tier()).isEqualTo(SourceTier.CACHED);
        assertThat(value.observedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(value.note()).contains("1분 전");
    }
    @Test void staleReferenceIsMarkedAndUsedOnlyForSimulation() {
        var r = new ExternalDataResolver(List.of(provider(SourceTier.BUNDLED, 34.0, "분",
                NOW.minusSeconds(86400L * 181))), cache, policy, CLOCK);
        var value = resolve(r);
        assertThat(value.provenance().quality().assumed()).isTrue();
        assertThat(value.provenance().quality().stale()).isTrue();
        assertThat(r.resolve(query, DataUsage.CURRENT_STATUS)).isEmpty();
    }
    @Test void jumpIsFlaggedButDoesNotReplaceNormalCache() {
        cache.store(query, new ExternalValue(30.0, "분", "healthy", SourceTier.LIVE, NOW.minusSeconds(60), null));
        var r = resolver(provider(SourceTier.LIVE, 300.0, "분", NOW));
        var result = resolve(r);
        assertThat(result.value()).isEqualTo(300.0);
        assertThat(result.provenance().quality().reviewRequired()).isTrue();
        assertThat(cache.lookup(query).orElseThrow().value()).isEqualTo(30.0);
        assertThat(r.resolve(query, DataUsage.CURRENT_STATUS).orElseThrow().value()).isEqualTo(30.0);
    }
    @Test void relationFailureFallsBackAndCacheWaitsForCommit() {
        var r = resolver(provider(SourceTier.LIVE, 100.0, "분", NOW));
        var values = r.resolveAll("ev", "sim", List.of(slot()), DataUsage.SIMULATION, Map.of(),
                trial -> ((Number) trial.get("time").value()).doubleValue() < 50);
        assertThat(values.get("time").value()).isEqualTo(34.0);
        assertThat(cache.size()).isZero();
        var valid = resolver(provider(SourceTier.LIVE, 30.0, "분", NOW));
        var pending = valid.resolveAll("ev", "sim", List.of(slot()), DataUsage.SIMULATION, Map.of(), t -> true);
        assertThat(cache.size()).isZero();
        valid.commit(pending);
        assertThat(cache.size()).isEqualTo(1);
    }
    @Test void olderResponsesCannotReplaceNewerCache() {
        cache.store(query, new ExternalValue(30.0, "분", "new", SourceTier.LIVE, NOW, null));
        cache.store(query, new ExternalValue(20.0, "분", "old", SourceTier.LIVE, NOW.minusSeconds(10), null));
        assertThat(cache.lookup(query).orElseThrow().value()).isEqualTo(30.0);
    }
    @Test void integerOverflowAndFractionsAreRejected() {
        VarDef var = VarDef.of("count", VarType.INT, "대", Range.none());
        ExternalQuery q = new ExternalQuery("ev","sim","count","count","대");
        var validator = new ExternalValueValidator();
        for (double n : new double[]{1.5, 2147483648.0, Double.NaN}) {
            assertThat(validator.validate(new ExternalValue(n,"대","live",SourceTier.LIVE,NOW,null),
                    q,var,null,Map.of(),DataUsage.SIMULATION,policy,NOW,Optional.empty()).accepted()).isFalse();
        }
    }
}
