package org.hanbat.ses.scenario.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hanbat.ses.devs.classic.Generator;
import org.hanbat.ses.devs.classic.Transducer;
import org.hanbat.ses.devs.engine.Coordinator;
import org.hanbat.ses.devs.engine.SimConfig;
import org.hanbat.ses.devs.engine.SimulationModel;
import org.hanbat.ses.devs.engine.SimulationResult;
import org.hanbat.ses.devs.model.Coupling;
import org.hanbat.ses.devs.model.ModelRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 숙박시설이 만실을 제대로 거절하는지, 그리고 <b>누가</b> 퇴실했는지를 잃지 않는지 본다.
 *
 * <p>후자가 중요한 이유: 퇴실 메시지에 상수를 실어 보내면 집계기가 도착과 완료를 짝지을 수
 * 없어 평균 체류시간이 0 으로 나온다. 그건 "체류시간이 0"이 아니라 "측정하지 못했다"는
 * 뜻인데, 결과 표에서는 둘이 똑같이 보인다.
 */
class LodgingTest {

    private final Coordinator coordinator = new Coordinator();

    @Test
    @DisplayName("객실이 차면 거절하고, 퇴실하면 다시 받는다")
    void rejectsWhenFullAndReadmitsAfterCheckout() {
        // 1분마다 1명, 객실 3실, 숙박 10분, 12분 관측.
        //   t=1,2,3   투숙 (만실)
        //   t=4..10   거절 7명
        //   t=11,12   1·2번 손님이 퇴실하는 시각이라 같은 순간 도착한 손님이 그 방에 든다
        //             (합류 전이: 퇴실 처리 후 입실 처리) -> 투숙 누적 5명
        SimulationModel model = new SimulationModel("net",
                List.of(new ModelRef("gen", new Generator(1.0, 20, "visitor")),
                        new ModelRef("hotel", new Lodging(3, 10.0))),
                List.of(new Coupling("gen", "visitor", "hotel", "in")),
                List.of());

        SimulationResult r = coordinator.run(model, SimConfig.of(12.0));

        assertThat(r.statistics()).containsEntry("hotel.rooms", 3);
        assertThat(r.statistics().get("hotel.admitted")).isEqualTo(5);
        assertThat(r.statistics().get("hotel.rejected")).isEqualTo(7);
        // 객실 수를 넘는 순간부터 거절이 시작되므로 재실은 절대 3을 넘지 않는다.
        assertThat(r.statistics().get("hotel.maxOccupancy")).isEqualTo(3);
        assertThat((Double) r.statistics().get("hotel.rejectionRate")).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("퇴실 메시지가 투숙객 신원을 그대로 실어 보내 체류시간이 측정된다")
    void checkoutCarriesGuestIdentitySoTurnaroundIsMeasurable() {
        double stay = 10.0;
        SimulationModel model = new SimulationModel("net",
                List.of(new ModelRef("gen", new Generator(1.0, 5, "visitor")),
                        new ModelRef("hotel", new Lodging(100, stay)),
                        new ModelRef("trans", new Transducer(100.0))),
                List.of(new Coupling("gen", "visitor", "hotel", "in"),
                        new Coupling("gen", "visitor", "trans", "arrive"),
                        new Coupling("hotel", "out", "trans", "done")),
                List.of());

        SimulationResult r = coordinator.run(model, SimConfig.of(60.0));

        assertThat(r.statistics().get("trans.arrived")).isEqualTo(5);
        assertThat(r.statistics().get("trans.solved")).isEqualTo(5);
        // 객실이 넉넉하니 모두가 정확히 숙박시간만큼 머문다.
        assertThat((Double) r.statistics().get("trans.avgTurnaround")).isEqualTo(stay);
        assertThat(r.statistics().get("trans.pending")).isEqualTo(0);
    }

    @Test
    @DisplayName("같은 시각에 여러 명이 퇴실해도 모두 내보낸다")
    void checksOutEveryoneDueAtTheSameTime() {
        // 5명이 동시에 도착하면 5명이 동시에 나간다.
        SimulationModel model = new SimulationModel("net",
                List.of(new ModelRef("gen", new Generator(1.0, 5, "visitor")),
                        new ModelRef("hotel", new Lodging(100, 10.0)),
                        new ModelRef("trans", new Transducer(100.0))),
                List.of(new Coupling("gen", "visitor", "hotel", "in"),
                        new Coupling("gen", "visitor", "trans", "arrive"),
                        new Coupling("hotel", "out", "trans", "done")),
                List.of());

        SimulationResult r = coordinator.run(model, SimConfig.of(60.0));

        assertThat(r.statistics().get("hotel.occupied")).isEqualTo(0);
        assertThat(r.statistics().get("trans.solved")).isEqualTo(5);
    }
}
