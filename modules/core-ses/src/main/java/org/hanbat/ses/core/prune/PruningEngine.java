package org.hanbat.ses.core.prune;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.AxisType;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.validate.ValueCoercion;

/**
 * 답변을 트리에 적용한다.
 *
 * <p>불변 재작성이라 세션 롤백("이전 답변 취소")이 트리 하나 되돌리기로 끝난다.
 * 프로토타입 규모에서 매 턴 트리를 통째로 재작성하는 비용은 무시할 수 있다.
 */
public final class PruningEngine {

    /** 불변 트리를 답변에 따라 재작성하여 새 트리를 반환한다. */
    public SesNode apply(SesNode root, SesAnchor anchor, Object answer) {
        Rewrite r = new Rewrite(anchor, answer);
        SesNode out = r.rewrite(root);
        if (!r.hit) {
            throw new PruningException(
                    "앵커가 가리키는 노드를 찾지 못했습니다: " + anchor.targetNodeId()
                            + (anchor.varName() == null ? "" : "." + anchor.varName()));
        }
        return out;
    }

    public SesNode applyAll(SesNode root, List<AnchoredAnswer> answers) {
        SesNode cur = root;
        for (AnchoredAnswer a : answers) {
            cur = apply(cur, a.anchor(), a.value());
        }
        return cur;
    }

    public record AnchoredAnswer(SesAnchor anchor, Object value) {
    }

    private static final class Rewrite {
        private final SesAnchor anchor;
        private final Object answer;
        private boolean hit;

        Rewrite(SesAnchor anchor, Object answer) {
            this.anchor = anchor;
            this.answer = answer;
        }

        SesNode rewrite(SesNode node) {
            return switch (node) {
                case SpecNode s when matches(s.id()) && anchor.axis() == AxisType.SPECIALIZATION -> {
                    hit = true;
                    yield new SpecNode(s.id(), s.name(), s.variants(), resolveVariantId(s, answer));
                }
                case MultiAspectNode m when matches(m.id()) && anchor.axis() == AxisType.MULTI_ASPECT -> {
                    hit = true;
                    yield resolveCount(m, answer);
                }
                case EntityNode e when matches(e.id()) && anchor.axis() == AxisType.VARIABLE -> {
                    hit = true;
                    yield withVar(e);
                }
                // 대상이 아니면 자식만 재귀 재작성한다.
                case EntityNode e -> new EntityNode(e.id(), e.name(), e.vars(),
                        e.axes().stream().map(this::rewrite).toList(),
                        e.modelRef(), e.ports(), e.couplings(), e.aliases());
                case AspectNode ap -> new AspectNode(ap.id(), ap.name(),
                        ap.components().stream().map(c -> (EntityNode) rewrite(c)).toList(),
                        ap.couplings());
                case SpecNode s -> new SpecNode(s.id(), s.name(),
                        s.variants().stream().map(v -> (EntityNode) rewrite(v)).toList(),
                        s.selectedVariantId());
                case MultiAspectNode m -> new MultiAspectNode(m.id(), m.name(),
                        (EntityNode) rewrite(m.prototype()), m.countRange(), m.count(),
                        m.instances().stream().map(i -> (EntityNode) rewrite(i)).toList());
            };
        }

        private boolean matches(String nodeId) {
            return nodeId.equals(anchor.targetNodeId());
        }

        private EntityNode withVar(EntityNode e) {
            String varName = anchor.varName();
            VarDef target = e.var(varName);
            if (target == null) {
                throw new PruningException(
                        "엔티티 " + e.name() + " 에 변수 " + varName + " 이(가) 없습니다.");
            }
            Object normalized = ValueCoercion.coerce(answer, target.type())
                    .orElseThrow(() -> new PruningException(
                            varName + " 값을 " + target.type() + " 로 해석할 수 없습니다: " + answer));
            List<VarDef> vars = new ArrayList<>(e.vars().size());
            for (VarDef v : e.vars()) {
                vars.add(v.name().equals(varName) ? v.withValue(normalized) : v);
            }
            return e.withVars(vars);
        }

        private String resolveVariantId(SpecNode s, Object ans) {
            if (ans == null) {
                return null;
            }
            String token = ans.toString().trim();
            // id, 이름, 별칭을 모두 본다 — "곤돌라"로 답해도 케이블카로 해소된다.
            Optional<EntityNode> matched = s.variants().stream()
                    .filter(v -> v.matchesName(token)).findFirst();
            if (matched.isPresent()) {
                return matched.get().id();
            }
            throw new PruningException(
                    s.name() + " 의 선택지에 없는 값입니다: " + token
                            + " (가능: " + s.variants().stream().map(EntityNode::name).toList() + ")");
        }

        /**
         * 개수를 확정하고 프로토타입 복제본을 만든다.
         * 이미 값을 채워 둔 복제본은 개수가 늘어도 그대로 살려 둔다 — 사용자가 다시 답하게 만들지 않는다.
         */
        private MultiAspectNode resolveCount(MultiAspectNode m, Object ans) {
            int n = ValueCoercion.coerce(ans, org.hanbat.ses.core.model.VarType.INT)
                    .map(v -> (Integer) v)
                    .orElseThrow(() -> new PruningException(
                            m.name() + " 개수를 정수로 해석할 수 없습니다: " + ans));
            if (m.countRange() != null && !m.countRange().contains(n)) {
                throw new PruningException(
                        m.name() + " 개수는 " + m.countRange().min() + "~" + m.countRange().max()
                                + " 사이여야 합니다. 입력값: " + n);
            }
            List<EntityNode> instances = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (i < m.instances().size()) {
                    instances.add(m.instances().get(i));
                } else {
                    instances.add(NodeCloner.instantiate(m.prototype(), i));
                }
            }
            return new MultiAspectNode(m.id(), m.name(), m.prototype(),
                    m.countRange(), n, instances);
        }
    }
}
