package org.hanbat.ses.llm.gateway;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.hanbat.ses.llm.audit.LlmCallRecord;
import org.hanbat.ses.llm.audit.LlmCallRecorder;
import org.hanbat.ses.llm.audit.SessionContext;
import org.hanbat.ses.llm.schema.JsonSchemaGenerator;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

/**
 * HTTP 로 채팅 API 를 부르는 게이트웨이의 공통 골격.
 *
 * <p>공급자마다 다른 것은 엔드포인트 경로, 요청 본문 모양, 응답에서 텍스트를 꺼내는 방법
 * 세 가지뿐이다. 재시도 정책, 스키마 주입, 코드펜스 제거, 파싱 실패 재시도, 감사 기록은
 * 전부 같다. 공급자를 늘릴 때마다 그 다섯 가지를 복사하면 그중 하나는 반드시 달라진다 —
 * 그리고 달라진 쪽은 "왜 이 공급자만 재시도를 안 하지" 같은 형태로 한참 뒤에 발견된다.
 */
public abstract class HttpLlmGateway implements LlmGateway {

    protected final WebClient client;
    protected final ObjectMapper mapper;
    private final LlmCallRecorder recorder;
    protected final LlmProperties props;

    protected HttpLlmGateway(WebClient client, ObjectMapper mapper,
                             LlmCallRecorder recorder, LlmProperties props) {
        this.client = client;
        this.mapper = mapper;
        this.recorder = recorder;
        this.props = props;
    }

    // ------------------------------------------------------------ 공급자별 구현

    /** 채팅 완료 엔드포인트 경로. */
    protected abstract String endpoint();

    /** 요청 본문. system 이 별도 필드인 공급자와 messages 에 섞는 공급자가 갈린다. */
    protected abstract Map<String, Object> requestBody(String system, String user,
                                                       String model, boolean jsonOnly);

    /** 응답에서 본문 텍스트와 토큰 사용량을 꺼낸다. */
    protected abstract Completion parseResponse(String rawJson) throws JsonProcessingException;

    /** 응답 텍스트와 사용량. */
    public record Completion(String text, Integer inputTokens, Integer outputTokens) {
    }

    // ------------------------------------------------------------ 공통 동작

    @Override
    public String text(String system, String user, Purpose purpose) {
        return call(system, user, purpose, false).text();
    }

    @Override
    public <T> T complete(String system, String user, Class<T> type, Purpose purpose) {
        // 스키마와 예시를 함께 준다. 예시를 뒤에 두는 것이 중요하다 —
        // 작은 모델은 마지막에 본 형태를 흉내 내는 경향이 강하고, 스키마만 주면
        // 스키마 자체를 답으로 되돌려주는 일이 흔하다.
        String prompt = user
                + "\n\n아래 JSON 스키마를 만족하는 JSON 객체 하나만 출력하세요."
                + "\n설명, 코드펜스, 스키마 자체를 출력하지 마세요."
                + "\n\n스키마:\n" + JsonSchemaGenerator.of(type)
                + "\n\n정확히 이 형태로, 값만 채워 답하세요:\n" + JsonSchemaGenerator.exampleOf(type);

        String raw = null;
        int attempts = Math.max(1, props.maxParseAttempts());
        for (int attempt = 0; attempt < attempts; attempt++) {
            raw = call(system, prompt, purpose, true).text();
            try {
                return mapper.readValue(unwrapSchemaEcho(stripFence(raw)), type);
            } catch (JsonProcessingException e) {
                if (attempt == attempts - 1) {
                    throw new LlmParseException(raw, e);
                }
                // 실패 사유를 그대로 돌려주면 다음 시도의 성공률이 눈에 띄게 오른다.
                // 작은 오픈 모델에서는 이 되먹임이 특히 크게 작용한다.
                prompt = prompt + "\n\n이전 응답이 파싱에 실패했습니다: " + e.getOriginalMessage()
                        + "\n스키마를 다시 확인하고 JSON 객체만 출력하세요.";
            }
        }
        throw new LlmParseException(raw, new IllegalStateException("unreachable"));
    }

