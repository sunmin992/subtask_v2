package org.hanbat.ses.devs.classic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.devs.model.AtomicModel;
import org.hanbat.ses.devs.model.Message;

/**
 * 고전 GPT 예제의 Transducer — 도착과 완료를 관측해 처리율과 평균 체류시간을 낸다.
 *
 * <p>전이 함수는 절대 시각을 받지 않으므로 경과 시간을 누적해 자체 시계를 굴린다.
 * 관측 시간이 끝나면 passive 로 떨어져 시뮬레이션을 자연 종료시킨다.
 */
public final class Transducer implements AtomicModel<Transducer.S> {

    /**
     * @param startTimes 아직 완료되지 않은 작업의 도착 시각.
     * @param sigma      관측 종료까지 남은 시간.
     */
    public record S(int arrived, int solved, double totalTurnaround,
                    Map<Object, Double> startTimes, double clock, double sigma,
                    boolean observing) {
    }

    private final double observationTime;
    private final String arrivePort;
    private final String solvedPort;

    public Transducer(double observationTime) {
        this(observationTime, "arrive", "done");
    }

    public Transducer(double observationTime, String arrivePort, String solvedPort) {
        this.observationTime = observationTime;
        this.arrivePort = arrivePort;
        this.solvedPort = solvedPort;
    }

    @Override
    public String type() {
        return "transducer";
    }

    @Override
    public S initialState() {
        return new S(0, 0, 0.0, Map.of(), 0.0, observationTime, true);
    }

    @Override
    public S internalTransition(S state) {
        // 관측 종료. 이후 들어오는 이벤트는 세지 않는다 —
        // 관측 창 밖의 건을 섞어 세면 처리율이 어느 구간의 값인지 알 수 없게 된다.
        return new S(state.arrived(), state.solved(), state.totalTurnaround(),
                state.startTimes(), state.clock() + state.sigma(),
                Double.POSITIVE_INFINITY, false);
    }

    @Override
    public S externalTransition(S state, double elapsed, List<Message> input) {
        double clock = state.clock() + elapsed;
        if (!state.observing()) {
            return new S(state.arrived(), state.solved(), state.totalTurnaround(),
                    state.startTimes(), clock, Double.POSITIVE_INFINITY, false);
        }
        int arrived = state.arrived();
        int solved = state.solved();
        double turnaround = state.totalTurnaround();
        Map<Object, Double> starts = new LinkedHashMap<>(state.startTimes());

        for (Message m : input) {
            if (m.port().equals(arrivePort)) {
                arrived++;
                starts.putIfAbsent(m.value(), clock);
            } else if (m.port().equals(solvedPort)) {
                solved++;
                Double start = starts.remove(m.value());
                if (start != null) {
                    turnaround += clock - start;
                }
            }
        }
        double sigma = Double.isInfinite(state.sigma())
                ? Double.POSITIVE_INFINITY : state.sigma() - elapsed;
        return new S(arrived, solved, turnaround, Map.copyOf(starts), clock, sigma, true);
    }

    /**
     * 관측 종료 시각에 도착한 이벤트는 관측 창 안으로 센다.
     *
     * <p>기본 합류 전이(내부 후 외부)를 쓰면 t = 관측종료 에 도착한 건이 버려진다.
     * 관측 구간을 닫힌 구간 [0, T] 로 보는 편이 결과를 설명하기 쉬우므로
     * 외부 전이를 먼저 적용한 뒤 창을 닫는다.
     */
    @Override
    public S confluentTransition(S state, List<Message> input) {
        return internalTransition(externalTransition(state, 0.0, input));
    }

    @Override
    public List<Message> output(S state) {
        return List.of();
    }

    @Override
    public double timeAdvance(S state) {
        return state.sigma();
    }

    @Override
    public Map<String, Object> observe(S state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("arrived", state.arrived());
        m.put("solved", state.solved());
        m.put("pending", state.startTimes().size());
        m.put("observationTime", observationTime);
        m.put("throughput", observationTime > 0 ? state.solved() / observationTime : 0.0);
        m.put("avgTurnaround", state.solved() > 0 ? state.totalTurnaround() / state.solved() : 0.0);
        return m;
    }
}
