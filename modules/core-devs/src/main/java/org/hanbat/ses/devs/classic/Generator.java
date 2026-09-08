package org.hanbat.ses.devs.classic;

import java.util.List;
import java.util.Map;

import org.hanbat.ses.devs.model.AtomicModel;
import org.hanbat.ses.devs.model.Message;

/**
 * 고전 GPT 예제의 Generator — 일정 주기로 작업을 하나씩 내보낸다.
 *
 * <p>maxJobs 에 도달하면 passive 로 떨어진다. 무한 생성은 horizon 이 있어도
 * 트레이스와 통계를 무의미하게 부풀리므로 상한을 두는 편이 낫다.
 */
public final class Generator implements AtomicModel<Generator.S> {

    public record S(int emitted, boolean active) {
    }

    private final double period;
    private final int maxJobs;
    private final String outPort;

    public Generator(double period, int maxJobs) {
        this(period, maxJobs, "out");
    }

    public Generator(double period, int maxJobs, String outPort) {
        if (period <= 0) {
            throw new IllegalArgumentException("생성 주기는 0보다 커야 합니다: " + period);
        }
        this.period = period;
        this.maxJobs = maxJobs;
        this.outPort = outPort;
    }

    @Override
    public String type() {
        return "generator";
    }

    @Override
    public S initialState() {
        return new S(0, true);
    }

    @Override
    public S internalTransition(S state) {
        int next = state.emitted() + 1;
        return new S(next, next < maxJobs);
    }

    @Override
    public S externalTransition(S state, double elapsed, List<Message> input) {
        return state;
    }

    @Override
    public List<Message> output(S state) {
        return state.active()
                ? List.of(new Message(outPort, "job-" + (state.emitted() + 1)))
                : List.of();
    }

    @Override
    public double timeAdvance(S state) {
        return state.active() ? period : Double.POSITIVE_INFINITY;
    }

    @Override
    public Map<String, Object> observe(S state) {
        return Map.of("generated", state.emitted());
    }
}
