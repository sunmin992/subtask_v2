package org.hanbat.ses.core.pes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.CouplingSpec;

/**
 * PES(Pruned Entity Structure) 노드 — 모든 선택이 끝난 확정 구조.
 *
 * @param modelRef 리프에만 존재하는 원자 모델 참조.
 * @param params   확정된 파라미터. VarDef 의 실효값을 이름으로 담는다.
 */
public record PesNode(
        String entityId,
        String name,
        String modelRef,
        Map<String, Object> params,
        List<PesNode> children,
        List<CouplingSpec> couplings
) {

    public PesNode {
        // Map.copyOf 는 순회 순서를 보장하지 않는다. PES 를 JSON 으로 비교해
        // 재현성을 확인하는 테스트가 있으므로 삽입 순서를 유지해야 한다.
        params = params == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        children = children == null ? List.of() : List.copyOf(children);
        couplings = couplings == null ? List.of() : List.copyOf(couplings);
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }
}
