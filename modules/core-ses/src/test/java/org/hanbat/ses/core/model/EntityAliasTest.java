package org.hanbat.ses.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.hanbat.ses.core.axiom.SesAxiomValidator;
import org.hanbat.ses.core.fixture.ResortSes;
import org.hanbat.ses.core.prune.PruningEngine;
import org.hanbat.ses.core.prune.PruningException;
import org.hanbat.ses.core.resolve.OpenSlot;
import org.hanbat.ses.core.resolve.SlotOption;
import org.hanbat.ses.core.resolve.SlotResolver;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 엔티티 별칭 — 도메인이 아는 동의어를 도메인이 말해 주는 장치.
 *
 * <p>별칭이 없으면 요청문의 "곤돌라"를 선택지 "케이블카"에 맞추는 일이 LLM 의 추측에 맡겨진다.
 * 그 추측은 값 범위 검사로도 근거 대조로도 잡히지 않는다 — 실제로 한 모델은
 * "8인승 곤돌라"를 셔틀버스 8대로 읽었고, 모든 검증을 통과했다.
 */
class EntityAliasTest {

    private final PruningEngine engine = new PruningEngine();
    private final SlotResolver resolver = new SlotResolver();

    private static SesAnchor transport() {
        return SesAnchor.node("리조트/이동설비", AxisType.SPECIALIZATION, "spec-transport");
    }

    @Test
    @DisplayName("별칭으로 답해도 변형이 해소된다")
    void resolvesByAlias() {
        SesNode byAlias = engine.apply(ResortSes.tree(), transport(), "곤돌라");
        SesNode byName = engine.apply(ResortSes.tree(), transport(), "케이블카");

        assertThat(names(byAlias)).isEqualTo(names(byName));
        assertThat(names(byAlias)).contains("ent-cablecar.정원");
    }

    @Test
    @DisplayName("띄어쓰기와 대소문자를 무시한다")
    void ignoresSpacingAndCase() {
        assertThat(names(engine.apply(ResortSes.tree(), transport(), "리프트")))
                .contains("ent-cablecar.정원");
        assertThat(names(engine.apply(ResortSes.tree(), transport(), " 케 이 블 카 ")))
                .contains("ent-cablecar.정원");
    }

    @Test
    @DisplayName("별칭에도 없는 값은 여전히 거부한다")
    void stillRejectsUnknown() {
        assertThatThrownBy(() -> engine.apply(ResortSes.tree(), transport(), "헬리콥터"))
                .isInstanceOf(PruningException.class)
                .hasMessageContaining("선택지에 없는 값");
    }

    @Test
    @DisplayName("선택지가 별칭을 함께 실어 나른다 — 프롬프트와 질문 힌트가 이걸 쓴다")
    void slotOptionCarriesAliases() {
        OpenSlot slot = resolver.scan(ResortSes.tree()).stream()
                .filter(s -> s.slotName().equals("spec-transport"))
                .findFirst().orElseThrow();

        SlotOption cableCar = slot.options().stream()
                .filter(o -> o.label().equals("케이블카")).findFirst().orElseThrow();

        assertThat(cableCar.aliases()).contains("곤돌라");
        assertThat(cableCar.describe()).isEqualTo("케이블카(=곤돌라, 리프트)");
    }

    @Test
    @DisplayName("별칭이 없는 선택지는 이름만 보여 준다")
    void describesWithoutAliases() {
        assertThat(new SlotOption("ent-x", "모노레일").describe()).isEqualTo("모노레일");
    }

    @Test
    @DisplayName("두 변형이 같은 별칭을 쓰면 공리 위반 — 어느 쪽인지 정할 수 없다")
    void detectsAliasCollision() {
        EntityNode a = EntityNode.leaf("ent-a", "케이블카", "m", List.of(), List.of())
                .withAliases(List.of("곤돌라"));
        EntityNode b = EntityNode.leaf("ent-b", "리프트", "m", List.of(), List.of())
                .withAliases(List.of("곤돌라"));
        SesNode root = EntityNode.of("ent-root", "루트",
                List.of(SpecNode.open("spec", "이동", List.of(a, b))));

        List<ValidationIssue> issues = new SesAxiomValidator().validate(root);

        assertThat(issues).anyMatch(i -> i.code().equals("VALID_BROTHERS")
                && i.message().contains("곤돌라"));
    }

    @Test
    @DisplayName("별칭이 다른 변형의 이름과 겹치는 것도 잡는다")
    void detectsAliasShadowingAnotherName() {
        EntityNode a = EntityNode.leaf("ent-a", "케이블카", "m", List.of(), List.of())
                .withAliases(List.of("모노레일"));
        EntityNode b = EntityNode.leaf("ent-b", "모노레일", "m", List.of(), List.of());
        SesNode root = EntityNode.of("ent-root", "루트",
                List.of(SpecNode.open("spec", "이동", List.of(a, b))));

        assertThat(new SesAxiomValidator().validate(root))
                .anyMatch(i -> i.code().equals("VALID_BROTHERS"));
    }

    @Test
    @DisplayName("별칭을 붙여도 도메인 SES 는 공리를 통과한다")
    void fixtureStillPassesAxioms() {
        assertThat(new SesAxiomValidator().validate(ResortSes.tree()))
                .filteredOn(ValidationIssue::isError).isEmpty();
    }

    private List<String> names(SesNode tree) {
        return resolver.scan(tree).stream().map(OpenSlot::slotName).toList();
    }
}
