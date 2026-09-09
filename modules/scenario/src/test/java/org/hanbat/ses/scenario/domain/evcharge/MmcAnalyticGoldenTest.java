package org.hanbat.ses.scenario.domain.evcharge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

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
 * DEVS 엔진 골든 시험 — M/M/c 해석해 대조 (TC-608).
 *
 * <p>이 시험이 이전의 Generator-Processor-Transducer 골든 시험을 대신한다. 고전 예제의
 * "이론값"은 손으로 따라간 사건 목록이었다. 손으로 따라갈 수 있다는 것은 검증력이 거기까지라는
 * 뜻이기도 하다 — 엔진과 같은 사람이 같은 규칙으로 센 숫자라, 규칙을 잘못 이해했다면
 * 시험도 똑같이 잘못 센다. 결정론적 주기 도착에는 대조할 외부 이론도 없다.
 *
 * <p>여기서는 <b>엔진 밖에서 온 공식</b>과 맞댄다. 포아송 도착·지수 서비스·창구 c 개의
 * 대기행렬은 Erlang C 로 정상상태 해가 닫힌 형태로 떨어진다. 시뮬레이션이 이 값에
 * 수렴하려면 사건 순서, 동시각 처리, 합류 전이, 브로드캐스트 배선이 <em>모두</em> 맞아야 한다.
 * 어느 하나가 틀리면 숫자가 어긋나고, 어긋난 방향이 무엇이 틀렸는지까지 알려 준다.
 *
 * <p>표본 오차가 있으므로 허용 오차를 둔다. 대기 관련 지표는 분포의 꼬리가 길고 자기상관이
 * 있어 10%, 이용률·처리율은 대수의 법칙이 빨리 듣는 지표라 2% 로 잡았다.
 */
class MmcAnalyticGoldenTest {

    /** 대기 지표(Lq, Wq)의 허용 오차. 상대값이다. */
    private static final double QUEUE_TOLERANCE = 0.10;

    /** 이용률·처리율의 허용 오차. */
    private static final double RATE_TOLERANCE = 0.02;

    private final Coordinator coordinator = new Coordinator();

    // ------------------------------------------------------------ M/M/c

