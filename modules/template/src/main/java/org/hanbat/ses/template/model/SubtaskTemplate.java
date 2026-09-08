package org.hanbat.ses.template.model;

import java.util.List;
import java.util.Optional;

import org.hanbat.ses.core.model.SesAnchor;

/**
 * 서브태스크 템플릿 — 계획서의 7개 블록(A~G)이 그대로 필드가 된다.
 *
 * <p>slots 에 있는 것은 표현 정보와 SES 앵커뿐이다. "어떤 슬롯이 지금 열려 있는가"는
 * SlotResolver 가 SES 트리에서 계산한다. 템플릿에 슬롯이 정의되어 있어도 상위 구조가
 * 결정되기 전에는 질문 목록에 나타나지 않는다.
 */
public record SubtaskTemplate(
        String id,
        String version,
        String name,
        Routing routing,
        List<SlotSpec> slots,
        DialogueControl dialogue,
        StructureBinding binding,
        ValidationSpec validation,
        ExecutionSpec execution,
        OutputSpec output,
        TemplateMeta meta
) {

    public SubtaskTemplate {
        slots = slots == null ? List.of() : List.copyOf(slots);
        dialogue = dialogue == null ? DialogueControl.defaults() : dialogue;
        validation = validation == null ? ValidationSpec.empty() : validation;
        execution = execution == null ? ExecutionSpec.defaults() : execution;
        output = output == null ? OutputSpec.defaults() : output;
    }

    public String key() {
        return id + "@" + version;
    }

    public Optional<SlotSpec> slotByName(String slotName) {
        return slots.stream().filter(s -> s.name().equals(slotName)).findFirst();
    }

    /**
     * 앵커로 슬롯을 찾는다 — 템플릿과 SES 를 잇는 정식 경로.
     *
     * <p>multi-aspect 복제본의 앵커는 노드 id 에 인스턴스 접미사가 붙으므로
     * 프로토타입 id 로 비교한다. 그래야 "버스" 슬롯 하나가 복제본 전부를 담당한다.
     */
    public Optional<SlotSpec> slotByAnchor(SesAnchor anchor) {
        return slots.stream()
                .filter(s -> matches(s.anchor(), anchor))
                .findFirst();
    }

    private static boolean matches(SesAnchor declared, SesAnchor actual) {
        if (declared == null || actual == null) {
            return false;
        }
        if (declared.axis() != actual.axis()) {
            return false;
        }
        if (!java.util.Objects.equals(declared.prototypeNodeId(), actual.prototypeNodeId())) {
            return false;
        }
        return java.util.Objects.equals(declared.varName(), actual.varName());
    }
}
