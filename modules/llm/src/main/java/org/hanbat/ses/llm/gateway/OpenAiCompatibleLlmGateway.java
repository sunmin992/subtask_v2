package org.hanbat.ses.llm.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.hanbat.ses.llm.audit.LlmCallRecorder;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * OpenAI 호환 채팅 API 게이트웨이 — 오픈 모델을 붙이는 경로.
 *
 * <p>Ollama 전용 API(`/api/chat`) 대신 호환 엔드포인트를 쓴다. 그쪽이 사실상의
 * 표준이라, 게이트웨이 하나로 Ollama · vLLM · llama.cpp server · LM Studio ·
 * OpenRouter · Together 를 모두 커버한다. 런타임을 바꿀 때 base-url 과 model 만 바뀐다.
 *
 * <p>Anthropic 경로와 다른 점이 네 가지다.
 * <ul>
 *   <li>system 프롬프트가 최상위 필드가 아니라 messages 의 첫 항목이다.</li>
 *   <li>인증이 {@code Authorization: Bearer} 이고, <b>로컬 런타임은 키가 아예 필요 없다.</b>
 *       그래서 available() 이 키가 아니라 base-url 을 본다.</li>
 *   <li>토큰 상한 필드 이름이 {@code max_tokens} 이고 사용량 필드 이름도 다르다.</li>
 *   <li>{@code response_format} 으로 JSON 모드를 켤 수 있다 — 파라미터가 적은 모델에서
 *       이 한 줄이 스키마 준수율을 크게 올린다.</li>
 * </ul>
 */
public class OpenAiCompatibleLlmGateway extends HttpLlmGateway {

    private static final String CHAT_PATH = "/v1/chat/completions";

    public OpenAiCompatibleLlmGateway(WebClient client, ObjectMapper mapper,
                                      LlmCallRecorder recorder, LlmProperties props) {
        super(client, mapper, recorder, props);
    }

    /**
     * 키가 아니라 주소가 있으면 쓸 수 있다.
     *
     * <p>로컬 Ollama 는 인증이 없다. 여기서 키를 요구하면 오픈 모델 경로가
     * 영원히 비활성으로 남는다.
     */
    @Override
    public boolean available() {
        return props.baseUrl() != null && !props.baseUrl().isBlank();
    }

    @Override
    protected String unavailableReason() {
        return "OpenAI 호환 엔드포인트 주소(llm.base-url)가 설정되어 있지 않습니다.";
    }

    @Override
    protected String endpoint() {
        return CHAT_PATH;
    }

    @Override
    protected Map<String, Object> requestBody(String system, String user,
                                              String model, boolean jsonOnly) {
        List<Map<String, String>> messages = new ArrayList<>(2);
        if (system != null && !system.isBlank()) {
            messages.add(Map.of("role", "system", "content", system));
        }
        messages.add(Map.of("role", "user", "content", user));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", props.temperature());
        body.put("max_tokens", props.maxTokens());
        // 스트리밍을 끄지 않으면 SSE 가 흘러와 봉투 파싱이 깨진다.
        body.put("stream", false);
        if (jsonOnly && props.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    @Override
    protected Completion parseResponse(String rawJson) throws JsonProcessingException {
        OpenAiChatResponse response = mapper.readValue(rawJson, OpenAiChatResponse.class);
        OpenAiChatResponse.Usage usage = response.usage();
        return new Completion(response.firstContent(),
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens());
    }
}
