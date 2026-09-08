package org.hanbat.ses.dialogue.routing;

import java.util.Optional;

/**
 * 라우팅 결과.
 *
 * @param source 어느 경로로 정해졌는지. "LLM 이 골랐다"와 "키워드가 걸렸다"는
 *               신뢰도가 다르고, 감사할 때도 구분되어야 한다.
 */
public record RoutingDecision(String templateId, double confidence, String reason, String source,
                              java.util.List<TemplateCandidate> candidates) {

    public RoutingDecision(String templateId, double confidence, String reason, String source) {
        this(templateId, confidence, reason, source, java.util.List.of());
    }

    public RoutingDecision {
        candidates = candidates == null ? java.util.List.of() : java.util.List.copyOf(candidates);
    }

    /**
     * 확정하기에 근거가 부족한가 (FR-104).
     *
     * <p>이때는 임의로 하나를 고르지 않고 사용자에게 후보를 제시한다. 잘못 고른 템플릿은
     * 도메인 SES 자체가 달라지므로, 대화를 몇 턴 진행한 뒤에는 되돌리기가 훨씬 비싸다.
     */
    public boolean needsUserChoice(double threshold) {
        return templateId == null || templateId.isBlank() || confidence < threshold;
    }

    public static final String SOURCE_EXPLICIT = "explicit";
    public static final String SOURCE_LLM = "llm";
    public static final String SOURCE_KEYWORD = "keyword";
    public static final String SOURCE_FALLBACK = "fallback";

    public static Optional<RoutingDecision> none() {
        return Optional.empty();
    }
}
