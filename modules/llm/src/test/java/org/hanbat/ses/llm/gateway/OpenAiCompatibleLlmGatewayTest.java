package org.hanbat.ses.llm.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.hanbat.ses.llm.audit.LlmCallRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 오픈 모델 경로(OpenAI 호환 엔드포인트) 검증.
 *
 * <p>Anthropic 경로와 갈리는 지점만 본다 — 요청 본문 모양, system 프롬프트 위치,
 * JSON 모드, 키 없이도 동작하는지, 응답 봉투에서 텍스트를 꺼내는 방법.
 * 재시도와 파싱 로직은 상위 클래스가 공유하므로 여기서 다시 확인하지 않는다.
 */
class OpenAiCompatibleLlmGatewayTest {

    private record Answer(String templateId, double confidence, String reason) {
    }

    private static final String PATH = "/v1/chat/completions";

    private WireMockServer server;
    private OpenAiCompatibleLlmGateway gateway;
    private List<LlmCallRecord> recorded;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        recorded = new ArrayList<>();

        LlmProperties props = LlmProperties.openAiCompatible(
                "http://localhost:" + server.port(), "qwen2.5:7b");

        gateway = new OpenAiCompatibleLlmGateway(
                WebClient.builder().baseUrl(props.baseUrl()).build(),
                new ObjectMapper(), recorded::add, props);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    @DisplayName("API 키가 없어도 사용할 수 있다 — 로컬 런타임은 인증이 없다")
    void worksWithoutApiKey() {
        stubContent("{\"templateId\":\"resort-simulation\",\"confidence\":0.9,\"reason\":\"리조트\"}");

        assertThat(gateway.available()).isTrue();

        Answer answer = gateway.complete("sys", "리조트 시뮬레이션", Answer.class, Purpose.ROUTING);
        assertThat(answer.templateId()).isEqualTo("resort-simulation");
    }

    @Test
    @DisplayName("주소가 없으면 사용할 수 없다")
    void unavailableWithoutBaseUrl() {
        OpenAiCompatibleLlmGateway noUrl = new OpenAiCompatibleLlmGateway(
                WebClient.builder().build(), new ObjectMapper(),
                org.hanbat.ses.llm.audit.LlmCallRecorder.noop(),
                new LlmProperties(LlmProvider.OPENAI_COMPATIBLE, null, "  ", "m",
                        java.util.Map.of(), 0.1, 512, 1000L, 0, true, 1));

        assertThat(noUrl.available()).isFalse();
        assertThatThrownBy(() -> noUrl.text("s", "u", Purpose.ROUTING))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("base-url");
    }

    @Test
    @DisplayName("system 프롬프트가 messages 첫 항목으로 들어가고 스트리밍은 꺼진다")
    void putsSystemPromptIntoMessages() {
        stubContent("{\"templateId\":\"t\",\"confidence\":1.0,\"reason\":\"r\"}");

        gateway.complete("당신은 라우터입니다", "리조트", Answer.class, Purpose.ROUTING);

        String body = server.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(body)
                .contains("\"role\":\"system\"")
                .contains("당신은 라우터입니다")
                .contains("\"role\":\"user\"")
                .contains("\"stream\":false")
                .contains("\"model\":\"qwen2.5:7b\"")
                // Anthropic 처럼 최상위 system 필드를 보내면 호환 서버가 무시하거나 400 을 낸다.
                .doesNotContain("\"system\":");
    }

