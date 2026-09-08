package org.hanbat.ses.devs.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hanbat.ses.devs.classic.Generator;
import org.hanbat.ses.devs.classic.Processor;
import org.hanbat.ses.devs.classic.Transducer;
import org.hanbat.ses.devs.model.Coupling;
import org.hanbat.ses.devs.model.ModelRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Generator-Processor-Transducer 골든 테스트.
 *
 * <p>주기 2, 처리시간 3, horizon 20 이면 손으로 전부 따라갈 수 있다.
 * <pre>
 *   생성   t = 2, 4, 6, ... , 20                 -> 10건
 *   처리   t = 2..5, 6..9, 10..13, 14..17        -> 4건 완료
 *   폐기   t = 4, 8, 12, 16, 20 (처리 중 도착)    -> 5건
 *   t = 18 에 시작한 작업은 t = 21 완료 예정이라 horizon 밖이다.
 * </pre>
 * 이 수치가 어긋나면 엔진이 회귀한 것이다. 이후 모든 도메인 모델은 이 기준선 위에 얹힌다.
 */
class GptGoldenTest {

    private static final double PERIOD = 2.0;
    private static final double PROCESSING = 3.0;
    private static final double HORIZON = 20.0;

    private final Coordinator coordinator = new Coordinator();

    private SimulationModel gpt() {
        return new SimulationModel("gpt",
                List.of(
                        new ModelRef("gen", new Generator(PERIOD, 100)),
                        new ModelRef("proc", new Processor(PROCESSING)),
                        new ModelRef("trans", new Transducer(HORIZON))),
                List.of(
                        new Coupling("gen", "out", "proc", "in"),
                        new Coupling("gen", "out", "trans", "arrive"),
                        new Coupling("proc", "out", "trans", "done")),
                List.of(new Coupling("proc", "out", "gpt", "completed")));
    }

    @Test
    @DisplayName("고전 GPT 예제가 이론값과 일치한다")
    void matchesTheoreticalValues() {
        SimulationResult r = coordinator.run(gpt(), SimConfig.of(HORIZON));

        assertThat(r.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(r.endTime()).isEqualTo(HORIZON);

        assertThat(r.statistics())
                .containsEntry("gen.generated", 10)
                .containsEntry("proc.processed", 4)
                .containsEntry("proc.discarded", 5)
                .containsEntry("trans.arrived", 10)
                .containsEntry("trans.solved", 4);
    }

    @Test
    @DisplayName("평균 체류시간은 처리시간과 같고 가동률은 12/20 이다")
    void derivedStatisticsAreExact() {
        SimulationResult r = coordinator.run(gpt(), SimConfig.of(HORIZON));

        assertThat((Double) r.statistics().get("trans.avgTurnaround")).isEqualTo(PROCESSING);
        assertThat((Double) r.statistics().get("trans.throughput")).isEqualTo(4 / HORIZON);
        assertThat((Double) r.statistics().get("proc.utilization")).isEqualTo(12.0 / HORIZON);
    }

    @Test
    @DisplayName("EOC 로 선언한 포트의 출력은 결과에 모인다")
    void collectsExternalOutputs() {
        SimulationResult r = coordinator.run(gpt(), SimConfig.of(HORIZON));

        assertThat(r.outputs()).hasSize(4);
        assertThat(r.outputs()).allMatch(m -> m.port().equals("completed"));
        assertThat(r.outputs().get(0).value()).isEqualTo("job-1");
    }

    @Test
    @DisplayName("이벤트가 발생한 시각만 트레이스에 남는다")
    void tracesOnlyEventTimes() {
        SimulationResult r = coordinator.run(gpt(), SimConfig.of(HORIZON));

        List<Double> times = r.trace().stream().map(TraceEntry::time).toList();

        // 짝수 시각(생성) + 5, 9, 13, 17(처리 완료)
        assertThat(times).containsExactly(
                2.0, 4.0, 5.0, 6.0, 8.0, 9.0, 10.0, 12.0, 13.0, 14.0, 16.0, 17.0, 18.0, 20.0);
        assertThat(r.eventCount()).isEqualTo(times.size());
    }

    @Test
    @DisplayName("horizon 을 늘리면 t=18 작업이 완료되어 처리 건수가 하나 늘어난다")
    void longerHorizonCompletesTheLastJob() {
        SimulationResult r = coordinator.run(gpt(), SimConfig.of(22.0));

        assertThat(r.statistics()).containsEntry("proc.processed", 5);
        // 관측 시간은 20 에서 끝났으므로 집계기는 21 의 완료를 세지 않는다.
        assertThat(r.statistics()).containsEntry("trans.solved", 4);
    }

    @Test
    @DisplayName("모든 컴포넌트가 passive 가 되면 horizon 전이라도 조기 종료한다")
    void stopsWhenQuiescent() {
        SimulationModel small = new SimulationModel("small",
                List.of(new ModelRef("gen", new Generator(PERIOD, 3))),
                List.of(), List.of());

        SimulationResult r = coordinator.run(small, SimConfig.of(1000.0));

        assertThat(r.status()).isEqualTo(RunStatus.QUIESCENT);
        assertThat(r.endTime()).isEqualTo(6.0);
        assertThat(r.statistics()).containsEntry("gen.generated", 3);
    }
}
