package org.hanbat.ses.dialogue.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.resolve.OpenSlot;
import org.hanbat.ses.core.resolve.SlotResolver;
import org.hanbat.ses.template.model.SlotSpec;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.springframework.stereotype.Component;

/**
 * 열린 슬롯에 템플릿 표현 정보를 붙인다.
 *
 * <p>이름 붙이기 규칙이 중요하다. 템플릿에 대응 슬롯이 있으면 그 이름을 쓰고
 * (사람이 읽을 수 있고 API 계약이 안정된다), 없으면 SES 가 만든 기계 이름을 쓴다.
 * multi-aspect 복제본은 템플릿 슬롯 하나를 여럿이 공유하므로 이름 뒤에 인덱스를 붙인다 —
 * 안 그러면 "버스 정원" 질문 세 개가 같은 키로 나가 서로를 덮어쓴다.
 */
@Component
public class SlotJoiner {

    private final SlotResolver resolver = new SlotResolver();

    public List<ResolvedSlot> scanAndJoin(SesNode ses, SubtaskTemplate template) {
        return join(resolver.scan(ses), template);
    }

    public List<ResolvedSlot> join(List<OpenSlot> open, SubtaskTemplate template) {
        List<ResolvedSlot> out = new ArrayList<>(open.size());
        for (OpenSlot slot : open) {
            SlotSpec spec = template == null ? null
                    : template.slotByAnchor(slot.anchor()).orElse(null);
            out.add(new ResolvedSlot(nameFor(slot, spec), slot, spec));
        }
        return List.copyOf(out);
    }

    public Map<String, ResolvedSlot> byName(List<ResolvedSlot> slots) {
        Map<String, ResolvedSlot> map = new LinkedHashMap<>();
        slots.forEach(s -> map.put(s.name(), s));
        return map;
    }

    private String nameFor(OpenSlot slot, SlotSpec spec) {
        if (spec == null) {
            return slot.slotName();
        }
        String index = instanceIndex(slot.anchor());
        return index == null ? spec.name() : spec.name() + "[" + index + "]";
    }

    /** multi-aspect 복제본이면 인덱스를, 아니면 null. */
    private String instanceIndex(SesAnchor anchor) {
        String id = anchor.targetNodeId();
        if (id == null) {
            return null;
        }
        int i = id.indexOf(SesAnchor.INSTANCE_SEPARATOR);
        return i < 0 ? null : id.substring(i + 1);
    }
}