    @Test
    @DisplayName("M/M/c (λ=0.8, μ=0.5, c=3) 가 Erlang C 해석해와 일치한다")
    void matchesErlangCSolution() {
        double lambda = 0.8;
        double mu = 0.5;
        int c = 3;
        double horizon = 200_000.0;

        SimulationResult r = run(station(lambda, mu, c, horizon, Integer.MAX_VALUE), horizon);

        assertThat(r.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(r.statistics()).containsEntry("queue.chargers", c);

        Mmc exact = Mmc.of(lambda, mu, c);

        assertThat(stat(r, "queue.utilization"))
                .as("이용률 ρ = λ/(cμ)")
                .isCloseTo(exact.utilization(), within(exact.utilization() * RATE_TOLERANCE));
        assertThat(stat(r, "queue.throughput"))
                .as("처리율 — 대기가 무한하므로 도착률과 같아야 한다")
                .isCloseTo(lambda, within(lambda * RATE_TOLERANCE));
        assertThat(stat(r, "queue.avgQueueLength"))
                .as("Lq = C(c,a)·ρ/(1-ρ)")
                .isCloseTo(exact.lq(), within(exact.lq() * QUEUE_TOLERANCE));
        assertThat(stat(r, "queue.avgWait"))
                .as("Wq = Lq/λ")
                .isCloseTo(exact.wq(), within(exact.wq() * QUEUE_TOLERANCE));
        assertThat(stat(r, "queue.avgSystem"))
                .as("W = Wq + 1/μ")
                .isCloseTo(exact.w(), within(exact.w() * QUEUE_TOLERANCE));
        assertThat(stat(r, "queue.avgInSystem"))
                .as("L = λW")
                .isCloseTo(exact.l(), within(exact.l() * QUEUE_TOLERANCE));
    }

    /**
     * 대기열을 공유하지 않으면 이 시험은 통과할 수 없다.
     *
     * <p>충전기마다 자기 대기열을 들면 계통은 M/M/1 이 c 개가 되고, 같은 λ·μ·c 에서
     * 평균 대기가 다섯 배 가까이 나온다. 두 값이 이만큼 벌어지므로, 위 시험이 통과한다는
     * 것 자체가 배정이 실제로 공유 대기열에서 나왔다는 증거가 된다.
     */
    @Test
    @DisplayName("M/M/c 의 대기는 부하를 나눈 M/M/1 c 개보다 다섯 배 이상 짧다")
    void sharedQueueBeatsSplitQueues() {
        Mmc shared = Mmc.of(0.8, 0.5, 3);

        // 충전기마다 자기 대기열을 들었을 때 — 도착이 셋으로 갈리는 M/M/1 세 개.
        double splitLambda = 0.8 / 3;
        double splitRho = splitLambda / 0.5;
        double splitLqEach = splitRho * splitRho / (1 - splitRho);
        double splitWq = splitLqEach / splitLambda;

        assertThat(shared.wq() * 5).isLessThan(splitWq);
        assertThat(shared.lq()).isLessThan(3 * splitLqEach);
    }

    // ------------------------------------------------------------ M/M/1/K

    @Test
    @DisplayName("유한 대기 정책이 M/M/1/K 의 거부율과 일치한다")
    void boundedQueueMatchesBlockingProbability() {
        double lambda = 1.0;
        double mu = 1.25;
        int maxWaiting = 4;
        int capacity = maxWaiting + 1;   // 대기 자리 + 충전 중 1대
        double horizon = 100_000.0;

        SimulationResult r = run(station(lambda, mu, 1, horizon, maxWaiting), horizon);

        assertThat(r.status()).isEqualTo(RunStatus.COMPLETED);

        double rho = lambda / mu;
        double blocking = Math.pow(rho, capacity) * (1 - rho) / (1 - Math.pow(rho, capacity + 1));

        assertThat(stat(r, "queue.balkRate"))
                .as("P_K — 계통에 K 대가 있어 돌아가는 비율")
                .isCloseTo(blocking, within(blocking * QUEUE_TOLERANCE));
        assertThat(stat(r, "queue.throughput"))
                .as("유효 처리율 = λ(1 - P_K)")
                .isCloseTo(lambda * (1 - blocking), within(lambda * RATE_TOLERANCE));
        assertThat((long) statLong(r, "queue.stillWaiting"))
                .as("대기열은 정책 한도를 넘지 않는다")
                .isLessThanOrEqualTo(maxWaiting);
    }

    // ------------------------------------------------------------ 배선·재현성

    @Test
    @DisplayName("배정은 정확히 한 충전기에만 꽂히고 차량은 사라지지 않는다")
    void assignmentsAreRoutedToExactlyOneCharger() {
        double horizon = 2_000.0;
        SimulationResult r = run(station(0.8, 0.5, 3, horizon, Integer.MAX_VALUE), horizon);

        for (int i = 0; i < 3; i++) {
            assertThat(statLong(r, "charger" + i + ".misroutedAssignments"))
                    .as("charger%d 가 남의 배정이나 겹치기 배정을 받지 않았다", i)
                    .isZero();
        }
        long completedByChargers = 0;
        for (int i = 0; i < 3; i++) {
            completedByChargers += statLong(r, "charger" + i + ".completed");
        }
        long arrived = statLong(r, "queue.arrived");
        long served = statLong(r, "queue.served");
        long waiting = statLong(r, "queue.stillWaiting");

        assertThat(served).isEqualTo(completedByChargers);
        // 도착한 차량은 충전을 마쳤거나, 대기 중이거나, 충전 중이다. 그 밖은 없다.
        assertThat(arrived - served - waiting)
                .as("충전 중인 차량 수")
                .isBetween(0L, 3L);
    }

    @Test
    @DisplayName("같은 시드는 같은 결과를 낸다")
    void sameSeedSameResult() {
        double horizon = 5_000.0;
        SimulationResult a = run(station(0.8, 0.5, 3, horizon, Integer.MAX_VALUE), horizon);
        SimulationResult b = run(station(0.8, 0.5, 3, horizon, Integer.MAX_VALUE), horizon);

        assertThat(b.eventCount()).isEqualTo(a.eventCount());
        assertThat(b.statistics()).isEqualTo(a.statistics());
    }

    @Test
    @DisplayName("EOC 로 선언한 완료 포트의 출력이 결과에 모인다")
    void collectsExternalOutputs() {
        double horizon = 500.0;
        SimulationModel model = station(0.8, 0.5, 3, horizon, Integer.MAX_VALUE);
        SimulationModel withEoc = new SimulationModel(model.id(), model.components(),
                model.couplings(),
                List.of(new Coupling("charger0", "done", model.id(), "charged"),
                        new Coupling("charger1", "done", model.id(), "charged"),
                        new Coupling("charger2", "done", model.id(), "charged")));

        SimulationResult r = run(withEoc, horizon);

        assertThat(r.outputs()).isNotEmpty();
        assertThat(r.outputs()).allMatch(m -> m.port().equals("charged"));
        assertThat(r.outputs()).allMatch(m -> m.value() instanceof Vehicle);
        assertThat(r.outputs()).hasSize((int) statLong(r, "queue.served"));
    }

    // ------------------------------------------------------------ 조립

    /** 도착원 1 + 충전기 c + 공유 대기열. SES 가 만들어 내는 것과 같은 배선이다. */
    private SimulationModel station(double lambda, double mu, int chargers,
                                    double horizon, int maxWaiting) {
        List<ModelRef> components = new ArrayList<>();
        components.add(new ModelRef("arrival",
                new VehicleArrival(1.0 / lambda, Long.MAX_VALUE, 42L)));
        for (int i = 0; i < chargers; i++) {
            components.add(new ModelRef("charger" + i, new Charger(1.0 / mu, 100.0, i, 42L)));
        }
        components.add(new ModelRef("queue", new ChargingQueue(horizon, maxWaiting)));

        List<Coupling> couplings = new ArrayList<>();
        couplings.add(new Coupling("arrival", "vehicle", "queue", "arrive"));
        for (int i = 0; i < chargers; i++) {
            // 배정은 브로드캐스트로 나가고 충전기가 자기 번호를 보고 걸러낸다.
            couplings.add(new Coupling("queue", "assign", "charger" + i, "assign"));
            couplings.add(new Coupling("charger" + i, "done", "queue", "done"));
            couplings.add(new Coupling("charger" + i, "ready", "queue", "ready"));
        }
        return SimulationModel.of("station", components, couplings);
    }

    private SimulationResult run(SimulationModel model, double horizon) {
        // 트레이스를 끈다. 수십만 사건을 남길 이유가 없고, 남기면 메모리만 먹는다.
        return coordinator.run(model,
                new SimConfig(horizon, 1.0, 42L, 120_000L, 20_000_000L, 10_000, 0));
    }

    private static double stat(SimulationResult r, String key) {
        Object v = r.statistics().get(key);
        assertThat(v).as("통계 %s", key).isInstanceOf(Number.class);
        return ((Number) v).doubleValue();
    }

    private static long statLong(SimulationResult r, String key) {
        Object v = r.statistics().get(key);
        assertThat(v).as("통계 %s", key).isInstanceOf(Number.class);
        return ((Number) v).longValue();
    }

    /**
     * M/M/c 정상상태 해 — Erlang C.
     *
     * <p>엔진과 무관한 순수 공식이다. 여기에 시뮬레이션 코드가 한 줄이라도 섞이면
     * 시험이 자기 자신을 검증하게 되므로, 참고 문헌의 식을 그대로 옮기기만 한다.
     *
     * @param a 제공 부하 λ/μ (Erlang)
     */
    private record Mmc(double lambda, double mu, int c, double a) {

        static Mmc of(double lambda, double mu, int c) {
            return new Mmc(lambda, mu, c, lambda / mu);
        }

        double utilization() {
            return a / c;
        }

        /** 모든 창구가 차서 기다려야 할 확률 C(c, a). */
        double delayProbability() {
            double sum = 0.0;
            double term = 1.0;              // a^0/0!
            for (int n = 0; n < c; n++) {
                if (n > 0) {
                    term *= a / n;
                }
                sum += term;
            }
            double last = term * a / c;     // a^c/c!
            double top = last / (1 - utilization());
            return top / (sum + top);
        }

        double lq() {
            double rho = utilization();
            return delayProbability() * rho / (1 - rho);
        }

        double wq() {
            return lq() / lambda;
        }

        double w() {
            return wq() + 1 / mu;
        }

        double l() {
            return lambda * w();
        }
    }
}
