package org.hanbat.ses.devs.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.hanbat.ses.devs.model.AtomicModel;
import org.hanbat.ses.devs.model.Message;
import org.hanbat.ses.devs.model.ModelRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 루프 안전장치 — 대화로 파라미터를 받는 이상 병적인 모델은 반드시 들어온다.
 * 서버가 멈추는 대신 부분 결과와 사유를 돌려주어야 한다.
 */
class SafetyGuardTest {

    private final Coordinator coordinator = new Coordinator();

    /** timeAdvance 가 항상 0 — 시뮬레이션 시각이 전혀 진행되지 않는다. */
    private record ZenoModel() implements AtomicModel<Integer> {
        @Override
        public String type() {
            return "zeno";
        }

        @Override
        public Integer initialState() {
            return 0;
        }

        @Override
        public Integer internalTransition(Integer state) {
            return state + 1;
        }

        @Override
        public Integer externalTransition(Integer state, double elapsed, List<Message> input) {
            return state;
        }

        @Override
        public List<Message> output(Integer state) {
            return List.of();
        }

        @Override
        public double timeAdvance(Integer state) {
            return 0.0;
        }

        @Override
        public Map<String, Object> observe(Integer state) {
            return Map.of("steps", state);
        }
    }

    /** 아주 작은 시간 간격으로 영원히 도는 모델. */
    private record TinyStepModel(double step) implements AtomicModel<Integer> {
        @Override
        public String type() {
            return "tiny";
        }

        @Override
        public Integer initialState() {
            return 0;
        }

        @Override
        public Integer internalTransition(Integer state) {
            return state + 1;
        }

        @Override
        public Integer externalTransition(Integer state, double elapsed, List<Message> input) {
            return state;
        }

        @Override
        public List<Message> output(Integer state) {
            return List.of();
        }

        @Override
        public double timeAdvance(Integer state) {
            return step;
        }
    }

    @Test
    @DisplayName("zeno 동작은 서버를 붙잡지 않고 사유와 함께 중단된다")
    void detectsZenoBehavior() {
        SimulationModel model = new SimulationModel("z",
                List.of(new ModelRef("z0", new ZenoModel())), List.of(), List.of());
        SimConfig cfg = new SimConfig(100.0, 1.0, 42L, 30_000L, 5_000_000L, 50, 100);

        SimulationResult r = coordinator.run(model, cfg);

        assertThat(r.status()).isEqualTo(RunStatus.ZENO);
        assertThat(r.partial()).isTrue();
        assertThat(r.note()).contains("timeAdvance");
        assertThat(r.endTime()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("이벤트 수 상한을 넘으면 부분 결과로 끊는다")
    void stopsOnEventLimit() {
        SimulationModel model = new SimulationModel("t",
                List.of(new ModelRef("t0", new TinyStepModel(0.001))), List.of(), List.of());
        SimConfig cfg = new SimConfig(1e9, 1.0, 42L, 30_000L, 1_000L, 10_000, 10);

        SimulationResult r = coordinator.run(model, cfg);

        assertThat(r.status()).isEqualTo(RunStatus.EVENT_LIMIT);
        assertThat(r.eventCount()).isEqualTo(1001);
        assertThat(r.note()).contains("상한");
    }

    @Test
    @DisplayName("타임아웃이 0 이면 첫 이벤트에서 즉시 끊긴다")
    void stopsOnTimeout() {
        SimulationModel model = new SimulationModel("t",
                List.of(new ModelRef("t0", new TinyStepModel(0.001))), List.of(), List.of());
        SimConfig cfg = new SimConfig(1e9, 1.0, 42L, 0L, 5_000_000L, 10_000, 10);

        SimulationResult r = coordinator.run(model, cfg);

        assertThat(r.status()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(r.note()).contains("끝나지 않았습니다");
    }

    @Test
    @DisplayName("트레이스는 상한까지만 쌓이고 실행 자체는 계속된다")
    void trimsTraceWithoutStopping() {
        SimulationModel model = new SimulationModel("t",
                List.of(new ModelRef("t0", new TinyStepModel(1.0))), List.of(), List.of());
        SimConfig cfg = new SimConfig(100.0, 1.0, 42L, 30_000L, 5_000_000L, 10_000, 10);

        SimulationResult r = coordinator.run(model, cfg);

        assertThat(r.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(r.trace()).hasSize(10);
        assertThat(r.eventCount()).isEqualTo(100);
    }
}
