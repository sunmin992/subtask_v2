package org.hanbat.ses.dialogue.routing;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.hanbat.ses.llm.gateway.LlmGateway;
import org.hanbat.ses.llm.gateway.Purpose;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hanbat.ses.template.registry.TemplateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * LLM 분류 라우터. 실패하거나 확신이 낮으면 키워드 라우터로 떨어진다.
 *
 * <p>LLM 은 자연어 경계에만 쓴다. 여기서 하는 일은 "이 문장이 어느 서브태스크에
 * 해당하는가"를 고르는 것뿐이고, 고른 뒤의 모든 결정은 결정론적 코드가 한다.
 */
@Component
@Primary
public class LlmRequestRouter implements RequestRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRequestRouter.class);

    private static final double MIN_CONFIDENCE = 0.5;

    private static final String SYSTEM = """
            당신은 시뮬레이션 요청을 서브태스크 템플릿으로 분류하는 라우터입니다.
            주어진 후보 중에서 요청에 가장 잘 맞는 것 하나를 고르세요.
            어느 것도 맞지 않으면 templateId 를 빈 문자열로 두고 confidence 를 0 으로 하세요.
            추측하지 말고, 확신이 없으면 낮은 confidence 를 주세요.
            """;

    /** LLM 이 채워야 할 구조. 스키마는 이 record 에서 자동 생성된다. */
    public record RoutingAnswer(String templateId, double confidence, String reason) {
    }

    private final LlmGateway llm;
    private final TemplateRegistry registry;
    private final KeywordRequestRouter fallback;

    public LlmRequestRouter(LlmGateway llm, TemplateRegistry registry,
                            KeywordRequestRouter fallback) {
        this.llm = llm;
        this.registry = registry;
        this.fallback = fallback;
    }

    @Override
    public Optional<RoutingDecision> route(String request) {
        if (!llm.available()) {
            return fallback.route(request);
        }
        List<SubtaskTemplate> candidates = registry.findAllActive();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        try {
            RoutingAnswer answer = llm.complete(SYSTEM, prompt(request, candidates),
                    RoutingAnswer.class, Purpose.ROUTING);
            boolean known = candidates.stream().anyMatch(t -> t.id().equals(answer.templateId()));
            if (known && answer.confidence() >= MIN_CONFIDENCE) {
                return Optional.of(new RoutingDecision(answer.templateId(), answer.confidence(),
                        answer.reason(), RoutingDecision.SOURCE_LLM, describe(candidates)));
            }
            log.debug("LLM 라우팅을 채택하지 않았습니다 (known={}, confidence={}). 키워드로 폴백합니다.",
                    known, answer.confidence());
        } catch (RuntimeException e) {
            log.warn("LLM 라우팅 실패, 키워드 매칭으로 폴백합니다: {}", e.getMessage());
        }
        return fallback.route(request);
    }

    /** 후보 목록. 확신이 낮을 때 사용자에게 보여 줄 것이 필요하다(FR-104). */
    private List<TemplateCandidate> describe(List<SubtaskTemplate> candidates) {
        return candidates.stream()
                .map(t -> new TemplateCandidate(t.id(), t.name(),
                        t.routing().intentDescription(), 0.0, "라우팅 후보"))
                .toList();
    }

    private String prompt(String request, List<SubtaskTemplate> candidates) {
        String list = candidates.stream()
                .map(t -> "- id: %s\n  이름: %s\n  설명: %s\n  키워드: %s"
                        .formatted(t.id(), t.name(),
                                t.routing().intentDescription(),
                                String.join(", ", t.routing().triggerPatterns())))
                .collect(Collectors.joining("\n"));
        return "사용자 요청:\n" + request + "\n\n후보 템플릿:\n" + list;
    }
}
