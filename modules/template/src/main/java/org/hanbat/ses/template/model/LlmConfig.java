package org.hanbat.ses.template.model;

/** 이 서브태스크가 LLM 을 부를 때의 설정. 모델을 지정하지 않으면 앱 기본값을 쓴다. */
public record LlmConfig(String model, Double temperature, Integer maxTokens) {

    public static LlmConfig defaults() {
        return new LlmConfig(null, 0.2, 4096);
    }
}
