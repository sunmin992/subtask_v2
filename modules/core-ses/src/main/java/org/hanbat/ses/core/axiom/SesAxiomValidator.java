package org.hanbat.ses.core.axiom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.validate.ValidationIssue;

/**
 * Zeigler 의 SES 공리 검사.
 *
 * <ol>
 *   <li>교번 모드 — 엔티티 노드와 서술 노드(aspect/spec/multi)가 번갈아 나타난다.</li>
 *   <li>형제 유일성 — 같은 부모 아래 형제는 이름이 겹치지 않는다.</li>
 *   <li>엄격한 계층 — 한 경로 위에 같은 엔티티 이름이 두 번 나오지 않는다.</li>
 *   <li>균일성 — 같은 이름의 엔티티는 어디에 나타나든 같은 구조를 가진다.</li>
 *   <li>변수 유일성 — 한 엔티티 안에서 변수 이름이 겹치지 않는다.</li>
 *   <li>노드 id 유일성 — pruning 앵커가 노드 id 로 매칭하므로 반드시 유일해야 한다.</li>
 * </ol>
 *
 * <p>도메인 SES 를 등록할 때 한 번 돌려서 잘못된 정의가 런타임까지 흘러가지 않게 한다.
 */
public final class SesAxiomValidator {

    public List<ValidationIssue> validate(SesNode root) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (!(root instanceof EntityNode)) {
            issues.add(ValidationIssue.error("<root>", "ROOT_NOT_ENTITY",
                    "SES 루트는 엔티티 노드여야 합니다."));
            return issues;
        }
        Map<String, String> ids = new HashMap<>();
        Map<String, String> shapes = new HashMap<>();
        walk(root, "", new HashSet<>(), ids, shapes, issues);
        return issues;
    }

    private void walk(SesNode node, String path, Set<String> ancestors,
                      Map<String, String> ids, Map<String, String> shapes,
                      List<ValidationIssue> issues) {
        String p = path.isEmpty() ? node.name() : path + "/" + node.name();

        String prev = ids.put(node.id(), p);
        if (prev != null) {
            issues.add(ValidationIssue.error(p, "DUPLICATE_NODE_ID",
                    "노드 id 가 중복됩니다: " + node.id() + " (이미 " + prev + " 에서 사용)"));
        }

        switch (node) {
            case EntityNode e -> {
                if (!ancestors.add(e.name())) {
                    issues.add(ValidationIssue.error(p, "STRICT_HIERARCHY",
                            "같은 엔티티 이름이 경로 위에 다시 나타납니다: " + e.name()));
                }
                checkUniqueNames(p, "변수", e.vars().stream().map(VarDef::name).toList(), issues);
                checkUniqueNames(p, "축", e.axes().stream().map(SesNode::name).toList(), issues);

                String shape = shapeOf(e);
                String known = shapes.putIfAbsent(e.name(), shape);
                if (known != null && !known.equals(shape)) {
                    issues.add(ValidationIssue.warning(p, "UNIFORMITY",
                            "같은 이름의 엔티티 " + e.name() + " 가 서로 다른 구조를 가집니다."));
                }
                // 교번 모드: 엔티티의 자식은 반드시 서술 노드여야 한다.
                for (SesNode axis : e.axes()) {
                    if (axis instanceof EntityNode) {
                        issues.add(ValidationIssue.warning(p, "ALTERNATING_MODE",
                                "엔티티 아래에 엔티티가 직접 왔습니다: " + axis.name()
                                        + ". aspect 로 감싸는 편이 공리에 맞습니다."));
                    }
                    walk(axis, p, new HashSet<>(ancestors), ids, shapes, issues);
                }
            }
            case AspectNode a -> {
                checkUniqueNames(p, "컴포넌트",
                        a.components().stream().map(EntityNode::name).toList(), issues);
                requireEntities(p, a.components(), issues);
                a.components().forEach(c -> walk(c, p, new HashSet<>(ancestors), ids, shapes, issues));
            }
            case SpecNode s -> {
                if (s.variants().size() < 2) {
                    issues.add(ValidationIssue.warning(p, "SPEC_TRIVIAL",
                            "선택지가 " + s.variants().size() + "개뿐인 specialization 입니다."));
                }
                // 변형 이름과 별칭을 한 통에 넣고 유일성을 본다.
                // 두 변형이 같은 별칭을 쓰면 "곤돌라"라는 답이 어느 쪽인지 정할 수 없고,
                // 그때 선택은 변형 목록 순서에 달리게 된다 — 재현성이 깨지는 자리다.
                List<String> selectors = new ArrayList<>();
                for (EntityNode v : s.variants()) {
                    selectors.add(v.name());
                    selectors.addAll(v.aliases());
                }
                checkUniqueNames(p, "변형 이름/별칭", selectors, issues);
                if (s.selectedVariantId() != null && s.selected() == null) {
                    issues.add(ValidationIssue.error(p, "SPEC_SELECTION_DANGLING",
                            "선택된 변형 id 가 변형 목록에 없습니다: " + s.selectedVariantId()));
                }
                s.variants().forEach(v -> walk(v, p, new HashSet<>(ancestors), ids, shapes, issues));
            }
            case MultiAspectNode m -> {
                if (m.countRange() == null) {
                    issues.add(ValidationIssue.error(p, "MULTI_NO_RANGE",
                            "multi-aspect 에 개수 범위가 없습니다. 트리 폭발을 막으려면 상한이 필요합니다."));
                } else if (m.countRange().max() > 1000) {
                    issues.add(ValidationIssue.warning(p, "MULTI_RANGE_LARGE",
                            "multi-aspect 상한이 " + m.countRange().max() + " 로 과도합니다."));
                }
                if (m.isResolved() && m.count() != m.instances().size()) {
                    issues.add(ValidationIssue.error(p, "MULTI_INSTANCE_MISMATCH",
                            "count(" + m.count() + ") 와 실제 복제본 수("
                                    + m.instances().size() + ")가 다릅니다."));
                }
                walk(m.prototype(), p, new HashSet<>(ancestors), ids, shapes, issues);
            }
        }
    }

    private void requireEntities(String path, List<EntityNode> components, List<ValidationIssue> issues) {
        if (components.isEmpty()) {
            issues.add(ValidationIssue.error(path, "ASPECT_EMPTY", "컴포넌트가 없는 aspect 입니다."));
        }
    }

    private void checkUniqueNames(String path, String what, List<String> names,
                                  List<ValidationIssue> issues) {
        Set<String> seen = new HashSet<>();
        for (String n : names) {
            if (!seen.add(normalize(n))) {
                issues.add(ValidationIssue.error(path, "VALID_BROTHERS",
                        what + " 이름이 중복됩니다: " + n));
            }
        }
    }

    /** 공백과 대소문자를 무시해 비교한다 — "cable car" 와 "CableCar" 는 같은 이름이다. */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 균일성 비교용 구조 지문. */
    private String shapeOf(EntityNode e) {
        StringBuilder sb = new StringBuilder();
        e.vars().stream().map(VarDef::name).sorted().forEach(v -> sb.append(v).append(','));
        sb.append('|');
        e.axes().stream().map(SesNode::name).sorted().forEach(a -> sb.append(a).append(','));
        return sb.toString();
    }
}
