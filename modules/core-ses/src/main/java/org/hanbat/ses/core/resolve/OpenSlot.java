package org.hanbat.ses.core.resolve;

import java.util.List;

import org.hanbat.ses.core.model.IntRange;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.VarDef;

/**
 * SlotResolver 의 출력 — SES 에서 파생된 "지금 물어봐야 할 것".
 *
 * <p>템플릿의 표현 정보(질문 문구 등)는 여기 담지 않는다. core-ses 가 template 모듈에
 * 의존하면 순환이 생기기 때문이다. 결합은 dialogue 모듈의 SlotJoiner 가 담당한다.
 *
 * @param slotName SES 에서 도출한 기계 이름. 템플릿에 대응 슬롯이 있으면 dialogue 계층이 덮어쓴다.
 * @param depth    위상 정렬용 깊이. 얕은 것(구조 결정)부터 묻는다.
 */
public record OpenSlot(
        String slotName,
        String entityPath,
        SesAnchor anchor,
        SlotKind kind,
        List<SlotOption> options,
        IntRange countRange,
        VarDef varDef,
        int depth
) {

    public OpenSlot {
        options = options == null ? List.of() : List.copyOf(options);
    }

    public static OpenSlot select(String entityPath, SesAnchor anchor,
                                  List<SlotOption> options, int depth) {
        return new OpenSlot(anchor.targetNodeId(), entityPath, anchor,
                SlotKind.SELECT, options, null, null, depth);
    }

    public static OpenSlot multiplicity(String entityPath, SesAnchor anchor,
                                        IntRange countRange, int depth) {
        return new OpenSlot(anchor.targetNodeId(), entityPath, anchor,
                SlotKind.MULTIPLICITY, List.of(), countRange, null, depth);
    }

    public static OpenSlot value(String entityPath, SesAnchor anchor, VarDef varDef, int depth) {
        return new OpenSlot(anchor.targetNodeId() + "." + varDef.name(), entityPath, anchor,
                SlotKind.VALUE, List.of(), null, varDef, depth);
    }

    public OpenSlot renamed(String newName) {
        return new OpenSlot(newName, entityPath, anchor, kind, options, countRange, varDef, depth);
    }

    /** multi-aspect 복제본에서 파생된 슬롯인가. */
    public boolean isInstanceSlot() {
        return anchor.targetNodeId() != null
                && anchor.targetNodeId().indexOf(SesAnchor.INSTANCE_SEPARATOR) >= 0;
    }
}
