package org.hanbat.ses.template.model;

import java.util.List;

import org.hanbat.ses.core.model.Range;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.VarType;

/**
 * 값 설정 슬롯 — 답변이 파라미터로 바인딩된다.
 *
 * <p>defaultValue 는 SES 의 기본값과 성격이 다르다. SES 쪽 기본값은 "도메인이 이미
 * 답을 아는 값"이라 질문 자체가 생기지 않지만, 여기 기본값은 "물어보되 사용자가
 * 끝내 답하지 않으면 쓸 값"이다. 턴 예산이 바닥났을 때 UnfilledPolicy 가 이것을 쓴다.
 */
public record ValueSlot(
        String name,
        SesAnchor anchor,
        VarType type,
        String unit,
        Range range,
        Object defaultValue,
        boolean inferable,
        QuestionSpec question,
        List<String> dependsOn
) implements SlotSpec {

    public ValueSlot {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
