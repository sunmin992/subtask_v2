package org.hanbat.ses.core.pes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.CouplingSpec;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.validate.ValidationIssue;

/**
 * pruning 이 끝난 SES 를 PES 로 확정한다.
 *
 * <p>커플링 엔드포인트는 세 가지를 모두 받아들인다.
 * <ul>
 *   <li>자식 엔티티 id — 그대로 연결</li>
 *   <li>SpecNode id — 선택된 변형으로 해석</li>
 *   <li>MultiAspectNode id — 복제본 전체로 팬아웃</li>
 * </ul>
 * 이렇게 하지 않으면 "케이블카를 3대로 늘렸더니 배선이 1대에만 남아 있는" 상황이 생긴다.
 */
public final class PesBuilder {

    public Pes build(SesNode root) {
        PesBuildResult r = tryBuild(root);
        if (!r.ok()) {
            throw new IncompleteSesException(r.issues());
        }
        return r.pes();
    }

    /** 검증 단계용 — 예외 대신 issue 목록을 돌려준다. */
    public PesBuildResult tryBuild(SesNode root) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (!(root instanceof EntityNode e)) {
            issues.add(ValidationIssue.error("<root>", "ROOT_NOT_ENTITY",
                    "SES 루트는 엔티티여야 합니다."));
            return new PesBuildResult(null, issues);
        }
        PesNode node = entity(e, e.name(), issues);
        boolean fatal = issues.stream().anyMatch(ValidationIssue::isError);
        return new PesBuildResult(fatal ? null : new Pes(node), issues);
    }

    private PesNode entity(EntityNode e, String path, List<ValidationIssue> issues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (VarDef v : e.vars()) {
            Object val = v.effectiveValue();
            if (val == null) {
                issues.add(ValidationIssue.error(path + "." + v.name(), "VAR_UNFILLED",
                        path + " 의 " + v.name() + " 값이 정해지지 않았습니다."));
            } else {
                params.put(v.name(), val);
            }
        }

        List<PesNode> children = new ArrayList<>();
        List<CouplingSpec> declared = new ArrayList<>(e.couplings());
        // 커플링 엔드포인트 해석표: 논리 id -> 실제 자식 엔티티 id 목록
        Map<String, List<String>> endpoints = new LinkedHashMap<>();

        for (SesNode axis : e.axes()) {
            switch (axis) {
                case AspectNode a -> {
                    for (EntityNode c : a.components()) {
                        children.add(entity(c, path + "/" + c.name(), issues));
                        endpoints.computeIfAbsent(c.id(), k -> new ArrayList<>()).add(c.id());
                    }
                    declared.addAll(a.couplings());
                }
                case SpecNode s -> {
                    EntityNode sel = s.selected();
                    if (sel == null) {
                        issues.add(ValidationIssue.error(path + "/" + s.name(), "SPEC_UNRESOLVED",
                                s.name() + " 선택이 아직 끝나지 않았습니다."));
                    } else {
                        children.add(entity(sel, path + "/" + s.name() + "/" + sel.name(), issues));
                        endpoints.computeIfAbsent(s.id(), k -> new ArrayList<>()).add(sel.id());
                        endpoints.computeIfAbsent(sel.id(), k -> new ArrayList<>()).add(sel.id());
                    }
                }
                case MultiAspectNode m -> {
                    if (!m.isResolved()) {
                        issues.add(ValidationIssue.error(path + "/" + m.name(), "MULTI_UNRESOLVED",
                                m.name() + " 개수가 아직 정해지지 않았습니다."));
                    } else {
                        List<String> ids = new ArrayList<>();
                        List<EntityNode> instances = m.instances();
                        for (int i = 0; i < instances.size(); i++) {
                            EntityNode inst = instances.get(i);
                            children.add(entity(inst, path + "/" + m.name() + "[" + i + "]", issues));
                            ids.add(inst.id());
                            endpoints.computeIfAbsent(inst.id(), k -> new ArrayList<>()).add(inst.id());
                        }
                        endpoints.put(m.id(), ids);
                    }
                }
                case EntityNode nested -> {
                    // 축 자리에 엔티티가 직접 온 경우도 허용한다 (단일 컴포넌트 aspect 축약형).
                    children.add(entity(nested, path + "/" + nested.name(), issues));
                    endpoints.computeIfAbsent(nested.id(), k -> new ArrayList<>()).add(nested.id());
                }
            }
        }
        endpoints.put(e.id(), List.of(e.id()));

        List<CouplingSpec> resolved = resolveCouplings(declared, endpoints, path, issues);
        return new PesNode(e.id(), e.name(), e.modelRef(), params, children, resolved);
    }

    private List<CouplingSpec> resolveCouplings(List<CouplingSpec> declared,
                                                Map<String, List<String>> endpoints,
                                                String path,
                                                List<ValidationIssue> issues) {
        List<CouplingSpec> out = new ArrayList<>();
        for (CouplingSpec c : declared) {
            List<String> from = endpoints.get(c.fromEntity());
            List<String> to = endpoints.get(c.toEntity());
            if (from == null || to == null) {
                issues.add(ValidationIssue.error(path, "COUPLING_DANGLING",
                        "커플링 " + c.fromEntity() + "." + c.fromPort() + " -> "
                                + c.toEntity() + "." + c.toPort()
                                + " 의 끝점을 찾을 수 없습니다."));
                continue;
            }
            for (String f : from) {
                for (String t : to) {
                    out.add(new CouplingSpec(c.kind(), f, c.fromPort(), t, c.toPort()));
                }
            }
        }
        return out;
    }
}
