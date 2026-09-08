package org.hanbat.ses.llm.gateway;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.hanbat.ses.llm.audit.LlmCallRecorder;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Anthropic Messages API 직접 호출.
 *
 * <p>Resilience4j 대신 Reactor 의 retryWhen 으로 충분하다 — 재시도 정책은
 * {@link HttpLlmGateway} 에 있고, 429 와 5xx 만 재시도하며 4xx 는 즉시 실패한다.
 */
public class AnthropicLlmGateway extends HttpLlmGateway {

    private static final String MESSAGES_PATH = "/v1/messages";

    public AnthropicLlmGateway(WebClient client, ObjectMapper mapper,
                               LlmCallRecorder recorder, LlmProperties props) {
        super(client, mapper, recorder, props);
    }

    @Override
    public boolean available() {
        return props.apiKey() != null && !props.apiKey().isBlank();
    }

    @Override
    protected String unavailableReason() {
        return "Anthropic API 키가 설정되어 있지 않습니다.";
    }

    @Override
    protected String endpoint() {
        return MESSAGES_PATH;
    }

    /** system 이 최상위 필드다 — messages 에 섞지 않는다. */
    @Override
    protected Map<String, Object> requestBody(String system, String user,
                                              String model, boolean jsonOnly) {
        return Map.of(
                "model", model,
                "max_tokens", props.maxTokens(),
                "temperature", props.temperature(),
                "system", system == null ? "" : system,
                "messages", List.of(Map.of("role", "user", "content", user)));
    }

    @Override
    protected Completion parseResponse(String rawJson) throws JsonProcessingException {
        AnthropicResponse response = mapper.readValue(rawJson, AnthropicResponse.class);
        AnthropicResponse.Usage usage = response.usage();
        return new Completion(response.firstText(),
                usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens());
    }
}
