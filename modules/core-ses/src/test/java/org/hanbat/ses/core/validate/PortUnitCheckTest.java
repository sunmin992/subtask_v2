package org.hanbat.ses.core.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.CouplingKind;
import org.hanbat.ses.core.model.CouplingSpec;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.PortDef;
import org.hanbat.ses.core.model.SesNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-403 — 이어진 포트의 단위 호환성.
 *
 * <p>이 검사가 잡는 오류는 실행해도 예외가 나지 않는다. 숫자는 그대로 흐르고 결과만
 * 조용히 무의미해진다. 포트가 없는 배선(NPE)보다 오히려 찾기 어렵다.
 */
class PortUnitCheckTest {

    /** 생산자 -> 소비자 두 컴포넌트를 잇고 각 포트의 단위만 바꿔 시험한다. */
    private static SesNode wire(String outUnit, String inUnit) {
        EntityNode producer = EntityNode.leaf("ent-p", "생산자", "m", List.of(),
                List.of(PortDef.out("out", outUnit)));
        EntityNode consumer = EntityNode.leaf("ent-c", "소비자", "m", List.of(),
                List.of(PortDef.in("in", inUnit)));

        return new EntityNode("ent-root", "루트", List.of(),
                List.of(AspectNode.of("asp", "구성", List.of(producer, consumer))),
                null, List.of(),
                List.of(new CouplingSpec(CouplingKind.IC, "ent-p", "out", "ent-c", "in")),
                List.of());
    }

    private static List<String> codes(SesNode tree) {
        return new SesStructureChecker().check(tree).stream()
                .map(ValidationIssue::code).toList();
    }

    @Test
    @DisplayName("같은 단위는 통과한다")
    void samePassesThrough() {
        assertThat(codes(wire("명", "명"))).isEmpty();
    }

    @Test
    @DisplayName("차원이 다른 단위는 오류 — 인원을 시간에 흘려보낼 수는 없다")
    void rejectsDifferentDimension() {
        List<ValidationIssue> issues = new SesStructureChecker().check(wire("명", "분"));

        assertThat(issues).extracting(ValidationIssue::code).contains("PORT_UNIT_MISMATCH");
        assertThat(issues).anyMatch(i -> i.message().contains("생산자.out(명)")
                && i.message().contains("소비자.in(분)"));
    }

    @Test
    @DisplayName("같은 것을 다르게 적은 단위는 통과한다 — 명 과 인")
    void acceptsSynonyms() {
        assertThat(codes(wire("명", "인"))).isEmpty();
        assertThat(codes(wire("분", "min"))).isEmpty();
    }

    @Test
    @DisplayName("도메인이 선언한 별칭을 적용한다")
    void appliesDomainAliases() {
        // 도메인이 "pax" 를 "명" 으로 쓰기로 했다면 그 선언을 따른다.
        SesStructureChecker checker = new SesStructureChecker(Map.of("pax", "명"));

        assertThat(checker.check(wire("pax", "명"))).isEmpty();
        assertThat(checker.check(wire("pax", "분")))
                .extracting(ValidationIssue::code).contains("PORT_UNIT_MISMATCH");
    }

    @Test
    @DisplayName("단위를 밝히지 않은 포트는 대조하지 않는다")
    void skipsWhenUnitAbsent() {
        assertThat(codes(wire(null, "분"))).isEmpty();
        assertThat(codes(wire("명", null))).isEmpty();
        assertThat(codes(wire(null, null))).isEmpty();
    }

    @Test
    @DisplayName("표에 없는 단위는 이름이 같을 때만 통과한다")
    void unknownUnitsMatchByNameOnly() {
        assertThat(codes(wire("팔레트", "팔레트"))).isEmpty();
        assertThat(codes(wire("팔레트", "박스")))
                .contains("PORT_UNIT_MISMATCH");
    }

    @Test
    @DisplayName("단위 표는 차원을 구분한다")
    void unitTableSeparatesDimensions() {
        assertThat(UnitTable.dimensionOf("분", Map.of())).isEqualTo(UnitTable.Dimension.TIME);
        assertThat(UnitTable.dimensionOf("명", Map.of())).isEqualTo(UnitTable.Dimension.COUNT);
        assertThat(UnitTable.dimensionOf("팔레트", Map.of())).isEqualTo(UnitTable.Dimension.UNKNOWN);
        assertThat(UnitTable.minutesPerUnit("시간", Map.of())).contains(60.0);
        assertThat(UnitTable.minutesPerUnit("명", Map.of())).isEmpty();
    }
}
