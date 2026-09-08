package org.hanbat.ses.api.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.api.dto.SesSnapshotNode;
import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;
import org.hanbat.ses.core.model.VarDef;
import org.springframework.stereotype.Component;

/**
 * 현재 pruning 상태를 시각화용 트리로 접는다.
 *
 * <p>선택되지 않은 변형은 싣지 않는다. 아직 안 고른 spec 은 선택지만 보여 주면 되고,
 * 이미 고른 spec 은 고른 쪽만 보여 주면 된다. 전체를 내보내면 트리가 몇 배로 커지면서
 * 정작 "어디까지 정해졌는가"가 묻힌다.
 */
@Component
public class SesSnapshotAssembler {

    public SesSnapshotNode assemble(SesNode root) {
        return build(root);
    }

    private SesSnapshotNode build(SesNode node) {
        return switch (node) {
            case EntityNode e -> {
                Map<String, Object> params = new LinkedHashMap<>();
                List<String> pending = new ArrayList<>();
                for (VarDef v : e.vars()) {
                    if (v.isOpen()) {
                        pending.add(v.name());
                    } else {
                        params.put(v.name(), v.effectiveValue());
                    }
                }
                List<SesSnapshotNode> children = e.axes().stream().map(this::build).toList();
                yield new SesSnapshotNode(e.id(), e.name(), "ENTITY", null,
                        pending, params, children);
            }
            case AspectNode a -> new SesSnapshotNode(a.id(), a.name(), "ASPECT", null,
                    List.of(), Map.of(), a.components().stream().map(this::build).toList());
            case SpecNode s -> {
                if (s.isResolved()) {
                    EntityNode selected = s.selected();
                    yield new SesSnapshotNode(s.id(), s.name(), "SPEC",
                            selected == null ? s.selectedVariantId() : selected.name(),
                            List.of(), Map.of(),
                            selected == null ? List.of() : List.of(build(selected)));
                }
                // 미결정 — 선택지 목록을 pending 으로 보여 준다.
                yield new SesSnapshotNode(s.id(), s.name(), "SPEC", null,
                        s.variants().stream().map(EntityNode::name).toList(),
                        Map.of(), List.of());
            }
            case MultiAspectNode m -> {
                if (m.isResolved()) {
                    yield new SesSnapshotNode(m.id(), m.name(), "MULTI",
                            String.valueOf(m.count()), List.of(), Map.of(),
                            m.instances().stream().map(this::build).toList());
                }
                yield new SesSnapshotNode(m.id(), m.name(), "MULTI", null,
                        List.of("개수 미정 (" + m.countRange().min() + "~"
                                + m.countRange().max() + ")"),
                        Map.of(), List.of());
            }
        };
    }
}
