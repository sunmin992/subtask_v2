package org.hanbat.ses.core.validate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.CouplingSpec;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.PortDef;
import org.hanbat.ses.core.model.PortDirection;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;

/**
 * 구조 무결성 — 고아 포트와 배선 오류를 잡는다.
 *
 * <p>이 검사를 건너뛰면 오류가 시뮬레이터 실행 시점의 정체불명 NPE 로 나타난다.
 * 커플링의 양 끝 포트가 실제로 존재하고 방향이 맞는지 확정 전에 확인한다.
 *
 * <p>포트가 단위를 밝혔다면 양 끝의 단위도 대조한다(FR-403). 단위가 어긋난 배선은
 * 실행해도 오류가 나지 않는다 — 숫자는 그대로 흐르고 결과만 조용히 무의미해진다.
 * 그래서 포트 존재 여부보다 오히려 찾기 어렵다.
 */
public final class SesStructureChecker {

    private final Map<String, String> unitAliases;

    public SesStructureChecker() {
        this(Map.of());
    }

    /** @param unitAliases 도메인이 선언한 단위 별칭. "min" 을 "분"으로 접는 데 쓴다. */
    public SesStructureChecker(Map<String, String> unitAliases) {
        this.unitAliases = unitAliases == null ? Map.of() : unitAliases;
    }

    public List<ValidationIssue> check(SesNode root) {
        List<ValidationIssue> issues = new ArrayList<>();
        walk(root, root instanceof EntityNode e ? e.name() : "<root>", issues);
        return issues;
    }

    private void walk(SesNode node, String path, List<ValidationIssue> issues) {
        switch (node) {
            case EntityNode e -> {
                Map<String, List<EntityNode>> endpoints = endpointsOf(e);
                for (CouplingSpec c : e.couplings()) {
                    checkCoupling(e, c, endpoints, path, issues);
                }
                for (SesNode axis : e.axes()) {
                    if (axis instanceof AspectNode a) {
                        for (CouplingSpec c : a.couplings()) {
                            checkCoupling(e, c, endpoints, path + "/" + a.name(), issues);
                        }
                    }
                    walk(axis, path, issues);
                }
            }
            case AspectNode a -> a.components().forEach(c -> walk(c, path + "/" + c.name(), issues));
            case SpecNode s -> {
                if (s.selected() != null) {
                    walk(s.selected(), path + "/" + s.name(), issues);
                }
            }
            case MultiAspectNode m -> {
                for (int i = 0; i < m.instances().size(); i++) {
                    walk(m.instances().get(i), path + "/" + m.name() + "[" + i + "]", issues);
                }
            }
        }
    }

    /** 커플링에서 참조 가능한 논리 id -> 실제 엔티티 목록. */
    private Map<String, List<EntityNode>> endpointsOf(EntityNode owner) {
        Map<String, List<EntityNode>> map = new LinkedHashMap<>();
        map.put(owner.id(), List.of(owner));
        for (SesNode axis : owner.axes()) {
            switch (axis) {
                case AspectNode a -> a.components()
                        .forEach(c -> map.computeIfAbsent(c.id(), k -> new ArrayList<>()).add(c));
                case SpecNode s -> {
                    EntityNode sel = s.selected();
                    if (sel != null) {
                        map.computeIfAbsent(s.id(), k -> new ArrayList<>()).add(sel);
                        map.computeIfAbsent(sel.id(), k -> new ArrayList<>()).add(sel);
                    }
                }
                case MultiAspectNode m -> {
                    if (m.isResolved()) {
                        map.put(m.id(), new ArrayList<>(m.instances()));
                        m.instances()
                                .forEach(i -> map.computeIfAbsent(i.id(), k -> new ArrayList<>()).add(i));
                    }
                }
                case EntityNode nested ->
                        map.computeIfAbsent(nested.id(), k -> new ArrayList<>()).add(nested);
            }
        }
        return map;
    }

