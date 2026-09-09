package org.hanbat.ses.dialogue.external;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.hanbat.ses.core.model.Range;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.resolve.SlotKind;
import org.hanbat.ses.dialogue.control.ResolvedSlot;
import org.hanbat.ses.template.model.ValueSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 공급자마다 정규화와 검증을 수행하며, 채택할 수 없는 응답은 다음 공급자로 넘긴다. */
@Component
public class ExternalDataResolver {
    private static final Logger log = LoggerFactory.getLogger(ExternalDataResolver.class);
    private final List<ExternalDataProvider> providers;
    private final SnapshotCacheProvider cache;
    private final ExternalDataProperties policy;
    private final Clock clock;
    private final ExternalValueValidator validator = new ExternalValueValidator();

    @Autowired
    public ExternalDataResolver(List<ExternalDataProvider> providers, SnapshotCacheProvider cache,
                                ExternalDataProperties policy) {
        this(providers, cache, policy, Clock.systemUTC());
    }

    public ExternalDataResolver(List<ExternalDataProvider> providers, SnapshotCacheProvider cache) {
        this(providers, cache, ExternalDataProperties.disabled());
    }

    public ExternalDataResolver(List<ExternalDataProvider> providers, SnapshotCacheProvider cache,
                                ExternalDataProperties policy, Clock clock) {
        this.providers = providers.stream().sorted(Comparator.comparing(p -> p.tier().ordinal())).toList();
        this.cache = cache;
        this.policy = policy;
        this.clock = clock;
    }

    public Map<String, Resolution> resolveAll(String domain, String templateId, List<ResolvedSlot> open) {
        Map<String, Resolution> values = resolveAll(domain, templateId, open, DataUsage.SIMULATION,
                Map.of(), candidate -> true);
        commit(values);
        return values;
    }

    /** 관계 검증까지 통과한 후보를 반환한다. 대화 전체 적용이 성공한 뒤 commit해야 한다. */
    public Map<String, Resolution> resolveAll(String domain, String templateId, List<ResolvedSlot> open,
            DataUsage usage, Map<String, String> aliases,
            Predicate<Map<String, Resolution>> relationCheck) {
        Map<String, Resolution> out = new LinkedHashMap<>();
        if (domain == null) return out;
        // 슬롯마다 새 예산을 주면 슬롯 수만큼 늘어진다. 턴 하나가 예산 하나를 나눠 쓴다.
        LiveBudget budget = new LiveBudget(policy.liveBudgetMs());
        for (ResolvedSlot slot : open) {
            if (slot.kind() != SlotKind.VALUE || slot.open().varDef() == null || !slot.inferable()) continue;
            VarDef var = slot.open().varDef();
            Range range = slot.spec() instanceof ValueSlot v ? v.range() : null;
            ExternalQuery query = new ExternalQuery(domain, templateId,
                    slot.spec() == null ? slot.name() : slot.spec().name(), var.name(), var.unit());
            resolveValidated(query, var, range, aliases, usage, budget, value -> {
                Map<String, Resolution> trial = new LinkedHashMap<>(out);
                trial.put(slot.name(), resolution(slot.name(), query, value));
                return relationCheck.test(Map.copyOf(trial));
            }).ifPresent(value -> out.put(slot.name(), resolution(slot.name(), query, value)));
        }
        return out;
    }

    private Resolution resolution(String name, ExternalQuery query, ExternalValue value) {
        return new Resolution(value.value(), value.provenanceFor(name), query, value);
    }

    /** 단일 값 조회. 도메인 범위 검사가 필요한 호출부는 resolveAll을 사용한다. */
    public Optional<ExternalValue> resolve(ExternalQuery query) {
        return resolve(query, DataUsage.SIMULATION);
    }

    public Optional<ExternalValue> resolve(ExternalQuery query, DataUsage usage) {
        Optional<ExternalValue> result = resolveValidated(query, null, null, Map.of(), usage,
                new LiveBudget(policy.liveBudgetMs()), v -> true);
        result.filter(v -> v.tier() == SourceTier.LIVE && !v.quality().reviewRequired())
                .ifPresent(v -> cache.store(query, v));
        return result;
    }

    public void commit(Map<String, Resolution> accepted) {
        accepted.values().stream().filter(r -> r.external().tier() == SourceTier.LIVE
                && !r.external().quality().reviewRequired())
                .forEach(r -> cache.store(r.query(), r.external()));
    }

