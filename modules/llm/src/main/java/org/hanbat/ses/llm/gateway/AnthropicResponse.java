package org.hanbat.ses.llm.gateway;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Anthropic Messages API 응답 중 필요한 부분만. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnthropicResponse(
        String id,
        String model,
        List<ContentBlock> content,
        @JsonProperty("stop_reason") String stopReason,
        Usage usage
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContentBlock(String type, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens) {
    }

    public String firstText() {
        if (content == null) {
            return "";
        }
        return content.stream()
                .filter(c -> "text".equals(c.type()))
                .map(ContentBlock::text)
                .findFirst()
                .orElse("");
    }
}
