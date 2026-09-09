package org.hanbat.ses.dialogue.external;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpExternalDataProviderTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final AtomicInteger calls = new AtomicInteger();
    private final ExternalQuery query = new ExternalQuery("ev", "sim", "time", "time", "분");
    private record Reply(int status, String body) { }
    private HttpExternalDataProvider start(IntFunction<Reply> replies) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data", exchange -> {
            Reply reply = replies.apply(calls.incrementAndGet());
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        return new HttpExternalDataProvider(new ExternalDataProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/data", 2000, 1440), new ObjectMapper());
    }
    private String valid() {
        return "{\"value\":6,\"unit\":\"시간\",\"observedAt\":\"" + Instant.now() + "\"}";
    }
    @AfterEach void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test void timeoutsAreBoundedAndReturnNoValue() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(executor);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/data", exchange -> {
            calls.incrementAndGet();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var provider = new HttpExternalDataProvider(new ExternalDataProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/data", 200, 1440), new ObjectMapper());
        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                    () -> assertThat(provider.lookup(query)).isEmpty());
            assertThat(calls.get()).isBetween(1, 2);
        } finally { release.countDown(); }
    }

    @Test void retriesServerFailureThenReturnsOriginalUnitAndTimestamp() throws Exception {
        var provider = start(n -> new Reply(n == 1 ? 503 : 200, n == 1 ? "unavailable" : valid()));
        var value = provider.lookup(query).orElseThrow();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(value.value()).isEqualTo(6);
        assertThat(value.unit()).isEqualTo("시간");
        assertThat(value.observedAt()).isNotNull();
    }
    @Test void repeatedServerErrorsStopAtAttemptLimit() throws Exception {
        assertThat(start(n -> new Reply(503,"unavailable")).lookup(query)).isEmpty();
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void doesNotRetryClientErrors() throws Exception {
        assertThat(start(n -> new Reply(400,"bad query")).lookup(query)).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void malformedPayloadIsNotRetried() throws Exception {
        assertThat(start(n -> new Reply(200,"{broken")).lookup(query)).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void missingObservationIsNotReplacedWithLookupTime() throws Exception {
        assertThat(start(n -> new Reply(200,"{\"value\":30,\"unit\":\"분\"}")).lookup(query)).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void missingUnitIsNotAssumedToBeRequestedUnit() throws Exception {
        assertThat(start(n -> new Reply(200,"{\"value\":30,\"observedAt\":\"2026-09-08T00:00:00Z\"}"))
                .lookup(query)).isEmpty();
    }
    @Test void derivedValuesNeedAnExplanationAndAreTagged() throws Exception {
        var provider = start(n -> new Reply(200, valid().replace("}",
                ",\"kind\":\"DERIVED\",\"note\":\"에너지와 출력으로 계산한 예시\"}")));
        var value = provider.lookup(query).orElseThrow();
        assertThat(value.quality().assumed()).isTrue();
        assertThat(value.quality().reasons()).contains("DERIVED");
    }
}
