package org.hanbat.ses.dialogue.external;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 1단 — 외부 시스템에 지금 물어본다.
 *
 * <p>{@code app.external-data.base-url} 이 비어 있으면 통째로 건너뛴다. 켜져 있어도
 * 타임아웃·오류·해석 실패는 전부 <b>빈 값</b>으로 접는다 — 여기서 예외를 올리면
 * 외부 시스템 한 곳이 멎을 때 대화 전체가 멎고, 그러면 폴백을 세 단으로 나눈 의미가 없다.
 *
 * <p>기대하는 응답은 다음 한 덩어리다. {@code value}, {@code observedAt}, 단위가 있는 슬롯의 {@code unit}이 필수다.
 * <pre>
 * { "value": 7.5, "unit": "분", "observedAt": "2026-09-01T00:00:00Z", "note": "..." }
 * </pre>
 *
 * <p>JDK 의 {@code HttpClient} 를 쓴다. 이 모듈은 대화 상태머신이 사는 곳이라
 * 리액티브 스택을 끌어들일 이유가 없고, 조회 하나에 필요한 것은 동기 GET 하나뿐이다.
 *
 * <h2>죽은 엔드포인트를 계속 두드리지 않는다</h2>
 * <p>타임아웃과 5xx 가 연속으로 쌓이면 잠시 {@link #available()} 을 false 로 내린다.
 * 이것이 없으면 엔드포인트가 멎어 있는 동안 <b>모든 턴</b>이 타임아웃을 다시 지불한다 —
 * 한 턴 안의 낭비는 조회 예산이 막지만, 턴과 턴 사이는 막지 못한다.
 *
 * <p>느려서 생긴 실패만 센다. 4xx 나 형식이 깨진 응답은 곧바로 돌아오므로 지연의 원인이
 * 아니고, 슬롯 하나의 계약 문제로 다른 슬롯의 조회까지 막을 이유가 없다.
 */
@Component
public class HttpExternalDataProvider implements ExternalDataProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpExternalDataProvider.class);

    private final ExternalDataProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;

    /** 느려서 생긴 연속 실패 횟수. 성공하면 0 으로 되돌린다. */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** 이 시각(nanoTime) 까지는 실시간 단을 건너뛴다. 0 이면 열려 있다. */
    private final AtomicLong skipUntilNanos = new AtomicLong();

    public HttpExternalDataProvider(ExternalDataProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.timeoutMs()))
                .build();
    }

    @Override
    public String sourceId() {
        return properties.liveEnabled() ? properties.baseUrl() : "live-disabled";
    }

    @Override
    public SourceTier tier() {
        return SourceTier.LIVE;
    }

    @Override
    public boolean available() {
        if (!properties.liveEnabled()) {
            return false;
        }
        long until = skipUntilNanos.get();
        // 뺄셈으로 비교한다. nanoTime 은 절대값에 의미가 없고 언젠가 넘친다.
        return until == 0L || System.nanoTime() - until >= 0;
    }

    @Override
    public Optional<ExternalValue> lookup(ExternalQuery query) {
        if (!available()) {
            return Optional.empty();
        }
        Attempt outcome = attempt(query);
        if (outcome.value().isPresent()) {
            consecutiveFailures.set(0);
            skipUntilNanos.set(0L);
        } else if (outcome.slow()) {
            recordSlowFailure();
        }
        return outcome.value();
    }

    /** 조회 한 번의 결과와, 그 실패가 <b>느려서</b> 생긴 것인지. */
    private record Attempt(Optional<ExternalValue> value, boolean slow) {

        static Attempt found(Optional<ExternalValue> value) {
            return new Attempt(value, false);
        }

        static Attempt fastFailure() {
            return new Attempt(Optional.empty(), false);
        }

        static Attempt slowFailure() {
            return new Attempt(Optional.empty(), true);
        }
    }

    private void recordSlowFailure() {
        if (consecutiveFailures.incrementAndGet() < properties.liveFailureThreshold()) {
            return;
        }
        long cooldown = properties.liveCooldownMs();
        skipUntilNanos.set(System.nanoTime() + cooldown * 1_000_000L);
        consecutiveFailures.set(0);
        log.warn("외부 API 가 연속 실패해 실시간 조회를 {}ms 동안 건너뜁니다: {}",
                cooldown, properties.baseUrl());
    }

    private Attempt attempt(ExternalQuery query) {
        boolean slow = false;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uriFor(query))
                        .timeout(Duration.ofMillis(properties.timeoutMs()))
                        .header("Accept", "application/json").GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) return Attempt.found(parse(response.body(), query));
                log.warn("외부 API 응답 실패: slot={}, status={}, attempt={}", query.slotName(), status, attempt);
                // 4xx와 잘못된 본문은 재시도로 고쳐지지 않는다. 429도 즉시 반복하지 않는다.
                if (status < 500 || status > 599) return Attempt.fastFailure();
                slow = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Attempt.fastFailure();
            } catch (java.io.IOException e) {
                slow = true;
                log.warn("외부 API 통신 실패: slot={}, attempt={}, error={}",
                        query.slotName(), attempt, e.getClass().getSimpleName());
            } catch (RuntimeException e) {
                log.warn("외부 API 응답/설정 오류: slot={}, error={}", query.slotName(), e.getClass().getSimpleName());
                return Attempt.fastFailure();
            }
        }
        return slow ? Attempt.slowFailure() : Attempt.fastFailure();
    }
    private URI uriFor(ExternalQuery query) {
        // 슬롯 이름과 도메인 이름만 싣는다. 요청문이나 세션 식별자는 보내지 않는다 —
        // 값 하나를 물어보는 데 필요한 것이 아니고, 나가면 되돌릴 수 없다.
        String sep = properties.baseUrl().contains("?") ? "&" : "?";
        return URI.create(properties.baseUrl() + sep
                + "domain=" + encode(query.domain())
                + "&slot=" + encode(query.slotName())
                + "&var=" + encode(query.varName())
                + "&unit=" + encode(query.unit()));
    }

    private Optional<ExternalValue> parse(String body, ExternalQuery query) {
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("객체 응답 필요");
            JsonNode value = node.path("value");
            if (!(value.isNumber() || value.isBoolean() || value.isTextual())) {
                throw new IllegalArgumentException("스칼라 값 필요");
            }
            if (!node.path("observedAt").isTextual()) throw new IllegalArgumentException("관측 시각 필요");
            if (query.unit() != null && !node.path("unit").isTextual()) {
                throw new IllegalArgumentException("단위 필요");
            }
            Object raw = value.isNumber() ? value.numberValue()
                    : value.isBoolean() ? value.booleanValue() : value.asText();
            String unit = node.hasNonNull("unit") ? node.get("unit").asText() : null;
            Instant observedAt = Instant.parse(node.get("observedAt").asText());
            String note = node.hasNonNull("note") ? node.get("note").asText() : null;
            String kind = node.path("kind").asText("OBSERVED");
            if (!java.util.Set.of("OBSERVED", "DERIVED", "ASSUMED").contains(kind)) {
                throw new IllegalArgumentException("알 수 없는 데이터 종류");
            }
            boolean assumed = !kind.equals("OBSERVED");
            if (assumed && (note == null || note.isBlank())) throw new IllegalArgumentException("계산/가정 근거 필요");
            return Optional.of(new ExternalValue(raw, unit, properties.baseUrl(), SourceTier.LIVE,
                    observedAt, note, new DataQuality(assumed, false, false,
                            assumed ? java.util.List.of(kind) : java.util.List.of())));
        } catch (Exception e) {
            log.warn("외부 API 응답 제외: slot={}, reason=MALFORMED_RESPONSE, error={}",
                    query.slotName(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
    private static String encode(String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

