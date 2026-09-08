package org.hanbat.ses.llm.gateway;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** OpenAI 호환 채팅 응답 중 필요한 부분만. Ollama/vLLM/llama.cpp 가 모두 이 모양을 낸다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatResponse(
        String id,
        String model,
        List<Choice> choices,
        Usage usage
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            int index,
            Message message,
            @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens) {
    }

    public String firstContent() {
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        Message m = choices.get(0).message();
        return m == null || m.content() == null ? "" : m.content();
    }
}