    @Test
    @DisplayName("구조화 출력에는 JSON 모드를 켜고 자유 텍스트에는 켜지 않는다")
    void enablesJsonModeOnlyForStructuredOutput() {
        stubContent("{\"templateId\":\"t\",\"confidence\":1.0,\"reason\":\"r\"}");

        gateway.complete("sys", "u", Answer.class, Purpose.ROUTING);
        assertThat(server.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .contains("\"response_format\"").contains("json_object");

        server.resetAll();
        stubContent("자연스러운 한국어 문장입니다");

        gateway.text("sys", "u", Purpose.PHRASING);
        assertThat(server.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .doesNotContain("response_format");
    }

    @Test
    @DisplayName("JSON 모드를 끄면 response_format 을 보내지 않는다 — 지원하지 않는 런타임 대비")
    void respectsJsonModeToggle() {
        LlmProperties noJson = new LlmProperties(LlmProvider.OPENAI_COMPATIBLE, null,
                "http://localhost:" + server.port(), "gemma2:9b",
                java.util.Map.of(), 0.1, 512, 5_000L, 0, false, 1);
        OpenAiCompatibleLlmGateway g = new OpenAiCompatibleLlmGateway(
                WebClient.builder().baseUrl(noJson.baseUrl()).build(),
                new ObjectMapper(), recorded::add, noJson);
        stubContent("{\"templateId\":\"t\",\"confidence\":1.0,\"reason\":\"r\"}");

        g.complete("sys", "u", Answer.class, Purpose.ROUTING);

        assertThat(server.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .doesNotContain("response_format");
    }

    @Test
    @DisplayName("prompt_tokens / completion_tokens 를 감사 기록에 옮긴다")
    void mapsUsageFieldNames() {
        stubContent("{\"templateId\":\"t\",\"confidence\":1.0,\"reason\":\"r\"}");

        gateway.complete("sys", "u", Answer.class, Purpose.EXTRACTION);

        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).inputTokens()).isEqualTo(312);
        assertThat(recorded.get(0).outputTokens()).isEqualTo(57);
        assertThat(recorded.get(0).model()).isEqualTo("qwen2.5:7b");
    }

    @Test
    @DisplayName("작은 모델이 코드펜스를 붙여 보내도 JSON 만 뽑아낸다")
    void stripsFenceFromSmallModelOutput() {
        stubContent("""
                ```json
                {"templateId": "resort-simulation", "confidence": 0.6, "reason": "리조트 관련"}
                ```""");

        Answer answer = gateway.complete("sys", "u", Answer.class, Purpose.ROUTING);

        assertThat(answer.confidence()).isEqualTo(0.6);
    }

    @Test
    @DisplayName("모델이 스키마를 그대로 되돌려줘도 답을 꺼낸다")
    void unwrapsSchemaEcho() {
        // 파라미터가 적은 모델의 전형적인 실패 — 스키마 봉투 안에 값을 담아 보낸다.
        // 파싱은 성공하지만 필드가 전부 비어 나오므로, 걸러 내지 않으면 조용히 틀린다.
        stubContent("{\"type\": \"object\", \"properties\": "
                + "{\"templateId\": \"resort-simulation\", \"confidence\": \"0.8\", "
                + "\"reason\": \"리조트 관련\"}}");

        Answer answer = gateway.complete("sys", "u", Answer.class, Purpose.ROUTING);

        assertThat(answer.templateId()).isEqualTo("resort-simulation");
        assertThat(answer.confidence()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("프롬프트에 스키마와 채울 예시가 함께 실린다")
    void promptCarriesSchemaAndExample() {
        stubContent("{\"templateId\":\"t\",\"confidence\":1.0,\"reason\":\"r\"}");

        gateway.complete("sys", "요청문", Answer.class, Purpose.ROUTING);

        String body = server.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(body).contains("스키마").contains("\\\"type\\\": \\\"object\\\"");
        // 예시가 뒤에 와야 한다 — 작은 모델은 마지막에 본 형태를 흉내 낸다.
        int schemaAt = body.indexOf("스키마:");
        int exampleAt = body.indexOf("값만 채워 답하세요");
        assertThat(exampleAt).isGreaterThan(schemaAt);
    }

    @Test
    @DisplayName("파싱 실패 시 설정된 횟수만큼 다시 시도한다")
    void retriesUpToConfiguredAttempts() {
        // openAiCompatible 기본값은 3회 — 작은 모델은 첫 시도에서 놓치는 일이 잦다.
        stubContent("JSON 이 아닙니다");

        assertThatThrownBy(() -> gateway.complete("sys", "u", Answer.class, Purpose.ROUTING))
                .isInstanceOf(LlmParseException.class);

        server.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("응답 봉투가 예상과 다르면 사유를 밝히고 실패한다")
    void reportsUnexpectedEnvelope() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"model not found\"}}")));

        // choices 가 없으면 본문이 빈 문자열이 되어 파싱 단계에서 걸린다.
        assertThatThrownBy(() -> gateway.complete("sys", "u", Answer.class, Purpose.ROUTING))
                .isInstanceOf(LlmParseException.class);
    }

    // ------------------------------------------------------------ 헬퍼

    private void stubContent(String content) {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(envelope(content))));
    }

    /** Ollama · vLLM · llama.cpp 가 모두 내는 OpenAI 호환 응답 모양. */
    private static String envelope(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n");
        return """
                {"id":"chatcmpl-1","object":"chat.completion","model":"qwen2.5:7b",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},
                             "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":312,"completion_tokens":57,"total_tokens":369}}"""
                .formatted(escaped);
    }
}
