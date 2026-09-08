package org.hanbat.ses.core.resolve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.AxisType;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;
import org.hanbat.ses.core.model.VarDef;

/**
 * SES 트리를 순회하며 미결정 지점을 수집한다. 이 시스템에서 가장 중요한 클래스다.
 *
 * <p>핵심은 <b>미해결 SpecNode 의 하위를 순회하지 않는 것</b>이다.
 * 케이블카를 고르기 전에는 케이블카의 정원 변수가 질문 목록에 들어가지 않는다.
 * 답변이 들어와 pruning 되면 다음 scan() 에서 자연스럽게 나타난다.
 * 이것이 "파생 슬롯"의 구현 전부다 — 별도의 의존성 그래프를 손으로 유지하지 않는다.
 */
public final class SlotResolver {

    public List<OpenSlot> scan(SesNode root) {
        List<OpenSlot> out = new ArrayList<>();
        walk(root, "", 0, out);
        // 얕은 것부터: 구조 결정이 값 설정보다 먼저 온다.
        out.sort(Comparator.comparingInt(OpenSlot::depth)
                .thenComparing(s -> s.kind().isStructural() ? 0 : 1)
                .thenComparing(OpenSlot::slotName));
        return List.copyOf(out);
    }

    private void walk(SesNode node, String path, int depth, List<OpenSlot> out) {
        switch (node) {
            case EntityNode e -> {
                String p = join(path, e.name());
                for (VarDef v : e.vars()) {
                    if (v.isOpen()) {
                        out.add(OpenSlot.value(p, SesAnchor.variable(p, e.id(), v.name()), v, depth));
                    }
                }
                for (SesNode axis : e.axes()) {
                    walk(axis, p, depth + 1, out);
                }
            }
            case SpecNode s -> {
                String p = join(path, s.name());
                if (!s.isResolved()) {
                    // 미해결 → 구조 슬롯. 하위는 아직 순회하지 않는다.
                    out.add(OpenSlot.select(p,
                            SesAnchor.node(p, AxisType.SPECIALIZATION, s.id()),
                            s.variants().stream()
                                    .map(v -> new SlotOption(v.id(), v.name(), v.aliases()))
                                    .toList(),
                            depth));
                } else {
                    EntityNode selected = s.selected();
                    if (selected != null) {
                        walk(selected, p, depth + 1, out);
                    }
                }
            }
            case MultiAspectNode m -> {
                String p = join(path, m.name());
                if (!m.isResolved()) {
                    out.add(OpenSlot.multiplicity(p,
                            SesAnchor.node(p, AxisType.MULTI_ASPECT, m.id()),
                            m.countRange(), depth));
                } else {
                    List<EntityNode> instances = m.instances();
                    for (int i = 0; i < instances.size(); i++) {
                        walk(instances.get(i), p + "[" + i + "]", depth + 1, out);
                    }
                }
            }
            case AspectNode a -> {
                String p = join(path, a.name());
                for (EntityNode c : a.components()) {
                    walk(c, p, depth + 1, out);
                }
            }
        }
    }

    private static String join(String path, String name) {
        return path.isEmpty() ? name : path + "/" + name;
    }
}
