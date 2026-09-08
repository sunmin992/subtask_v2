package org.hanbat.ses.dialogue.control;

import java.util.List;

import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.resolve.OpenSlot;
import org.hanbat.ses.core.resolve.SlotKind;
import org.hanbat.ses.template.model.SlotSpec;
import org.hanbat.ses.template.model.ValueSlot;

/**
 * SES 가 계산한 열린 슬롯 + 템플릿이 제공하는 표현 정보.
 *
 * <p>이 결합은 dialogue 계층에서만 일어난다. core-ses 가 template 을 알면 순환이 생기고,
 * 무엇보다 "SES 가 단일 진실 원천"이라는 원칙이 흐려진다. 템플릿 슬롯이 없어도
 * 질문은 나가야 한다 — 문구가 투박할 뿐이다.
 */
public record ResolvedSlot(String name, OpenSlot open, SlotSpec spec) {

    public SlotKind kind() {
        return open.kind();
    }

    public SesAnchor anchor() {
        return open.anchor();
    }

    public String entityPath() {
        return open.entityPath();
    }

    public int depth() {
        return open.depth();
    }

    public List<String> dependsOn() {
        return spec == null ? List.of() : spec.dependsOn();
    }

    /** 템플릿 기본값. 턴 예산이 끝났을 때 UnfilledPolicy 가 쓴다. */
    public Object templateDefault() {
        return spec instanceof ValueSlot v ? v.defaultValue() : null;
    }

    /** LLM 추출을 허용하는가. */
    public boolean inferable() {
        return spec instanceof ValueSlot v && v.inferable();
    }
}
