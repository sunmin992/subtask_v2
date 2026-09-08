package org.hanbat.ses.devs.classic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.devs.model.AtomicModel;
import org.hanbat.ses.devs.model.Message;

/**
 * 고전 GPT 예제의 Processor — 한 번에 한 작업만 처리하고, 처리 중 도착한 작업은 버린다.
 *
 * <p>버린 작업 수를 세어 두는 것이 중요하다. 시나리오 결과에서 "케이블카를 3분 간격으로
 * 돌렸더니 방문객 40%가 못 탔다"를 보여주려면 이 숫자가 필요하다.
 */
public final class Processor implements AtomicModel<Processor.S> {

    /**
     * @param job     처리 중인 작업. null 이면 idle.
     * @param sigma   다음 내부 전이까지 남은 시간.
     * @param busyTime 누적 가동 시간 — 가동률 계산용.
     */
    public record S(Object job, double sigma, int processed, int discarded,
                    double busyTime, double clock) {
    }

    private final double processingTime;
    private final String inPort;
    private final String outPort;

    public Processor(double processingTime) {
        this(processingTime, "in", "out");
    }

    public Processor(double processingTime, String inPort, String outPort) {
        if (processingTime <= 0) {
            throw new IllegalArgumentException("처리 시간은 0보다 커야 합니다: " + processingTime);
        }
        this.processingTime = processingTime;
        this.inPort = inPort;
        this.outPort = outPort;
    }

    @Override
    public String type() {
        return "processor";
    }

    @Override
    public S initialState() {
        return new S(null, Double.POSITIVE_INFINITY, 0, 0, 0.0, 0.0);
    }

    @Override
    public S internalTransition(S state) {
        // 처리 완료 -> idle
        double clock = state.clock() + state.sigma();
        return new S(null, Double.POSITIVE_INFINITY, state.processed() + 1,
                state.discarded(), state.busyTime() + processingTime, clock);
    }

    @Override
    public S externalTransition(S state, double elapsed, List<Message> input) {
        double clock = state.clock() + elapsed;
        if (state.job() != null) {
            // 처리 중 — 들어온 작업은 모두 버리고 남은 시간만 줄인다.
            int discarded = state.discarded() + countJobs(input);
            return new S(state.job(), state.sigma() - elapsed, state.processed(),
                    discarded, state.busyTime(), clock);
        }
        Object job = firstJob(input);
        if (job == null) {
            return new S(state.job(), state.sigma(), state.processed(),
                    state.discarded(), state.busyTime(), clock);
        }
        // 동시에 여러 건이 오면 첫 건만 처리하고 나머지는 버린다.
        int discarded = state.discarded() + countJobs(input) - 1;
        return new S(job, processingTime, state.processed(), discarded, state.busyTime(), clock);
    }

    @Override
    public List<Message> output(S state) {
        return state.job() == null ? List.of() : List.of(new Message(outPort, state.job()));
    }

    @Override
    public double timeAdvance(S state) {
        return state.sigma();
    }

    @Override
    public Map<String, Object> observe(S state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("processed", state.processed());
        m.put("discarded", state.discarded());
        m.put("busyTime", state.busyTime());
        m.put("utilization", state.clock() > 0 ? state.busyTime() / state.clock() : 0.0);
        return m;
    }

    private int countJobs(List<Message> input) {
        return (int) input.stream().filter(m -> m.port().equals(inPort)).count();
    }

    private Object firstJob(List<Message> input) {
        return input.stream().filter(m -> m.port().equals(inPort))
                .map(Message::value).findFirst().orElse(null);
    }
}