    private void checkCoupling(EntityNode owner, CouplingSpec c,
                               Map<String, List<EntityNode>> endpoints,
                               String path, List<ValidationIssue> issues) {
        List<EntityNode> from = endpoints.get(c.fromEntity());
        List<EntityNode> to = endpoints.get(c.toEntity());
        // 아직 결정되지 않은 축을 가리키는 배선은 오류가 아니다 — 선택이 끝나면 실체가 생긴다.
        if (isPending(owner, c.fromEntity()) || isPending(owner, c.toEntity())) {
            return;
        }
        if (from == null) {
            issues.add(ValidationIssue.error(path, "COUPLING_FROM_MISSING",
                    "커플링 출발 엔티티를 찾을 수 없습니다: " + c.fromEntity()));
            return;
        }
        if (to == null) {
            issues.add(ValidationIssue.error(path, "COUPLING_TO_MISSING",
                    "커플링 도착 엔티티를 찾을 수 없습니다: " + c.toEntity()));
            return;
        }
        // EIC 는 소유자의 입력을, EOC 는 소유자의 출력을 향한다.
        PortDirection fromDir = switch (c.kind()) {
            case EIC -> PortDirection.IN;
            case IC, EOC -> PortDirection.OUT;
        };
        PortDirection toDir = switch (c.kind()) {
            case EOC -> PortDirection.OUT;
            case IC, EIC -> PortDirection.IN;
        };
        for (EntityNode f : from) {
            requirePort(f, c.fromPort(), fromDir, path, c, issues);
        }
        for (EntityNode t : to) {
            requirePort(t, c.toPort(), toDir, path, c, issues);
        }
        for (EntityNode f : from) {
            for (EntityNode t : to) {
                requireUnitMatch(f, c.fromPort(), t, c.toPort(), path, issues);
            }
        }
        if (c.kind() == org.hanbat.ses.core.model.CouplingKind.EIC && !c.fromEntity().equals(owner.id())) {
            issues.add(ValidationIssue.warning(path, "EIC_SOURCE_NOT_OWNER",
                    "EIC 의 출발점은 소유 엔티티여야 자연스럽습니다: " + c.fromEntity()));
        }
    }

    /** 미해결 spec/multi 축을 가리키는 참조인가. */
    private boolean isPending(EntityNode owner, String id) {
        for (SesNode axis : owner.axes()) {
            if (!axis.id().equals(id)) {
                continue;
            }
            return switch (axis) {
                case SpecNode s -> !s.isResolved();
                case MultiAspectNode m -> !m.isResolved();
                default -> false;
            };
        }
        return false;
    }

    /**
     * 이어진 두 포트의 단위가 호환되는지 본다.
     *
     * <p>어느 한쪽이라도 단위를 밝히지 않았으면 넘어간다 — 도메인이 말하지 않은 것을
     * 근거로 오류를 내면, 단위를 적지 않은 멀쩡한 도메인이 등록조차 되지 않는다.
     */
    private void requireUnitMatch(EntityNode fromEntity, String fromPort,
                                  EntityNode toEntity, String toPort,
                                  String path, List<ValidationIssue> issues) {
        PortDef out = portOf(fromEntity, fromPort);
        PortDef in = portOf(toEntity, toPort);
        if (out == null || in == null || !out.hasUnit() || !in.hasUnit()) {
            return;
        }
        if (!UnitTable.compatible(out.unit(), in.unit(), unitAliases)) {
            issues.add(ValidationIssue.error(path, "PORT_UNIT_MISMATCH",
                    "%s.%s(%s) -> %s.%s(%s) 배선의 단위가 맞지 않습니다. 실행은 되지만 결과가 무의미해집니다."
                            .formatted(fromEntity.name(), fromPort, out.unit(),
                                    toEntity.name(), toPort, in.unit())));
        }
    }

    private PortDef portOf(EntityNode e, String name) {
        return e.ports().stream()
                .filter(p -> p.name().equals(name))
                .findFirst().orElse(null);
    }

    private void requirePort(EntityNode e, String port, PortDirection dir,
                             String path, CouplingSpec c, List<ValidationIssue> issues) {
        // 포트를 선언하지 않은 엔티티는 검사 대상에서 제외한다 (중간 계층 엔티티).
        if (e.ports().isEmpty()) {
            return;
        }
        if (!e.hasPort(port, dir)) {
            issues.add(ValidationIssue.error(path, "PORT_MISSING",
                    e.name() + " 에 " + dir + " 포트 '" + port + "' 이(가) 없습니다. (커플링 "
                            + c.fromEntity() + "." + c.fromPort() + " -> "
                            + c.toEntity() + "." + c.toPort() + ")"));
        }
    }
}
