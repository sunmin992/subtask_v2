package org.hanbat.ses.core.prune;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.hanbat.ses.core.fixture.ResortSes;
import org.hanbat.ses.core.model.AxisType;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.resolve.OpenSlot;
import org.hanbat.ses.core.resolve.SlotResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PruningEngineTest {

    private final PruningEngine engine = new PruningEngine();
    private final SlotResolver resolver = new SlotResolver();

    @Test
    @DisplayName("원본 트리는 변경되지 않는다 — 롤백이 트리 하나 되돌리기로 끝나는 근거")
    void applyIsImmutable() {
        SesNode original = ResortSes.tree();
        int before = resolver.scan(original).size();

        engine.apply(original, transport(), "케이블카");

        assertThat(resolver.scan(original)).hasSize(before);
    }

    @Test
    @DisplayName("변형 이름으로도 id 로도 선택할 수 있다")
    void variantSelectableByNameOrId() {
        SesNode byName = engine.apply(ResortSes.tree(), transport(), "케이블카");
        SesNode byId = engine.apply(ResortSes.tree(), transport(), "ent-cablecar");

        assertThat(openNames(byName)).isEqualTo(openNames(byId));
    }

    @Test
    @DisplayName("선택지 밖의 답은 거부한다")
    void rejectsUnknownVariant() {
        assertThatThrownBy(() -> engine.apply(ResortSes.tree(), transport(), "헬리콥터"))
                .isInstanceOf(PruningException.class)
                .hasMessageContaining("선택지에 없는 값");
    }

    @Test
    @DisplayName("multi-aspect 개수는 선언된 범위를 벗어날 수 없다")
    void rejectsCountOutOfRange() {
        SesNode t = engine.apply(ResortSes.tree(), transport(), "셔틀버스");

        assertThatThrownBy(() -> engine.apply(t, multiBus(), 99))
                .isInstanceOf(PruningException.class)
                .hasMessageContaining("1~10");
    }

    @Test
    @DisplayName("개수를 늘려도 이미 채운 복제본의 값은 살아남는다")
    void increasingCountKeepsFilledInstances() {
        SesNode t = engine.apply(ResortSes.tree(), transport(), "셔틀버스");
        t = engine.apply(t, multiBus(), 2);
        t = engine.apply(t, SesAnchor.variable("", "ent-bus#0", "정원"), 45);

        t = engine.apply(t, multiBus(), 4);

        assertThat(openNames(t)).doesNotContain("ent-bus#0.정원");
        assertThat(openNames(t)).contains("ent-bus#1.정원", "ent-bus#3.정원");
    }

    @Test
    @DisplayName("타입이 맞지 않는 값은 거부한다")
    void rejectsWrongType() {
        SesNode t = engine.apply(ResortSes.tree(), transport(), "케이블카");

        assertThatThrownBy(() -> engine.apply(t,
                SesAnchor.variable("", "ent-cablecar", "정원"), "여덟 명"))
                .isInstanceOf(PruningException.class);
    }

    @Test
    @DisplayName("존재하지 않는 앵커는 조용히 무시하지 않고 실패한다")
    void unknownAnchorFails() {
        assertThatThrownBy(() -> engine.apply(ResortSes.tree(),
                SesAnchor.node("", AxisType.SPECIALIZATION, "spec-nope"), "x"))
                .isInstanceOf(PruningException.class)
                .hasMessageContaining("찾지 못했습니다");
    }

    private List<String> openNames(SesNode t) {
        return resolver.scan(t).stream().map(OpenSlot::slotName).toList();
    }

    private static SesAnchor transport() {
        return SesAnchor.node("리조트/이동설비", AxisType.SPECIALIZATION, "spec-transport");
    }

    private static SesAnchor multiBus() {
        return SesAnchor.node("리조트/이동설비/셔틀버스/버스", AxisType.MULTI_ASPECT, "multi-bus");
    }
}
