package org.hanbat.ses.core.prune;

import java.util.List;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.CouplingSpec;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;

/**
 * multi-aspect 복제본 생성기.
 *
 * <p>서브트리 전체의 노드 id 에 인스턴스 접미사를 붙여 유일성을 보장한다.
 * 접미사가 없으면 복제본 3개가 같은 id 를 갖게 되어 "2번 케이블카의 정원"을
 * 따로 물어볼 방법이 사라진다. 커플링의 양 끝 엔티티 id 도 같이 고쳐야
 * 복제본 내부 배선이 다른 복제본으로 새지 않는다.
 */
public final class NodeCloner {

    private NodeCloner() {
    }

    public static EntityNode instantiate(EntityNode prototype, int index) {
        return (EntityNode) reid(prototype, SesAnchor.INSTANCE_SEPARATOR + String.valueOf(index));
    }

    private static SesNode reid(SesNode node, String suffix) {
        return switch (node) {
            case EntityNode e -> new EntityNode(
                    e.id() + suffix, e.name(), e.vars(),
                    e.axes().stream().map(a -> reid(a, suffix)).toList(),
                    e.modelRef(), e.ports(),
                    e.couplings().stream().map(c -> reidCoupling(c, suffix)).toList(),
                    e.aliases());
            case AspectNode a -> new AspectNode(
                    a.id() + suffix, a.name(),
                    a.components().stream().map(c -> (EntityNode) reid(c, suffix)).toList(),
                    a.couplings().stream().map(c -> reidCoupling(c, suffix)).toList());
            case SpecNode s -> new SpecNode(
                    s.id() + suffix, s.name(),
                    s.variants().stream().map(v -> (EntityNode) reid(v, suffix)).toList(),
                    s.selectedVariantId() == null ? null : s.selectedVariantId() + suffix);
            case MultiAspectNode m -> new MultiAspectNode(
                    m.id() + suffix, m.name(),
                    (EntityNode) reid(m.prototype(), suffix),
                    m.countRange(), m.count(),
                    m.instances().stream().map(i -> (EntityNode) reid(i, suffix)).toList());
        };
    }

    private static CouplingSpec reidCoupling(CouplingSpec c, String suffix) {
        return new CouplingSpec(c.kind(),
                c.fromEntity() + suffix, c.fromPort(),
                c.toEntity() + suffix, c.toPort());
    }
}
