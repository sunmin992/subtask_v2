package org.hanbat.ses.scenario.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.CouplingKind;
import org.hanbat.ses.core.model.CouplingSpec;
import org.hanbat.ses.core.pes.Pes;
import org.hanbat.ses.core.pes.PesNode;
import org.hanbat.ses.devs.engine.SimulationModel;
import org.hanbat.ses.devs.model.Coupling;
import org.hanbat.ses.scenario.domain.ResortModelFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 평탄화가 결합 모델의 배선을 끝단까지 잇는지 확인한다.
 *
 * <p>"방문객생성기 -> 셔틀버스" 한 줄은 그대로 실행할 수 없다. 셔틀버스가 결합 모델이므로
 * EIC 를 따라 내려가 버스 각각에 닿아야 한다. 이 전개가 빠지면 메시지가 조용히 증발하고,
 * 시뮬레이션은 오류 없이 "아무도 이동하지 않는" 결과를 낸다 — 가장 알아채기 어려운 종류의 버그다.
 */
class PesFlattenerTest {

    private final ModelFactoryRegistry registry = new ModelFactoryRegistry(List.of(
            new ResortModelFactories.VisitorGeneratorFactory(),
            new ResortModelFactories.ShuttleBusFactory(),
            new ResortModelFactories.LodgingFactory(),
            new ResortModelFactories.TransducerFactory()));

    private final PesFlattener flattener = new PesFlattener(registry);

    /**
     * <pre>
     * 리조트
     *   ├─ 방문객생성기 (leaf)
     *   ├─ 셔틀버스 (coupled)
     *   │    ├─ 버스#0 (leaf)
     *   │    └─ 버스#1 (leaf)
     *   └─ 호텔 (leaf)
     * </pre>
     */
    private Pes hierarchicalPes() {
        PesNode bus0 = new PesNode("bus#0", "버스", "shuttle-bus",
                Map.of("정원", 25, "왕복시간", 12.0), List.of(), List.of());
        PesNode bus1 = new PesNode("bus#1", "버스", "shuttle-bus",
                Map.of("정원", 25, "왕복시간", 12.0), List.of(), List.of());

        PesNode shuttle = new PesNode("shuttle", "셔틀버스", null, Map.of(),
                List.of(bus0, bus1),
                List.of(new CouplingSpec(CouplingKind.EIC, "shuttle", "in", "bus#0", "in"),
                        new CouplingSpec(CouplingKind.EIC, "shuttle", "in", "bus#1", "in"),
                        new CouplingSpec(CouplingKind.EOC, "bus#0", "out", "shuttle", "out"),
                        new CouplingSpec(CouplingKind.EOC, "bus#1", "out", "shuttle", "out")));

        PesNode arrival = new PesNode("arrival", "방문객생성기", "visitor-generator",
                Map.of("도착간격", 2.0, "총방문객", 100), List.of(), List.of());
        PesNode hotel = new PesNode("hotel", "호텔", "lodging",
                Map.of("객실수", 50, "평균숙박시간", 600.0), List.of(), List.of());

        return new Pes(new PesNode("resort", "리조트", null, Map.of(),
                List.of(arrival, shuttle, hotel),
                List.of(new CouplingSpec(CouplingKind.IC, "arrival", "visitor", "shuttle", "in"),
                        new CouplingSpec(CouplingKind.IC, "shuttle", "out", "hotel", "in"))));
    }

    @Test
    @DisplayName("리프만 컴포넌트가 된다")
    void onlyLeavesBecomeComponents() {
        SimulationModel model = flattener.flatten(hierarchicalPes(), 1440.0, 42L);

        assertThat(model.components()).extracting(c -> c.id())
                .containsExactlyInAnyOrder("arrival", "bus#0", "bus#1", "hotel");
    }

    @Test
    @DisplayName("결합 모델을 향한 배선은 EIC 를 따라 복제본 전체로 전개된다")
    void expandsThroughEic() {
        SimulationModel model = flattener.flatten(hierarchicalPes(), 1440.0, 42L);

        assertThat(model.couplings()).contains(
                new Coupling("arrival", "visitor", "bus#0", "in"),
                new Coupling("arrival", "visitor", "bus#1", "in"));
        // 결합 모델 자체를 가리키는 배선은 남지 않아야 한다.
        assertThat(model.couplings()).noneMatch(c ->
                c.toModel().equals("shuttle") || c.fromModel().equals("shuttle"));
    }

    @Test
    @DisplayName("결합 모델에서 나가는 배선은 EOC 를 따라 원자 모델의 출력으로 되짚어진다")
    void expandsThroughEoc() {
        SimulationModel model = flattener.flatten(hierarchicalPes(), 1440.0, 42L);

        assertThat(model.couplings()).contains(
                new Coupling("bus#0", "out", "hotel", "in"),
                new Coupling("bus#1", "out", "hotel", "in"));
    }

    @Test
    @DisplayName("같은 배선이 두 번 잡히지 않는다")
    void deduplicatesCouplings() {
        SimulationModel model = flattener.flatten(hierarchicalPes(), 1440.0, 42L);

        assertThat(model.couplings()).doesNotHaveDuplicates();
        // arrival->bus x2, bus->hotel x2
        assertThat(model.couplings()).hasSize(4);
    }

    @Test
    @DisplayName("등록되지 않은 모델을 가리키면 실행 전에 실패한다")
    void failsFastOnUnknownModel() {
        Pes pes = new Pes(new PesNode("root", "루트", null, Map.of(),
                List.of(new PesNode("x", "엑스", "존재하지-않는-모델", Map.of(), List.of(), List.of())),
                List.of()));

        assertThat(registry.supports("존재하지-않는-모델")).isFalse();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> flattener.flatten(pes, 100.0, 1L))
                .isInstanceOf(UnknownModelException.class)
                .hasMessageContaining("shuttle-bus");
    }
}
