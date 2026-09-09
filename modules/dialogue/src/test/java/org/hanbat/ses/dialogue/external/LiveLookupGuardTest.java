package org.hanbat.ses.dialogue.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.hanbat.ses.core.model.Range;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.model.VarType;
import org.hanbat.ses.core.resolve.OpenSlot;
import org.hanbat.ses.dialogue.control.ResolvedSlot;
import org.hanbat.ses.template.model.ValueSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * 실시간 조회가 대화를 붙잡지 못하게 막는 두 겹의 상한.
 *
 * <p>{@code timeoutMs} 는 조회 <b>하나</b>만 지킨다. 값 슬롯이 스무 개면 조회도 스무 번이라
 * 한 턴이 그만큼 늘어지고, 엔드포인트가 죽어 있는 동안에는 <b>모든 턴</b>이 같은 값을 다시
 * 지불한다. 두 상한은 각각 그 둘을 막는다 — 하나는 턴 안을, 하나는 턴 사이를.
 */
class LiveLookupGuardTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------ 턴 예산

    @Test
    @DisplayName("예산을 다 쓰면 남은 슬롯은 실시간을 건너뛰고 내장 데이터셋으로 채운다")
    void budgetStopsPerSlotMultiplication() {
        SlowProvider live = new SlowProvider(120);
        // 예산 200ms — 느린 조회 두 번이면 바닥난다.
        ExternalDataProperties policy = new ExternalDataProperties(
                null, 2_000L, 1_440L, 1, 120, 180, 3, 200L, 3, 60_000L);
        Fixture f = fixture(policy, live, bundled(34.0));

        List<ResolvedSlot> slots = List.of(slot("a"), slot("b"), slot("c"), slot("d"), slot("e"));
        var filled = f.resolver().resolveAll("evcharge", "ev-charging", slots,
                DataUsage.SIMULATION, java.util.Map.of(), trial -> true);

        // 슬롯 다섯 개인데 실시간은 몇 번만 두드린다. 예산이 없으면 다섯 번 전부 두드린다.
        assertThat(live.calls()).isLessThan(slots.size());
        // 그래도 값은 모두 채워진다 — 못 받는 것이 아니라 덜 새로운 값을 받는다.
        assertThat(filled).hasSize(slots.size());
        // 예산이 남아 있던 앞쪽 슬롯은 실시간으로, 소진된 뒤는 내장 데이터셋으로 갈린다.
        assertThat(filled.values()).anyMatch(r -> r.provenance().tier() == SourceTier.LIVE);
        assertThat(filled.values()).anyMatch(r -> r.provenance().tier() == SourceTier.BUNDLED);
        assertThat(filled.values().stream()
                .filter(r -> r.provenance().tier() == SourceTier.LIVE).count())
                .isEqualTo(live.calls());
    }

    @Test
    @DisplayName("예산은 턴마다 새로 주어진다")
    void budgetIsPerTurn() {
        SlowProvider live = new SlowProvider(120);
        ExternalDataProperties policy = new ExternalDataProperties(
                null, 2_000L, 1_440L, 1, 120, 180, 3, 200L, 3, 60_000L);
        Fixture f = fixture(policy, live, bundled(34.0));

        List<ResolvedSlot> slots = List.of(slot("a"), slot("b"), slot("c"));
        f.resolver().resolveAll("evcharge", "ev-charging", slots,
                DataUsage.SIMULATION, java.util.Map.of(), trial -> true);
        int firstTurn = live.calls();

        f.resolver().resolveAll("evcharge", "ev-charging", slots,
                DataUsage.SIMULATION, java.util.Map.of(), trial -> true);

        // 다음 턴에도 다시 시도한다. 한 번 소진했다고 영구히 닫히면 엔드포인트가 살아나도 못 쓴다.
        assertThat(live.calls()).isGreaterThan(firstTurn);
    }

    @Test
    @DisplayName("빠른 응답은 예산을 거의 쓰지 않아 슬롯 전체가 실시간으로 채워진다")
    void healthyEndpointIsNotThrottled() {
        SlowProvider live = new SlowProvider(0);
        ExternalDataProperties policy = new ExternalDataProperties(
                null, 2_000L, 1_440L, 1, 120, 36_500, 3, 200L, 3, 60_000L);
        Fixture f = fixture(policy, live, bundled(34.0));

        List<ResolvedSlot> slots = List.of(slot("a"), slot("b"), slot("c"), slot("d"), slot("e"));
        var filled = f.resolver().resolveAll("evcharge", "ev-charging", slots,
                DataUsage.SIMULATION, java.util.Map.of(), trial -> true);

        assertThat(live.calls()).isEqualTo(slots.size());
        assertThat(filled.values()).allMatch(r -> r.provenance().tier() == SourceTier.LIVE);
    }

    // ------------------------------------------------------------ 연속 실패 차단

    @Test
    @DisplayName("연속 실패가 쌓이면 실시간 단을 잠시 닫는다")
    void repeatedSlowFailuresOpenTheBreaker() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/data";
        // 시도 1회, 연속 2회 실패하면 차단.
        var provider = new HttpExternalDataProvider(new ExternalDataProperties(
                url, 500L, 1_440L, 1, 120, 180, 3, 0L, 2, 60_000L), new ObjectMapper());

        assertThat(provider.available()).isTrue();
        provider.lookup(query());
        provider.lookup(query());
        int afterThreshold = hits.get();

        assertThat(provider.available()).as("차단 상태").isFalse();

        provider.lookup(query());
        assertThat(hits.get()).as("차단 중에는 두드리지 않는다").isEqualTo(afterThreshold);
    }

    @Test
    @DisplayName("빠르게 돌아오는 실패는 차단하지 않는다 — 지연의 원인이 아니다")
    void clientErrorsDoNotOpenTheBreaker() throws Exception {
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/data";
        var provider = new HttpExternalDataProvider(new ExternalDataProperties(
                url, 500L, 1_440L, 1, 120, 180, 3, 0L, 2, 60_000L), new ObjectMapper());

        // 슬롯 하나의 계약 문제로 다른 슬롯의 조회까지 막을 이유가 없다.
        for (int i = 0; i < 5; i++) {
            provider.lookup(query());
        }
        assertThat(provider.available()).isTrue();
    }

    @Test
    @DisplayName("성공하면 실패 누적이 지워진다")
    void successResetsTheBreaker() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data", exchange -> {
            // 첫 요청만 실패시킨다.
            if (hits.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] body = ("{\"value\":34,\"unit\":\"분\",\"observedAt\":\"" + Instant.now() + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/data";
        var provider = new HttpExternalDataProvider(new ExternalDataProperties(
                url, 500L, 1_440L, 1, 120, 180, 3, 0L, 2, 60_000L), new ObjectMapper());

        provider.lookup(query());                       // 실패 1
        assertThat(provider.lookup(query())).isPresent();   // 성공 -> 누적 초기화
        provider.lookup(query());                       // 실패였다면 여기서 차단됐을 자리

        assertThat(provider.available()).isTrue();
    }

    // ------------------------------------------------------------ 조립

    private record Fixture(ExternalDataResolver resolver, SnapshotCacheProvider cache) {
    }

    private Fixture fixture(ExternalDataProperties policy, ExternalDataProvider... providers) {
        SnapshotCacheProvider cache = new SnapshotCacheProvider(policy);
        List<ExternalDataProvider> all = new ArrayList<>(List.of(providers));
        all.add(cache);
        return new Fixture(new ExternalDataResolver(all, cache, policy), cache);
    }

    private static ExternalQuery query() {
        return new ExternalQuery("evcharge", "ev-charging", "급속기.평균충전시간", "평균충전시간", "분");
    }

    /** 응답은 하지만 느린 실시간 공급자. 몇 번 불렸는지 센다. */
    private static final class SlowProvider implements ExternalDataProvider {

        private final long delayMillis;
        private final AtomicInteger calls = new AtomicInteger();

        SlowProvider(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public String sourceId() {
            return "slow-live";
        }

        @Override
        public SourceTier tier() {
            return SourceTier.LIVE;
        }

        @Override
        public Optional<ExternalValue> lookup(ExternalQuery query) {
            calls.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Optional.of(new ExternalValue(30.0, "분", "slow-live", SourceTier.LIVE,
                    Instant.now(), null));
        }
    }

    private static ExternalDataProvider bundled(double value) {
        return new ExternalDataProvider() {
            @Override
            public String sourceId() {
                return "bundled";
            }

            @Override
            public SourceTier tier() {
                return SourceTier.BUNDLED;
            }

            @Override
            public Optional<ExternalValue> lookup(ExternalQuery query) {
                return Optional.of(new ExternalValue(value, "분", "bundled", SourceTier.BUNDLED,
                        Instant.now(), null));
            }
        };
    }

    private static ResolvedSlot slot(String suffix) {
        VarDef var = new VarDef("평균충전시간", VarType.DOUBLE, "분", null, null, true,
                new Range(5.0, 120.0, List.of()));
        SesAnchor anchor = SesAnchor.variable(
                "충전소/충전기종류/급속충전기/충전기", "ent-fast-charger-" + suffix, "평균충전시간");
        ValueSlot spec = new ValueSlot("급속기.평균충전시간." + suffix, anchor, VarType.DOUBLE, "분",
                new Range(5.0, 120.0, List.of()), 30.0, true, null, List.of());
        return new ResolvedSlot("급속기.평균충전시간." + suffix,
                OpenSlot.value("충전소/충전기종류/급속충전기/충전기", anchor, var, 3), spec);
    }
}
