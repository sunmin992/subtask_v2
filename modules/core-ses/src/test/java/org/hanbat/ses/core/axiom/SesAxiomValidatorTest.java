package org.hanbat.ses.core.axiom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hanbat.ses.core.fixture.ResortSes;
import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.IntRange;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.Range;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.model.VarType;
import org.hanbat.ses.core.validate.Severity;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SesAxiomValidatorTest {

    private final SesAxiomValidator validator = new SesAxiomValidator();

    @Test
    @DisplayName("정상 도메인 SES 는 오류 없이 통과한다")
    void validTreePasses() {
        List<ValidationIssue> issues = validator.validate(ResortSes.tree());

        assertThat(issues).filteredOn(ValidationIssue::isError).isEmpty();
    }

    @Test
    @DisplayName("형제 이름이 겹치면 잡아낸다")
    void detectsDuplicateSiblings() {
        EntityNode a = EntityNode.leaf("ent-a", "같은이름", "m", List.of(), List.of());
        EntityNode b = EntityNode.leaf("ent-b", "같은이름", "m", List.of(), List.of());
        SesNode root = EntityNode.of("ent-root", "루트",
                List.of(AspectNode.of("asp", "구성", List.of(a, b))));

        assertThat(codes(validator.validate(root))).contains("VALID_BROTHERS");
    }

    @Test
    @DisplayName("노드 id 중복은 오류다 — pruning 앵커가 id 로 매칭하기 때문")
    void detectsDuplicateNodeIds() {
        EntityNode a = EntityNode.leaf("ent-dup", "가", "m", List.of(), List.of());
        EntityNode b = EntityNode.leaf("ent-dup", "나", "m", List.of(), List.of());
        SesNode root = EntityNode.of("ent-root", "루트",
                List.of(AspectNode.of("asp", "구성", List.of(a, b))));

        assertThat(codes(validator.validate(root))).contains("DUPLICATE_NODE_ID");
    }

    @Test
    @DisplayName("변수 이름 중복은 오류다")
    void detectsDuplicateVars() {
        EntityNode leaf = EntityNode.leaf("ent-x", "엑스", "m",
                List.of(VarDef.of("정원", VarType.INT, "명", Range.none()),
                        VarDef.of("정원", VarType.INT, "명", Range.none())),
                List.of());
        SesNode root = EntityNode.of("ent-root", "루트",
                List.of(AspectNode.of("asp", "구성", List.of(leaf))));

        assertThat(codes(validator.validate(root))).contains("VALID_BROTHERS");
    }

    @Test
    @DisplayName("multi-aspect 에 개수 상한이 없으면 오류 — 트리 폭발 방지")
    void requiresMultiplicityRange() {
        EntityNode proto = EntityNode.leaf("ent-p", "프로토", "m", List.of(), List.of());
        MultiAspectNode multi = new MultiAspectNode("multi", "여럿", proto, null, null, List.of());
        SesNode root = EntityNode.of("ent-root", "루트", List.of(multi));

        assertThat(codes(validator.validate(root))).contains("MULTI_NO_RANGE");
    }

    @Test
    @DisplayName("같은 엔티티 이름이 경로 위에 반복되면 계층 공리 위반")
    void detectsStrictHierarchyViolation() {
        EntityNode inner = EntityNode.of("ent-inner", "루트",
                List.of(AspectNode.of("asp2", "속",
                        List.of(EntityNode.leaf("ent-leaf", "잎", "m", List.of(), List.of())))));
        SesNode root = EntityNode.of("ent-root", "루트",
                List.of(AspectNode.of("asp1", "겉", List.of(inner))));

        assertThat(codes(validator.validate(root))).contains("STRICT_HIERARCHY");
    }

    @Test
    @DisplayName("선택지가 하나뿐인 specialization 은 경고한다")
    void warnsOnTrivialSpec() {
        SpecNode spec = SpecNode.open("spec", "선택",
                List.of(EntityNode.leaf("ent-only", "유일", "m", List.of(), List.of())));
        SesNode root = EntityNode.of("ent-root", "루트", List.of(spec));

        List<ValidationIssue> issues = validator.validate(root);
        assertThat(issues).anyMatch(i -> i.code().equals("SPEC_TRIVIAL")
                && i.severity() == Severity.WARNING);
    }

    @Test
    @DisplayName("count 와 복제본 수가 어긋나면 오류")
    void detectsInstanceMismatch() {
        EntityNode proto = EntityNode.leaf("ent-p", "프로토", "m", List.of(), List.of());
        MultiAspectNode multi = new MultiAspectNode("multi", "여럿", proto,
                new IntRange(1, 5), 3, List.of());
        SesNode root = EntityNode.of("ent-root", "루트", List.of(multi));

        assertThat(codes(validator.validate(root))).contains("MULTI_INSTANCE_MISMATCH");
    }

    private static List<String> codes(List<ValidationIssue> issues) {
        return issues.stream().map(ValidationIssue::code).toList();
    }
}
