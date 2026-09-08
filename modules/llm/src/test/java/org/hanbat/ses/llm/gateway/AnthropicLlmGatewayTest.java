package org.hanbat.ses.llm.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.hanbat.ses.llm.audit.LlmCallRecord;
import org.hanbat.ses.llm.audit.LlmCallRecorder;
import org.hanbat.ses.llm.audit.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 게이트웨이 동작을 스텁 응답으로 검증한다.
 *
 * <p>실제 LLM 호출은 비결정적이고 비용이 들며, 실패해도 대개 코드 문제가 아니다.
 * 여기서 확인할 것은 모델의 답이 아니라 <b>우리 쪽 처리</b>다 —
 * 코드펜스 제거, 파싱 실패 재시도, 재시도 대상 상태코드 구분, 감사 기록.
 */
class AnthropicLlmGatewayTest {

    private record Answer(String templateId, double confidence, String reason) {
    }

    private WireMockServer server;
    private AnthropicLlmGateway gateway;
    private List<LlmCallRecord> recorded;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        recorded = new ArrayList<>();

        LlmProperties props = LlmProperties.anthropic(
                "test-key", "http://localhost:" + server.port(), "claude-sonnet-5");
        LlmCallRecorder recorder = recorded::add;

        gateway = new AnthropicLlmGateway(
                WebClient.builder()
                        .baseUrl(props.baseUrl())
                        .defaultHeader("x-api-key", props.apiKey())
                        .build(),
                new ObjectMapper(), recorder, props);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    @DisplayName("구조화 출력을 record 로 파싱한다")
    void parsesStructuredOutput() {
        stubText("{\"templateId\":\"resort-simulation\",\"confidence\":0.9,\"reason\":\"리조트 언급\"}");

        Answer answer = gateway.complete("sys", "리조트 시뮬레이션", Answer.class, Purpose.ROUTING);

        assertThat(answer.templateId()).isEqualTo("resort-simulation");
        assertThat(answer.confidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("코드펜스와 앞뒤 설명이 붙어 와도 JSON 만 뽑아낸다")
    void stripsCodeFenceAndProse() {
        stubText("""
                네, 분석했습니다.
                ```json
                {"templateId": "resort-simulation", "confidence": 0.8, "reason": "ok"}
                ```
                도움이 되었길 바랍니다.""");

        Answer answer = gateway.complete("sys", "u", Answer.class, Purpose.ROUTING);

        assertThat(answer.templateId()).isEqualTo("resort-simulation");
    }

    @Test
    @DisplayName("파싱에 실패하면 실패 사유를 붙여 한 번 더 시도한다")
    void retriesOnceOnParseFailure() {
        server.stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("parse")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(okJson(body("이건 JSON 이 아닙니다")))
                .willSetStateTo("retried"));
        server.stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("parse")
                .whenScenarioStateIs("retried")
                .willReturn(okJson(body(
                        "{\"templateId\":\"resort-simulation\",\"confidence\":0.7,\"reason\":\"r\"}"))));

        Answer answer = gateway.complete("sys", "u", Answer.class, Purpose.ROUTING);

        assertThat(answer.confidence()).isEqualTo(0.7);
        server.verify(2, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    @DisplayName("두 번째도 파싱에 실패하면 원문을 실어 예외를 던진다")
    void failsAfterSecondParseFailure() {
        stubText("여전히 JSON 이 아닙니다");

        assertThatThrownBy(() -> gateway.complete("sys", "u", Answer.class, Purpose.ROUTING))
                .isInstanceOf(LlmParseException.class);
    }

    @Test
    @DisplayName("429 는 재시도하고 4xx 는 즉시 실패한다")
    void retriesOnlyRetryableStatuses() {
        server.stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("rate")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("ok"));
        server.stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("rate")
                .whenScenarioStateIs("ok")
                .willReturn(okJson(body(
                        "{\"templateId\":\"t\",\"confidence\":1.0,\"reason\":\"r\"}"))));

        Answer answer = gateway.complete("sys", "u", Answer.class, Purpose.ROUTING);
        assertThat(answer.templateId()).isEqualTo("t");

        server.resetAll();
        server.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(400).withBody("bad request")));

        assertThatThrownBy(() -> gateway.text("sys", "u", Purpose.ROUTING))
                .isInstanceOf(LlmUnavailableException.class);
        // 400 은 재시도하지 않는다 — 같은 요청을 세 번 더 보내 봐야 같은 답이 온다.
        server.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    @DisplayName("호출은 세션 id 와 토큰 사용량까지 감사 기록에 남는다")
    void recordsAuditTrail() {
        stubText("{\"templateId\":\"t\",\"confidence\":1.0,\"reason\":\"r\"}");
        UUID sessionId = UUID.randomUUID();

        SessionContext.with(sessionId,
                () -> gateway.complete("sys", "u", Answer.class, Purpose.EXTRACTION));

        assertThat(recorded).hasSize(1);
        LlmCallRecord record = recorded.get(0);
        assertThat(record.sessionId()).isEqualTo(sessionId);
        assertThat(record.purpose()).isEqualTo(Purpose.EXTRACTION);
        assertThat(record.model()).isEqualTo("claude-sonnet-5");
        assertThat(record.inputTokens()).isEqualTo(120);
        assertThat(record.outputTokens()).isEqualTo(45);
        assertThat(record.error()).isNull();
    }

    @Test
    @DisplayName("실패한 호출도 사유와 함께 기록된다")
    void recordsFailures() {
        server.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> gateway.text("sys", "u", Purpose.PHRASING))
                .isInstanceOf(LlmUnavailableException.class);

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).error()).isNotNull();
        assertThat(recorded.get(0).response()).isNull();
    }

    @Test
    @DisplayName("API 키가 없으면 available() 이 false 다")
    void reportsUnavailableWithoutKey() {
        AnthropicLlmGateway noKey = new AnthropicLlmGateway(
                WebClient.builder().build(), new ObjectMapper(), LlmCallRecorder.noop(),
                LlmProperties.disabled());

        assertThat(noKey.available()).isFalse();
        assertThatThrownBy(() -> noKey.text("s", "u", Purpose.ROUTING))
                .isInstanceOf(LlmUnavailableException.class);
    }

    // ------------------------------------------------------------ 헬퍼

    private void stubText(String text) {
        server.stubFor(post(urlEqualTo("/v1/messages")).willReturn(okJson(body(text))));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(
            String json) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(json);
    }

    /** Anthropic Messages API 응답 모양. */
    private static String body(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n");
        return """
                {"id":"msg_1","model":"claude-sonnet-5","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"%s"}],
                 "usage":{"input_tokens":120,"output_tokens":45}}""".formatted(escaped);
    }
}
