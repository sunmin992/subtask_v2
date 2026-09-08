package org.hanbat.ses.template.model;

import java.util.List;

import org.hanbat.ses.core.model.SesAnchor;

/** 참조 슬롯 — 선행 서브태스크의 출력을 값으로 받는다. 사용자에게 묻지 않는다. */
public record ReferenceSlot(
        String name,
        SesAnchor anchor,
        String sourceTaskId,
        String sourceField,
        QuestionSpec question,
        List<String> dependsOn
) implements SlotSpec {

    public ReferenceSlot {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
