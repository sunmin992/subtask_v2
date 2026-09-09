package org.hanbat.ses.scenario.domain.evcharge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.devs.model.AtomicModel;
import org.hanbat.ses.devs.model.Message;
import org.hanbat.ses.devs.random.Rng;

/**
 * 충전기 한 대 — 지수분포 충전시간의 단일 서버.
 *
 * <p>급속기와 완속기는 이 클래스 하나로 귀결된다. 둘의 차이는 평균 충전시간과 출력이라는
 * 파라미터뿐이고, 다르게 만들어 두면 파라미터 이름만 다른 코드 두 벌을 유지하게 된다.
 * 리조트의 이동설비 세 종류가 {@code BatchTransport} 하나로 모이는 것과 같은 판단이다.
 *
 * <h2>왜 대기열을 스스로 갖지 않는가</h2>
 * <p>충전기가 각자 대기열을 들면 c 대는 M/M/1 이 c 개인 계통이 되지 M/M/c 가 아니다.
 * 둘은 전혀 다른 값을 낸다 — λ=0.8, μ=0.5, c=3 에서 M/M/c 의 평균 대기는 0.39분이지만
 * 부하를 셋으로 나눈 M/M/1 세 개는 2.29분으로 여섯 배에 가깝다. 대기열을 공유해야 하므로
 * 대기열은 {@link ChargingQueue} 한 곳에 두고, 충전기는 <b>비었다고 알리고 배정을 받는</b>
 * 쪽으로 뒤집었다.
 *
 * <p>그래서 시각 0 에 {@code ready} 를 한 번 내보낸다. 대기열은 이 인사를 받아 자기 앞에
 * 충전기가 몇 대 있는지 알게 된다 — 대수를 파라미터로 따로 알려 줄 필요가 없고,
 * multi-aspect 로 대수가 바뀌어도 배선만 그대로면 저절로 맞는다.
 */
public final class Charger implements AtomicModel<Charger.S> {

    /** 충전기의 난수 계열을 도착원과 갈라 두는 상수. */
    static final long SEED_SALT = 0x4348_4152_4745_5231L;

    /**
     * @param serving   충전 중인 차량. null 이면 비어 있다.
     * @param remaining 충전 완료까지 남은 시간.
     * @param current   지금 충전의 총 소요 시간. 완료 시 가동시간에 더한다.
     * @param announced 시각 0 의 ready 인사를 이미 보냈는가.
     */
    public record S(Vehicle serving, double remaining, double current, Rng rng,
                    int completed, double busy, int misrouted, boolean announced) {
    }

    private final double meanServiceTime;
    private final double powerKw;
    private final int index;
    private final Rng seedRng;

    public Charger(double meanServiceTime, double powerKw, int index, Long seed) {
        if (meanServiceTime <= 0) {
            throw new IllegalArgumentException("평균 충전시간은 0보다 커야 합니다: " + meanServiceTime);
        }
        this.meanServiceTime = meanServiceTime;
        this.powerKw = powerKw;
        this.index = index;
        this.seedRng = Rng.forInstance(seed, index, SEED_SALT);
    }

    /** 단위 시간당 서비스율 μ. */
    public double serviceRate() {
        return 1.0 / meanServiceTime;
    }

    @Override
    public String type() {
        return "charger";
    }

    @Override
    public S initialState() {
        // remaining = 0 으로 두어 시각 0 에 imminent 가 되게 한다. 그 한 번이 ready 인사다.
        return new S(null, 0.0, 0.0, seedRng, 0, 0.0, 0, false);
    }

    @Override
    public S internalTransition(S state) {
        if (!state.announced()) {
            return new S(null, Double.POSITIVE_INFINITY, 0.0, state.rng(),
                    state.completed(), state.busy(), state.misrouted(), true);
        }
        // 충전 완료 -> 비었음. ready 는 output 에서 done 과 함께 나갔다.
        return new S(null, Double.POSITIVE_INFINITY, 0.0, state.rng(),
                state.completed() + 1, state.busy() + state.current(),
                state.misrouted(), true);
    }

    @Override
    public S externalTransition(S state, double elapsed, List<Message> input) {
        double remaining = Double.isInfinite(state.remaining())
                ? Double.POSITIVE_INFINITY : state.remaining() - elapsed;

        Vehicle assigned = null;
        int misrouted = state.misrouted();
        for (Message m : input) {
            if (!"assign".equals(m.port()) || !(m.value() instanceof Assignment a)) {
                continue;
            }
            if (a.charger() != index) {
                continue;   // 브로드캐스트로 받은 남의 배정이다.
            }
            if (state.serving() != null || assigned != null) {
                // 대기열은 비었다고 알린 충전기에게만 배정하므로 여기 오면 안 된다.
                // 조용히 버리면 차량이 증발하고 처리량만 맞지 않으므로 세어 둔다.
                misrouted++;
                continue;
            }
            assigned = a.vehicle();
        }

        if (assigned == null) {
            return new S(state.serving(), remaining, state.current(), state.rng(),
                    state.completed(), state.busy(), misrouted, state.announced());
        }
        Rng.Draw service = state.rng().exponential(serviceRate());
        return new S(assigned, service.value(), service.value(), service.rng(),
                state.completed(), state.busy(), misrouted, state.announced());
    }

    @Override
    public List<Message> output(S state) {
        if (!state.announced()) {
            return List.of(new Message("ready", index));
        }
        if (state.serving() == null) {
            return List.of();
        }
        // 완료 통보와 "다시 비었음" 인사는 같은 순간에 함께 나간다.
        List<Message> out = new ArrayList<>(2);
        out.add(new Message("done", state.serving()));
        out.add(new Message("ready", index));
        return out;
    }

    @Override
    public double timeAdvance(S state) {
        if (!state.announced()) {
            return 0.0;
        }
        return state.serving() == null ? Double.POSITIVE_INFINITY : state.remaining();
    }

    @Override
    public Map<String, Object> observe(S state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("completed", state.completed());
        m.put("busyTime", state.busy());
        m.put("meanServiceTime", meanServiceTime);
        m.put("powerKw", powerKw);
        m.put("misroutedAssignments", state.misrouted());
        return m;
    }
}
