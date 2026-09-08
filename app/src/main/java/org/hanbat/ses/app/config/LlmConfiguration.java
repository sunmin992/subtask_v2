package org.hanbat.ses.app.config;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.hanbat.ses.llm.audit.LlmCallRecorder;
import org.hanbat.ses.llm.gateway.AnthropicLlmGateway;
import org.hanbat.ses.llm.gateway.DisabledLlmGateway;
import org.hanbat.ses.llm.gateway.LlmGateway;
import org.hanbat.ses.llm.gateway.LlmProperties;
import org.hanbat.ses.llm.gateway.LlmProvider;
import org.hanbat.ses.llm.gateway.OpenAiCompatibleLlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * LLM 연동 설정.
 *
 * <p>공급자가 셋이다.
 * <ul>
 *   <li><b>anthropic</b> — Messages API</li>
 *   <li><b>openai-compatible</b> — 오픈 모델. Ollama, vLLM, llama.cpp, LM Studio, OpenRouter</li>
 *   <li><b>disabled</b> — 라우팅은 키워드로, 슬롯 채우기는 질문으로 대체</li>
 * </ul>
 *
 * <p>어느 쪽이든 서버는 정상적으로 뜨고 전체 흐름이 돈다. 이것이 이 시스템의
 * 중요한 성질이라, 공급자를 늘려도 그 성질은 유지되어야 한다.
 */
@Configuration
public class LlmConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

    @Bean
    public LlmProperties llmProperties(
            @Value("${llm.provider:auto}") String provider,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.model:}") String model,
            @Value("${llm.model-by-purpose.ROUTING:}") String routingModel,
            @Value("${llm.model-by-purpose.EXTRACTION:}") String extractionModel,
            @Value("${llm.temperature:0.2}") double temperature,
            @Value("${llm.max-tokens:4096}") int maxTokens,
            @Value("${llm.timeout-ms:60000}") long timeoutMs,
            @Value("${llm.max-retries:3}") int maxRetries,
            @Value("${llm.json-mode:true}") boolean jsonMode,
            @Value("${llm.max-parse-attempts:2}") int maxParseAttempts) {

        Map<String, String> byPurpose = new LinkedHashMap<>();
        putIfSet(byPurpose, "ROUTING", routingModel);
        putIfSet(byPurpose, "EXTRACTION", extractionModel);

        return new LlmProperties(LlmProvider.parse(provider), emptyToNull(apiKey),
                emptyToNull(baseUrl), emptyToNull(model), byPurpose,
                temperature, maxTokens, timeoutMs, maxRetries, jsonMode, maxParseAttempts);
    }

    @Bean
    public WebClient llmWebClient(WebClient.Builder builder, LlmProperties props) {
        WebClient.Builder b = builder
                .baseUrl(props.baseUrl())
                // 긴 SES 트리를 프롬프트에 실으면 기본 256KB 버퍼를 넘길 수 있다.
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                        .build())
                .defaultHeader("content-type", "application/json");

        if (props.effectiveProvider() == LlmProvider.ANTHROPIC) {
            b.defaultHeader("x-api-key", props.apiKey() == null ? "" : props.apiKey())
                    .defaultHeader("anthropic-version", "2023-06-01");
        } else if (props.apiKey() != null && !props.apiKey().isBlank()) {
            // 로컬 런타임은 키가 없다. 호스팅 오픈 모델(OpenRouter 등)만 Bearer 를 붙인다.
            b.defaultHeader("Authorization", "Bearer " + props.apiKey());
        }
        return b.build();
    }

    @Bean
    public LlmGateway llmGateway(WebClient llmWebClient, ObjectMapper mapper,
                                 ObjectProvider<LlmCallRecorder> recorders,
                                 LlmProperties props) {
        LlmCallRecorder recorder = recorders.getIfAvailable(LlmCallRecorder::noop);

        return switch (props.effectiveProvider()) {
            case ANTHROPIC -> {
                log.info("Anthropic 게이트웨이를 사용합니다 (model={}).", props.defaultModel());
                yield new AnthropicLlmGateway(llmWebClient, mapper, recorder, props);
            }
            case OPENAI_COMPATIBLE -> {
                log.info("OpenAI 호환 게이트웨이를 사용합니다 (base-url={}, model={}, jsonMode={}).",
                        props.baseUrl(), props.defaultModel(), props.jsonMode());
                yield new OpenAiCompatibleLlmGateway(llmWebClient, mapper, recorder, props);
            }
            case DISABLED, AUTO -> {
                log.info("LLM 이 비활성입니다. 라우팅은 키워드로, 슬롯 채우기는 질문으로 대체됩니다.");
                yield new DisabledLlmGateway();
            }
        };
    }

    private static void putIfSet(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
