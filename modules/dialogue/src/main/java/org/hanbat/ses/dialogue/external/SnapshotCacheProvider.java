package org.hanbat.ses.dialogue.external;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 검증 완료한 정상값만 저장한다. 조회 시각과 관측 시각을 모두 보존한다. */
@Component
public class SnapshotCacheProvider implements ExternalDataProvider {
    private record Snapshot(ExternalValue value, Instant cachedAt) { }
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public SnapshotCacheProvider(ExternalDataProperties properties) {
        this(properties, Clock.systemUTC());
    }
    public SnapshotCacheProvider(ExternalDataProperties properties, Clock clock) {
        this.ttl = Duration.ofMinutes(properties.cacheTtlMinutes());
        this.clock = clock;
    }
    public String sourceId() { return "snapshot-cache"; }
    public SourceTier tier() { return SourceTier.CACHED; }
    public boolean available() { return !snapshots.isEmpty(); }

    public Optional<ExternalValue> lookup(ExternalQuery query) {
        Snapshot snapshot = snapshots.get(query.cacheKey());
        if (snapshot == null || !snapshot.cachedAt().plus(ttl).isAfter(clock.instant())) return Optional.empty();
        ExternalValue value = snapshot.value();
        return Optional.of(value.reTagged(SourceTier.CACHED,
                "원 출처 " + value.sourceId() + " · " + snapshot.cachedAt() + " 갈무리 · "
                        + (value.note() == null ? "" : value.note())));
    }

    /** 늦게 도착한 응답이나 확인 대기 값이 기존 정상 관측을 덮지 못한다. */
    public void store(ExternalQuery query, ExternalValue value) {
        if (value.tier() != SourceTier.LIVE || value.observedAt() == null || value.quality().reviewRequired()) return;
        snapshots.compute(query.cacheKey(), (key, old) -> old != null
                && !old.value().observedAt().isBefore(value.observedAt())
                ? old : new Snapshot(value, clock.instant()));
    }
    public int size() { return snapshots.size(); }
    public void clear() { snapshots.clear(); }
}
