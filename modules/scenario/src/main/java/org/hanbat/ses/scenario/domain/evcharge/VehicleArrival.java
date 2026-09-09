package org.hanbat.ses.scenario.domain.evcharge;

import java.util.List;
import java.util.Map;

import org.hanbat.ses.devs.model.AtomicModel;
import org.hanbat.ses.devs.model.Message;
import org.hanbat.ses.devs.random.Rng;

/**
 * 전기차 도착 — 지수분포 간격의 포아송 도착원.
 *
 * <p>리조트 도메인의 {@code Generator} 는 일정 주기로 내보낸다. 그것으로도 대화 흐름은
 * 시연되지만 대기행렬 이론의 어떤 공식과도 대조할 수 없다 — 결정론적 도착은 M/D/c 이고,
 * 그마저 해석해가 닫힌 형태로 떨어지지 않는다. 도착을 지수분포로 두어야 M/M/c 의
 * Erlang C 해석해와 숫자를 맞대 볼 수 있고, 그래야 엔진이 맞는지 <b>독립적으로</b> 검증된다.
 *
 * <p>난수 커서를 상태에 싣는다. 필드에 두면 같은 모델 인스턴스를 공유하는 복제본끼리
 * 난수열을 갉아먹어 실행 순서가 결과를 바꾼다. 자세한 이유는 {@link Rng} 에 적어 두었다.
 */
public final class VehicleArrival implements AtomicModel<VehicleArrival.S> {

    /** 도착원의 난수 계열을 다른 모델과 갈라 두는 상수. */
    static final long SEED_SALT = 0x5645_4849_434C_4531L;

    /**
     * @param sigma 다음 도착까지 남은 시간. 이미 뽑아 둔 표본이다.
     */
    public record S(long emitted, double sigma, Rng rng, boolean active) {
    }

    private final double meanInterArrival;
    private final long maxVehicles;
    private final Rng seedRng;
    private final String outPort;

    public VehicleArrival(double meanInterArrival, long maxVehicles, Long seed) {
        this(meanInterArrival, maxVehicles, seed, "vehicle");
    }

    public VehicleArrival(double meanInterArrival, long maxVehicles, Long seed, String outPort) {
        if (meanInterArrival <= 0) {
            throw new IllegalArgumentException("평균 도착 간격은 0보다 커야 합니다: " + meanInterArrival);
        }
        this.meanInterArrival = meanInterArrival;
        this.maxVehicles = maxVehicles;
        this.seedRng = Rng.forInstance(seed, 0, SEED_SALT);
        this.outPort = outPort;
    }

    /** 단위 시간당 도착률 λ. 해석해와 대조할 때 쓴다. */
    public double arrivalRate() {
        return 1.0 / meanInterArrival;
    }

    @Override
    public String type() {
        return "vehicle-arrival";
    }

    @Override
    public S initialState() {
        Rng.Draw first = seedRng.exponential(arrivalRate());
        return new S(0, first.value(), first.rng(), maxVehicles > 0);
    }

    @Override
    public S internalTransition(S state) {
        long emitted = state.emitted() + 1;
        if (emitted >= maxVehicles) {
            return new S(emitted, Double.POSITIVE_INFINITY, state.rng(), false);
        }
        Rng.Draw next = state.rng().exponential(arrivalRate());
        return new S(emitted, next.value(), next.rng(), true);
    }

    @Override
    public S externalTransition(S state, double elapsed, List<Message> input) {
        // 도착원은 입력을 받지 않는다. 남은 시간만 줄여 둔다 —
        // 이 줄이 없으면 외부 이벤트가 스칠 때마다 다음 도착이 뒤로 밀린다.
        return new S(state.emitted(), state.sigma() - elapsed, state.rng(), state.active());
    }

    @Override
    public List<Message> output(S state) {
        return state.active()
                ? List.of(new Message(outPort, new Vehicle(state.emitted() + 1)))
                : List.of();
    }

    @Override
    public double timeAdvance(S state) {
        return state.active() ? state.sigma() : Double.POSITIVE_INFINITY;
    }

    @Override
    public Map<String, Object> observe(S state) {
        return Map.of("generated", state.emitted(),
                "meanInterArrival", meanInterArrival);
    }
}