    private Optional<ExternalValue> resolveValidated(ExternalQuery query, VarDef var, Range range,
            Map<String, String> aliases, DataUsage usage, LiveBudget budget,
            Predicate<ExternalValue> relationCheck) {
        List<String> failures = new ArrayList<>();
        Optional<ExternalValue> previous = cache.lookup(query);
        for (ExternalDataProvider provider : providers) {
            try {
                if (!provider.available()) continue;
                if (usage == DataUsage.CURRENT_STATUS && provider.tier() == SourceTier.BUNDLED) continue;
                boolean live = provider.tier() == SourceTier.LIVE;
                if (live && budget.exhausted()) {
                    // 이번 턴에 쓸 실시간 조회 시간을 다 썼다. 남은 슬롯은 캐시·내장으로 간다.
                    failures.add("LIVE_BUDGET_EXHAUSTED");
                    continue;
                }
                Optional<ExternalValue> found;
                long started = System.nanoTime();
                try {
                    found = provider.lookup(query);
                } finally {
                    if (live) budget.charge(System.nanoTime() - started);
                }
                if (found.isEmpty()) {
                    failures.add("SOURCE_UNAVAILABLE");
                    continue;
                }
                ExternalValue raw = found.get();
                if (raw.tier() != provider.tier()) {
                    failures.add("SOURCE_TIER_MISMATCH");
                    continue;
                }
                var check = validator.validate(raw, query, var, range, aliases, usage, policy,
                        clock.instant(), previous);
                if (!check.accepted()) {
                    failures.add(check.rejection());
                    log.warn("외부 데이터 제외: source={}, slot={}, reason={}",
                            provider.sourceId(), query.slotName(), check.rejection());
                    continue;
                }
                ExternalValue value = check.value();
                if (!relationCheck.test(value)) {
                    failures.add("CROSS_FIELD_VIOLATION");
                    log.warn("외부 데이터 관계 검증 실패: source={}, slot={}", provider.sourceId(), query.slotName());
                    continue;
                }
                List<String> reasons = new ArrayList<>(value.quality().reasons());
                reasons.addAll(failures);
                String note = value.note();
                if (value.tier() == SourceTier.CACHED) {
                    long minutes = Math.max(0, java.time.Duration.between(value.observedAt(), clock.instant()).toMinutes());
                    note = "실시간 값 대신 " + minutes + "분 전 관측 정보를 사용했습니다. "
                            + (note == null ? "" : note);
                }
                DataQuality quality = new DataQuality(value.quality().assumed(), value.quality().stale(),
                        value.quality().reviewRequired(), reasons.stream().distinct().toList());
                return Optional.of(new ExternalValue(value.value(), value.unit(), value.sourceId(),
                        value.tier(), value.observedAt(), note, quality));
            } catch (RuntimeException ex) {
                failures.add("PROVIDER_ERROR");
                log.warn("외부 데이터 공급자 실패: source={}, slot={}, error={}",
                        provider.sourceId(), query.slotName(), ex.getClass().getSimpleName());
            }
        }
        // 정상 경로다. 도메인에 참고값이 없으면 모든 슬롯이 여기로 오므로 info 로 두면
        // 아무 문제 없는 세션이 로그를 가득 채운다. 진짜 문제는 위쪽 warn 이 이미 남겼다.
        log.debug("사용 가능한 외부 데이터 없음: slot={}, reasons={}", query.slotName(), failures);
        return Optional.empty();
    }

    /**
     * 한 턴이 실시간 조회에 쓸 수 있는 시간의 총량.
     *
     * <p>{@code timeoutMs} 는 조회 하나의 상한일 뿐이라 슬롯이 늘면 턴도 함께 늘어진다.
     * 충전기 20대 구성에서 엔드포인트가 멎으면 한 턴이 분 단위로 늘어났다. 예산을 두면
     * 슬롯 수와 무관하게 유계가 되고, 예산을 다 쓴 뒤의 슬롯은 캐시와 내장 데이터셋으로
     * 곧바로 내려간다 — 값을 못 받는 것이 아니라 <b>덜 새로운</b> 값을 받는다.
     */
    private static final class LiveBudget {

        private final long limitNanos;
        private long spentNanos;
        private boolean reported;

        LiveBudget(long limitMillis) {
            this.limitNanos = limitMillis * 1_000_000L;
        }

        boolean exhausted() {
            if (spentNanos < limitNanos) {
                return false;
            }
            if (!reported) {
                reported = true;
                log.warn("이번 턴의 실시간 조회 예산({}ms)을 다 썼습니다. 남은 슬롯은 캐시와 내장 데이터셋으로 채웁니다.",
                        limitNanos / 1_000_000L);
            }
            return true;
        }

        void charge(long nanos) {
            spentNanos += nanos;
        }
    }

    public record Resolution(Object value, SlotProvenance provenance, ExternalQuery query, ExternalValue external) { }
}
