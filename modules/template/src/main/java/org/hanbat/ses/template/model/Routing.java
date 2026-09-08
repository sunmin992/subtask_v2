package org.hanbat.ses.template.model;

import java.util.List;

/**
 * 적용 대상.
 *
 * @param intentDescription LLM 분류용 설명. 키워드로 못 잡는 요청을 여기서 잡는다.
 * @param triggerPatterns   키워드 폴백. LLM 이 없거나 실패해도 라우팅이 동작해야 한다.
 */
public record Routing(
        List<String> triggerPatterns,
        String intentDescription,
        int priority,
        List<Dependency> prerequisites
) {

    public Routing {
        triggerPatterns = triggerPatterns == null ? List.of() : List.copyOf(triggerPatterns);
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
    }
}
