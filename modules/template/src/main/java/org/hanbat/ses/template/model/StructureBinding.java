package org.hanbat.ses.template.model;

import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.CouplingSpec;

/**
 * 구조 바인딩 — 이 템플릿이 어떤 도메인 SES 위에서 도는지.
 *
 * @param pruningRules  "슬롯=값" -> 선택할 노드 id. SpecNode 변형 이름과 사용자가 쓰는
 *                      말이 다를 때만 필요하다. 비어 있으면 이름/ id 직접 매칭에 맡긴다.
 * @param extraCouplings 도메인 SES 에는 없고 이 서브태스크에서만 추가되는 배선.
 */
public record StructureBinding(
        String sesDefinitionId,
        String rootEntity,
        Map<String, String> pruningRules,
        List<CouplingSpec> extraCouplings
) {

    public StructureBinding {
        pruningRules = pruningRules == null ? Map.of() : Map.copyOf(pruningRules);
        extraCouplings = extraCouplings == null ? List.of() : List.copyOf(extraCouplings);
    }
}
