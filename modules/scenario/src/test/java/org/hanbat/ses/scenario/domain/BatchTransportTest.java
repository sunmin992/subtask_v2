package org.hanbat.ses.scenario.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hanbat.ses.devs.classic.Generator;
import org.hanbat.ses.devs.engine.Coordinator;
import org.hanbat.ses.devs.engine.RunStatus;
import org.hanbat.ses.devs.engine.SimConfig;
import org.hanbat.ses.devs.engine.SimulationModel;
import org.hanbat.ses.devs.engine.SimulationResult;
import org.hanbat.ses.devs.model.Coupling;
import org.hanbat.ses.devs.model.ModelRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이동설비의 운행 주기가 승객 도착에 흔들리지 않아야 한다.
 *
 * <p>회귀 방지용이다. timeAdvance 가 남은 시간이 아니라 매번 전체 간격을 돌려주면,
 * 시뮬레이터는 모든 전이 직후에 다음 이벤트 시각을 다시 잡으므로 승객이 한 명 도착할
 * 때마다 출발이 한 간격씩 미뤄진다. 도착이 간격보다 잦으면 차가 영영 출발하지 못한다.
 * 오류 없이 "대기열만 끝없이 늘어나는" 결과가 나오므로 눈으로는 알아채기 어렵다.
 */
class BatchTransportTest {

    private final Coordinator coordinator = new Coordinator();

    /** 2분마다 한 명씩 도착, 3분마다 정원 8명씩 출발. 수송력이 도착보다 5배 넉넉하다. */
    private SimulationResult run(double horizon) {
        SimulationModel model = new SimulationModel("net",
                List.of(new ModelRef("gen", new Generator(2.0, 100, "visitor")),
                        new ModelRef("car", new BatchTransport(3.0, 8))),
                List.of(new Coupling("gen", "visitor", "car", "in")),
                List.of());
        return coordinator.run(model, SimConfig.of(horizon));
    }

    @Test
    @DisplayName("운행 간격은 승객 도착과 무관하게 유지된다")
    void departureCadenceIsUnaffectedByArrivals() {
        SimulationResult r = run(60.0);

        assertThat(r.status()).isEqualTo(RunStatus.COMPLETED);
        // 60분 / 3분 = 20회. 도착이 미루었다면 이 값이 크게 줄어든다.
        assertThat(r.statistics().get("car.departures")).isEqualTo(20L);
    }

    @Test
    @DisplayName("수송력이 충분하면 대기열이 쌓이지 않는다")
    void queueStaysShortWhenCapacityIsAmple() {
        SimulationResult r = run(300.0);

        // 3분에 8명을 실을 수 있고 2분에 한 명씩만 오므로 줄이 길어질 이유가 없다.
        assertThat((Integer) r.statistics().get("car.maxQueue")).isLessThanOrEqualTo(2);
        assertThat(r.statistics().get("car.carried")).isEqualTo(100);
    }

    @Test
    @DisplayName("수송력이 모자라면 대기열이 실제로 늘어난다")
    void queueGrowsWhenCapacityIsShort() {
        SimulationModel model = new SimulationModel("net",
                List.of(new ModelRef("gen", new Generator(1.0, 200, "visitor")),
                        // 10분에 2명 = 분당 0.2명. 도착은 분당 1명.
                        new ModelRef("car", new BatchTransport(10.0, 2))),
                List.of(new Coupling("gen", "visitor", "car", "in")),
                List.of());

        SimulationResult r = coordinator.run(model, SimConfig.of(100.0));

        assertThat((Integer) r.statistics().get("car.maxQueue")).isGreaterThan(50);
        assertThat((Integer) r.statistics().get("car.carried")).isEqualTo(20);
    }

    @Test
    @DisplayName("편성이 여러 대면 승객을 나눠 태운다 — 같은 승객을 각각 태우지 않는다")
    void fleetSplitsTheStreamInsteadOfDuplicating() {
        // EIC 는 브로드캐스트라 3대가 모두 같은 메시지를 받는다.
        // 손대지 않으면 승객 60명이 180명으로 불어난다.
        int fleet = 3;
        var components = new java.util.ArrayList<ModelRef>();
        components.add(new ModelRef("gen", new Generator(1.0, 60, "visitor")));
        var couplings = new java.util.ArrayList<Coupling>();
        for (int i = 0; i < fleet; i++) {
            components.add(new ModelRef("bus#" + i, new BatchTransport(5.0, 10, i, fleet)));
            couplings.add(new Coupling("gen", "visitor", "bus#" + i, "in"));
        }

        SimulationResult r = coordinator.run(
                new SimulationModel("fleet", components, couplings, List.of()),
                SimConfig.of(200.0));

        int total = 0;
        for (int i = 0; i < fleet; i++) {
            int carried = (Integer) r.statistics().get("bus#" + i + ".carried");
            // 60명을 3대가 나누면 대당 20명 안팎이어야 한다.
            assertThat(carried).isBetween(15, 25);
            total += carried;
        }
        // 합계는 도착 인원과 정확히 같다 — 중복도 누락도 없어야 한다.
        assertThat(total).isEqualTo(60);
    }

    @Test
    @DisplayName("한 대뿐이면 모든 승객을 받는다")
    void singleVehicleTakesEveryone() {
        SimulationResult r = run(300.0);

        assertThat(r.statistics().get("car.carried")).isEqualTo(100);
    }

    @Test
    @DisplayName("출발 시각에 도착한 승객은 다음 편으로 넘어간다")
    void passengerArrivingAtDepartureWaitsForNextRun() {
        // 3분마다 도착, 3분마다 출발 — 매 출발이 합류 전이가 된다.
        SimulationModel model = new SimulationModel("net",
                List.of(new ModelRef("gen", new Generator(3.0, 5, "visitor")),
                        new ModelRef("car", new BatchTransport(3.0, 8))),
                List.of(new Coupling("gen", "visitor", "car", "in")),
                List.of());

        SimulationResult r = coordinator.run(model, SimConfig.of(30.0));

        // 매번 한 편씩 밀리므로 마지막 한 명은 다음 편에 실린다.
        assertThat(r.statistics().get("car.carried")).isEqualTo(5);
        assertThat((Integer) r.statistics().get("car.maxQueue")).isEqualTo(1);
    }
}
