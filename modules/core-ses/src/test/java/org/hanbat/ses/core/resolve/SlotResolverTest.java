package org.hanbat.ses.core.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hanbat.ses.core.fixture.ResortSes;
import org.hanbat.ses.core.model.AxisType;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.prune.PruningEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlotResolverTest {

    private final SlotResolver resolver = new SlotResolver();
    private final PruningEngine engine = new PruningEngine();

    @Test
    @DisplayName("미해결 spec 의 하위 변수는 질문 목록에 나타나지 않는다")
    void unresolvedSpecHidesItsChildren() {
        List<OpenSlot> open = resolver.scan(ResortSes.tree());

        assertThat(names(open)).containsExactlyInAnyOrder("spec-transport", "spec-lodging");
        assertThat(names(open)).noneMatch(n -> n.startsWith("ent-cablecar"));
        assertThat(names(open)).noneMatch(n -> n.startsWith("ent-hotel"));
    }

    @Test
    @DisplayName("구조 답변이 새 값 슬롯을 파생시키고 선택되지 않은 가지는 사라진다")
    void structuralAnswerDerivesNewSlots() {
        SesNode t = engine.apply(ResortSes.tree(), transportAnchor(), "케이블카");

        List<String> open = names(resolver.scan(t));

        assertThat(open).contains("ent-cablecar.정원", "ent-cablecar.운행간격");
        assertThat(open).doesNotContain("multi-bus");
        assertThat(open).doesNotContain("ent-monorail.정원");
    }

    @Test
    @DisplayName("multi-aspect 개수를 정하면 복제본마다 개별 슬롯이 파생된다")
    void multiplicityDerivesPerInstanceSlots() {
        SesNode t = engine.apply(ResortSes.tree(), transportAnchor(), "셔틀버스");
        assertThat(names(resolver.scan(t))).contains("multi-bus");

        t = engine.apply(t, SesAnchor.node("리조트/이동설비/셔틀버스/버스",
                AxisType.MULTI_ASPECT, "multi-bus"), 3);

        assertThat(names(resolver.scan(t)))
                .contains("ent-bus#0.정원", "ent-bus#1.정원", "ent-bus#2.정원")
                .doesNotContain("ent-bus#3.정원");
    }

    @Test
    @DisplayName("기본값이 있는 변수는 질문 대상이 아니다")
    void defaultedVarsAreNotAsked() {
        SesNode t = engine.apply(ResortSes.tree(), transportAnchor(), "케이블카");

        // 방문객생성기의 도착간격/총방문객은 기본값이 있으므로 열리지 않는다.
        assertThat(names(resolver.scan(t))).doesNotContain("ent-arrival.도착간격", "ent-arrival.총방문객");
    }

    @Test
    @DisplayName("스캔 결과는 얕은 깊이부터, 같은 깊이에서는 구조 슬롯부터 나온다")
    void slotsAreOrderedByDepthThenStructureFirst() {
        SesNode t = engine.apply(ResortSes.tree(), transportAnchor(), "셔틀버스");
        t = engine.apply(t, SesAnchor.node("리조트/숙박시설",
                AxisType.SPECIALIZATION, "spec-lodging"), "호텔");

        List<OpenSlot> open = resolver.scan(t);

        assertThat(open).isNotEmpty();
        for (int i = 1; i < open.size(); i++) {
            OpenSlot prev = open.get(i - 1);
            OpenSlot cur = open.get(i);
            assertThat(prev.depth()).isLessThanOrEqualTo(cur.depth());
            if (prev.depth() == cur.depth() && prev.kind() != cur.kind()) {
                assertThat(prev.kind().isStructural()).isTrue();
            }
        }
    }

    private static SesAnchor transportAnchor() {
        return SesAnchor.node("리조트/이동설비", AxisType.SPECIALIZATION, "spec-transport");
    }

    private static List<String> names(List<OpenSlot> slots) {
        return slots.stream().map(OpenSlot::slotName).toList();
    }
}
