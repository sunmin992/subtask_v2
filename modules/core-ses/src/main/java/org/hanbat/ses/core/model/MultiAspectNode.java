package org.hanbat.ses.core.model;

import java.util.List;

/**
 * Multi-aspect — 동일 타입 n 개 복제. pruning 지점.
 *
 * <p>count 가 정해지는 순간 instances 가 프로토타입 복제본으로 채워진다.
 * 복제본은 노드 id 에 인스턴스 접미사를 붙여 유일성을 유지하므로
 * 복제본마다 서로 다른 변수 값을 물어보고 채울 수 있다.
 */
public record MultiAspectNode(
        String id,
        String name,
        EntityNode prototype,
        IntRange countRange,
        Integer count,
        List<EntityNode> instances
) implements SesNode {

    public MultiAspectNode {
        instances = instances == null ? List.of() : List.copyOf(instances);
    }

    public static MultiAspectNode open(String id, String name, EntityNode prototype, IntRange countRange) {
        return new MultiAspectNode(id, name, prototype, countRange, null, List.of());
    }

    public boolean isResolved() {
        return count != null;
    }
}
