package org.hanbat.ses.core.resolve;

import java.util.List;

/**
 * SELECT 슬롯의 선택지.
 *
 * @param id      SpecNode 변형의 엔티티 id.
 * @param label   사용자에게 보이는 이름.
 * @param aliases 같은 것을 가리키는 다른 이름. LLM 프롬프트와 답변 매칭에 쓴다 —
 *                요청문의 "곤돌라"를 선택지 "케이블카"에 맞추려면 이 목록이 필요하다.
 */
public record SlotOption(String id, String label, List<String> aliases) {

    public SlotOption {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public SlotOption(String id, String label) {
        this(id, label, List.of());
    }

    /** 사용자에게 보여줄 이름 — 별칭이 있으면 함께 밝힌다. */
    public String describe() {
        return aliases.isEmpty() ? label : label + "(=" + String.join(", ", aliases) + ")";
    }
}
