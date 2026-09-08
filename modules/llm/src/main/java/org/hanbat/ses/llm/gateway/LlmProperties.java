package org.hanbat.ses.llm.gateway;

import java.util.Map;

/**
 * LLM 접속 설정.
 *
 * @param provider        공급자. AUTO 는 키 유무로 Anthropic/비활성을 고른다.
 * @param modelByPurpose  목적별 모델 오버라이드. 분류처럼 값싼 일에 큰 모델을 쓸 이유가 없다.
 * @param jsonMode        구조화 출력 시 {@code response_format} 으로 JSON 모드를 켤지.
 *                        OpenAI 호환 경로에서만 쓰이며, 작은 모델의 스키마 준수율을 크게 올린다.
 * @param maxParseAttempts 파싱 실패 시 총 시도 횟수. 파라미터가 적은 오픈 모델은 더 필요하다.
 */
public record LlmProperties(
        LlmProvider provider,
        String apiKey,
        String baseUrl,
        String defaultModel,
        Map<String, String> modelByPurpose,
        double temperature,
        int maxTokens,
        long timeoutMs,
        int maxRetries,
        boolean jsonMode,
        int maxParseAttempts
) {

    public LlmProperties {
        provider = provider == null ? LlmProvider.AUTO : provider;
        // 기본 주소와 기본 모델은 Anthropic 경로에서만 채운다.
        // 공급자와 무관하게 채우면 base-url 을 빠뜨린 오픈 모델 설정이
        // 조용히 Anthropic API 를 가리키게 되고, 그 사실은 401 로만 드러난다.
        boolean anthropicPath = provider == LlmProvider.ANTHROPIC || provider == LlmProvider.AUTO;
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = anthropicPath ? "https://api.anthropic.com" : null;
        }
        if (defaultModel == null || defaultModel.isBlank()) {
            defaultModel = anthropicPath ? "claude-sonnet-5" : null;
        }
        modelByPurpose = modelByPurpose == null ? Map.of() : Map.copyOf(modelByPurpose);
        if (maxTokens <= 0) {
            maxTokens = 4096;
        }
        if (timeoutMs <= 0) {
            timeoutMs = 60_000L;
        }
        if (maxRetries < 0) {
            maxRetries = 3;
        }
        if (maxParseAttempts <= 0) {
            maxParseAttempts = 2;
        }
    }

    public String modelFor(Purpose purpose) {
        return modelByPurpose.getOrDefault(purpose.name(), defaultModel);
    }

    /** AUTO 를 실제 공급자로 확정한다. */
    public LlmProvider effectiveProvider() {
        if (provider != LlmProvider.AUTO) {
            return provider;
        }
        return apiKey != null && !apiKey.isBlank() ? LlmProvider.ANTHROPIC : LlmProvider.DISABLED;
    }

    public static LlmProperties disabled() {
        return new LlmProperties(LlmProvider.DISABLED, null, null, null, Map.of(),
                0.2, 4096, 60_000L, 3, false, 2);
    }

    /** 테스트와 로컬 실행용 — Anthropic 경로 최소 설정. */
    public static LlmProperties anthropic(String apiKey, String baseUrl, String model) {
        return new LlmProperties(LlmProvider.ANTHROPIC, apiKey, baseUrl, model, Map.of(),
                0.2, 1024, 5_000L, 2, false, 2);
    }

    /** 테스트와 로컬 실행용 — OpenAI 호환 경로 최소 설정. 키 없이 쓸 수 있다. */
    public static LlmProperties openAiCompatible(String baseUrl, String model) {
        return new LlmProperties(LlmProvider.OPENAI_COMPATIBLE, null, baseUrl, model, Map.of(),
                0.2, 1024, 30_000L, 2, true, 3);
    }
}