    /**
     * @param jsonOnly JSON 만 받아야 하는 호출인가. 공급자가 JSON 모드를 지원하면 켠다.
     */
    protected Completion call(String system, String user, Purpose purpose, boolean jsonOnly) {
        if (!available()) {
            throw new LlmUnavailableException(unavailableReason());
        }
        String model = props.modelFor(purpose);
        Map<String, Object> body = requestBody(system, user, model, jsonOnly);

        long startNs = System.nanoTime();
        try {
            String rawJson = client.post().uri(endpoint())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(props.timeoutMs()))
                    .retryWhen(Retry.backoff(props.maxRetries(), Duration.ofSeconds(1))
                            .filter(HttpLlmGateway::isRetryable))
                    .block();

            Completion completion = parseResponse(rawJson == null ? "" : rawJson);
            audit(purpose, model, body, completion, startNs, null);
            return completion;
        } catch (JsonProcessingException e) {
            audit(purpose, model, body, null, startNs, "응답 봉투를 해석하지 못했습니다: " + e);
            throw new LlmUnavailableException(
                    "LLM 응답 형식이 예상과 다릅니다: " + e.getOriginalMessage(), e);
        } catch (RuntimeException e) {
            audit(purpose, model, body, null, startNs, e.toString());
            if (e instanceof LlmUnavailableException) {
                throw e;
            }
            throw new LlmUnavailableException("LLM 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }

    protected String unavailableReason() {
        return "LLM 게이트웨이를 사용할 수 없습니다.";
    }

    /** 429 와 5xx 만 재시도한다. 4xx 는 재시도해도 같은 답이 온다. */
    private static boolean isRetryable(Throwable t) {
        if (t instanceof WebClientResponseException w) {
            int status = w.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return !(t instanceof LlmParseException);
    }

    private void audit(Purpose purpose, String model, Object request,
                       Completion completion, long startNs, String error) {
        try {
            recorder.record(new LlmCallRecord(
                    UUID.randomUUID(),
                    SessionContext.get(),
                    purpose,
                    model,
                    safeWrite(request),
                    completion == null ? null : completion.text(),
                    (int) ((System.nanoTime() - startNs) / 1_000_000L),
                    completion == null ? null : completion.inputTokens(),
                    completion == null ? null : completion.outputTokens(),
                    error));
        } catch (RuntimeException ignored) {
            // 감사 로그 저장 실패가 본 흐름을 막으면 안 된다.
        }
    }

    private String safeWrite(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    /**
     * 모델이 스키마를 그대로 되돌려준 경우 답을 꺼낸다.
     *
     * <p>파라미터가 적은 모델은 {@code {"type":"object","properties":{...}}} 형태로
     * 답하는 일이 잦다. 값이 properties 안에 들어 있으므로 최상위에는 필요한 필드가 없고,
     * 파싱은 성공하지만 모든 필드가 비어 나온다 — 오류 없이 조용히 틀리는 쪽이라 더 나쁘다.
     * 스키마 봉투가 확실할 때만 한 겹 벗긴다.
     */
    static String unwrapSchemaEcho(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    SHARED.readTree(json);
            if (root.isObject() && root.has("properties")
                    && root.path("type").asText("").equals("object")
                    && root.path("properties").isObject()) {
                return root.path("properties").toString();
            }
        } catch (JsonProcessingException ignored) {
            // 파싱이 안 되면 아래 단계가 사유를 밝히며 실패한다.
        }
        return json;
    }

    /** 봉투 판별 전용 — 인스턴스 mapper 설정과 무관하게 동작해야 한다. */
    private static final ObjectMapper SHARED = new ObjectMapper();

    /**
     * 모델이 붙여 보내는 장식을 걷어낸다.
     *
     * <p>지시를 해도 코드펜스나 앞뒤 설명을 붙이는 모델이 흔하고, 파라미터가 적은
     * 오픈 모델은 특히 그렇다. 첫 중괄호부터 마지막 중괄호까지만 취하면 대부분 살아난다.
     */
    static String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                s = s.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }
}
