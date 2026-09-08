package org.hanbat.ses.template.model;

import java.util.List;

import org.hanbat.ses.core.model.SesAnchor;

/**
 * 구조 결정 슬롯 — 답변이 SES 를 가지치기한다.
 *
 * <p>options 는 표시용이다. 실제 선택지는 SpecNode 의 변형 목록이 갖고 있고,
 * 둘이 어긋나면 TemplateConsistencyChecker 가 등록 시점에 잡아낸다.
 */
public record StructuralSlot(
        String name,
        SesAnchor anchor,
        StructuralKind structuralKind,
        List<String> options,
        QuestionSpec question,
        List<String> dependsOn
) implements SlotSpec {

    public StructuralSlot {
        options = options == null ? List.of() : List.copyOf(options);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
