package org.hanbat.ses.core.model;

import java.util.List;

/** Aspect — AND 분해. 모든 컴포넌트가 함께 존재하므로 미결정 지점이 아니다. */
public record AspectNode(
        String id,
        String name,
        List<EntityNode> components,
        List<CouplingSpec> couplings
) implements SesNode {

    public AspectNode {
        components = components == null ? List.of() : List.copyOf(components);
        couplings = couplings == null ? List.of() : List.copyOf(couplings);
    }

    public static AspectNode of(String id, String name, List<EntityNode> components) {
        return new AspectNode(id, name, components, List.of());
    }
}
