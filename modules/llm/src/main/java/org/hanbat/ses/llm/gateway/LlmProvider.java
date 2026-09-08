package org.hanbat.ses.llm.gateway;

/**
 * LLM 공급자.
 *
 * <p>AUTO 는 설정을 보고 고른다 — Anthropic 키가 있으면 그쪽, 없으면 비활성.
 * 오픈 모델은 로컬 런타임이 떠 있는지를 서버가 알 수 없으므로 자동 선택 대상이 아니다.
 * 명시적으로 켜야 한다.
 */
public enum LlmProvider {
    AUTO,
    DISABLED,
    ANTHROPIC,
    /** OpenAI 호환 엔드포인트 — Ollama, vLLM, llama.cpp, LM Studio, OpenRouter 등. */
    OPENAI_COMPATIBLE;

    public static LlmProvider parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        String normalized = raw.trim().toUpperCase().replace('-', '_');
        // 흔히 쓰는 별칭을 받아 준다.
        return switch (normalized) {
            case "OLLAMA", "VLLM", "LLAMACPP", "LLAMA_CPP", "LMSTUDIO", "LM_STUDIO",
                 "OPENROUTER", "TOGETHER", "OPENAI" -> OPENAI_COMPATIBLE;
            case "NONE", "OFF", "FALSE" -> DISABLED;
            default -> {
                try {
                    yield valueOf(normalized);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "알 수 없는 LLM 공급자입니다: " + raw
                                    + " (가능: auto, disabled, anthropic, openai-compatible, ollama)");
                }
            }
        };
    }
}
