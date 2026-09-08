package org.hanbat.ses.dialogue.routing;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hanbat.ses.template.registry.TemplateRegistry;
import org.springframework.stereotype.Component;

/**
 * 키워드 매칭 라우터 — LLM 라우팅의 폴백이자 기준선.
 *
 * <p>LLM 을 껐을 때 기존 경로가 그대로 동작하는 것이 폴백 설계의 증거다.
 * 이 구현이 없으면 API 키가 만료된 순간 시스템 전체가 멈춘다.
 */
@Component
public class KeywordRequestRouter implements RequestRouter {

    private final TemplateRegistry registry;

    public KeywordRequestRouter(TemplateRegistry registry) {
        this.registry = registry;
    }

    /** 키워드 매칭은 확신할 수 있는 종류의 근거가 아니다. 상한을 낮게 둔다. */
    private static double score(long hits) {
        return hits == 0 ? 0.0 : Math.min(0.8, 0.4 + 0.2 * hits);
    }

    @Override
    public Optional<RoutingDecision> route(String request) {
        if (request == null || request.isBlank()) {
            return Optional.empty();
        }
        String normalized = request.toLowerCase(Locale.ROOT);
        List<SubtaskTemplate> candidates = registry.findAllActive();

        record Scored(SubtaskTemplate template, long hits) {
        }

        List<Scored> scored = candidates.stream()
                .map(t -> new Scored(t, t.routing().triggerPatterns().stream()
                        .filter(p -> normalized.contains(p.toLowerCase(Locale.ROOT)))
                        .count()))
                .sorted(Comparator.comparingLong(Scored::hits).reversed()
                        .thenComparing(s -> -s.template().routing().priority()))
                .toList();

        // 일치가 없어도 후보 목록은 만들어 둔다. FR-104 가 사용자에게 보여 줄 것이 필요하다.
        List<TemplateCandidate> all = scored.stream()
                .map(s -> new TemplateCandidate(
                        s.template().id(), s.template().name(),
                        s.template().routing().intentDescription(),
                        score(s.hits()),
                        s.hits() > 0
                                ? "키워드 " + s.hits() + "개 일치"
                                : "키워드 일치 없음"))
                .toList();

        Scored best = scored.isEmpty() ? null : scored.get(0);
        if (best == null) {
            return Optional.empty();
        }
        if (best.hits() == 0) {
            // 아무 키워드도 걸리지 않았다. 후보만 담아 "고를 수 없다"고 알린다.
            return Optional.of(new RoutingDecision(null, 0.0,
                    "요청문에서 어떤 템플릿의 키워드도 찾지 못했습니다.",
                    RoutingDecision.SOURCE_KEYWORD, all));
        }
        return Optional.of(new RoutingDecision(best.template().id(), score(best.hits()),
                "요청문에서 키워드 " + best.hits() + "개가 일치했습니다.",
                RoutingDecision.SOURCE_KEYWORD, all));
    }
}
