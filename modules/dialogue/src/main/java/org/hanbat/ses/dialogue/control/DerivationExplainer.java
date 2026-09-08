package org.hanbat.ses.dialogue.control;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * "왜 갑자기 이 질문이 나왔는지"를 설명한다.
 *
 * <p>파생 슬롯 구조에서는 답할 때마다 새 질문이 생긴다. 설명이 없으면 사용자는
 * 질문이 끝없이 늘어난다고 느끼고 대화를 포기한다. 무엇을 골랐기 때문에
 * 무엇이 새로 생겼는지 한 줄로 보여 주는 것만으로 체감이 크게 달라진다.
 */
@Component
public class DerivationExplainer {

    public String explain(List<ResolvedSlot> before, List<ResolvedSlot> after,
                          Map<String, Object> justAnswered) {
        Set<String> beforeNames = before.stream().map(ResolvedSlot::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> added = after.stream()
                .map(ResolvedSlot::name)
                .filter(n -> !beforeNames.contains(n))
                .toList();
        if (added.isEmpty()) {
            return null;
        }
        String cause = justAnswered.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        String items = added.size() <= 4 ? String.join(", ", added)
                : String.join(", ", added.subList(0, 4)) + " 외 " + (added.size() - 4) + "개";
        return cause.isEmpty()
                ? items + " 항목이 새로 필요해졌습니다."
                : cause + " 선택으로 " + items + " 항목이 새로 추가되었습니다.";
    }
}
