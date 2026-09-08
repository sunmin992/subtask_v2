package org.hanbat.ses.core.pes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.hanbat.ses.core.fixture.ResortSes;
import org.hanbat.ses.core.model.AxisType;
import org.hanbat.ses.core.model.CouplingSpec;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.prune.PruningEngine;
import org.hanbat.ses.core.validate.SesStructureChecker;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PesBuilderTest {

    private final PesBuilder builder = new PesBuilder();
    private final PruningEngine engine = new PruningEngine();

    @Test
    @DisplayName("확정된 SES 는 리프와 파라미터가 모두 채워진 PES 로 변환된다")
    void buildsPesFromResolvedSes() {
        Pes pes = builder.build(ResortSes.cableCarResolved());

        assertThat(pes.leaves()).extracting(PesNode::name)
                .containsExactlyInAnyOrder("방문객생성기", "집계기", "케이블카", "호텔");

        PesNode cableCar = pes.index().get("ent-cablecar");
        assertThat(cableCar.modelRef()).isEqualTo("cable-car");
        assertThat(cableCar.params()).containsEntry("정원", 8).containsEntry("운행간격", 3.0);

        // 기본값만 있던 변수도 실효값으로 확정된다.
        assertThat(pes.index().get("ent-hotel").params()).containsEntry("평균숙박시간", 600.0);
        assertThat(pes.index().get("ent-arrival").params()).containsEntry("도착간격", 2.0);
    }

    @Test
    @DisplayName("spec 을 가리키는 커플링은 선택된 변형으로 해석된다")
    void couplingToSpecResolvesToSelectedVariant() {
        Pes pes = builder.build(ResortSes.cableCarResolved());

        List<CouplingSpec> couplings = pes.root().couplings();

        assertThat(couplings).anyMatch(c ->
                c.fromEntity().equals("ent-arrival") && c.toEntity().equals("ent-cablecar"));
        assertThat(couplings).anyMatch(c ->
                c.fromEntity().equals("ent-cablecar") && c.toEntity().equals("ent-hotel"));
        assertThat(couplings).noneMatch(c -> c.toEntity().startsWith("spec-"));
    }

    @Test
    @DisplayName("multi-aspect 를 가리키는 커플링은 복제본 전체로 팬아웃된다")
    void couplingToMultiAspectFansOut() {
        SesNode t = engine.apply(ResortSes.tree(), transport(), "셔틀버스");
        t = engine.apply(t, multiBus(), 3);
        t = engine.apply(t, SesAnchor.node("", AxisType.SPECIALIZATION, "spec-lodging"), "호텔");
        for (int i = 0; i < 3; i++) {
            t = engine.apply(t, SesAnchor.variable("", "ent-bus#" + i, "정원"), 20 + i);
        }
        t = engine.apply(t, SesAnchor.variable("", "ent-hotel", "객실수"), 120);

        Pes pes = builder.build(t);
        PesNode shuttle = pes.index().get("ent-shuttle");

        assertThat(shuttle.children()).hasSize(3);
        // EIC 는 셔틀버스 입력에서 버스 3대 각각으로, EOC 는 그 반대로 팬아웃된다.
        assertThat(shuttle.couplings()).hasSize(6);
        assertThat(pes.index().get("ent-bus#2").params()).containsEntry("정원", 22);
    }

    @Test
    @DisplayName("미결정 지점이 남으면 PES 확정을 거부한다")
    void refusesIncompleteSes() {
        assertThatThrownBy(() -> builder.build(ResortSes.tree()))
                .isInstanceOf(IncompleteSesException.class);

        PesBuildResult r = builder.tryBuild(ResortSes.tree());
        assertThat(r.ok()).isFalse();
        assertThat(r.issues()).extracting(ValidationIssue::code)
                .contains("SPEC_UNRESOLVED");
    }

    @Test
    @DisplayName("확정된 트리는 구조 무결성 검사를 통과한다")
    void resolvedTreePassesStructureCheck() {
        List<ValidationIssue> issues = new SesStructureChecker().check(ResortSes.cableCarResolved());

        assertThat(issues).isEmpty();
    }

    private static SesAnchor transport() {
        return SesAnchor.node("리조트/이동설비", AxisType.SPECIALIZATION, "spec-transport");
    }

    private static SesAnchor multiBus() {
        return SesAnchor.node("리조트/이동설비/셔틀버스/버스", AxisType.MULTI_ASPECT, "multi-bus");
    }
}
