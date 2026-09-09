package org.hanbat.ses.scenario.domain.evcharge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.devs.model.AtomicModel;
import org.hanbat.ses.devs.model.Message;

/**
 * 공유 대기열 겸 집계기 — 충전소의 대기 정책이 사는 곳.
 *
 * <p>세 가지 일을 한다. 나누지 않은 이유는 셋이 같은 장부를 보기 때문이다.
 * <ol>
 *   <li><b>대기</b> — 도착한 차량을 선착순으로 세우고, 정책이 정한 한도를 넘으면 돌려보낸다.</li>
 *   <li><b>배정</b> — 비었다고 알린 충전기에게 대기 순서대로 배정한다.</li>
 *   <li><b>관측</b> — 대기 길이를 시간으로 적분해 Lq 를, 대기 시간을 차량별로 재어 Wq 를 낸다.</li>
 * </ol>
 * 배정을 대기열 밖에 두면 "누가 몇 시부터 기다렸는가"를 두 곳에서 추적하게 되고,
 * 그 둘은 반드시 어긋난다. 어긋난 쪽이 이기면 대기시간 통계가 조용히 틀린다.
 *
 * <h2>배정이 출력과 전이에서 같아야 하는 이유</h2>
 * <p>DEVS 는 출력을 먼저 내보내고 그 다음 내부 전이를 적용한다. 두 곳이 각각 배정을
 * 계산하면 한쪽만 고치는 날이 오고, 그날 차량은 배정 메시지를 받은 충전기와 다른 곳에서
 * 대기열을 떠난다. 그래서 배정은 {@link #assignments} 한 함수에서만 나온다 —
 * 상태만 보고 정해지는 순수 함수이므로 두 번 불러도 같은 답이 나온다.
 */
public final class ChargingQueue implements AtomicModel<ChargingQueue.S> {

    /**
     * @param waiting   대기 중인 차량. 앞이 먼저 온 쪽이다.
     * @param arrivedAt 계통 안에 있는 차량의 도착 시각 (대기 중 + 충전 중).
     * @param free      비었다고 알려온 충전기 번호. 오름차순을 유지해 배정을 결정론적으로 만든다.
     * @param clock     자체 시계. 전이 함수는 절대 시각을 받지 않으므로 경과 시간을 누적한다.
     * @param queueArea 대기 길이의 시간 적분 -> Lq.
     * @param busyArea  충전 중 대수의 시간 적분 -> 이용률 ρ.
     */
    public record S(
            List<Vehicle> waiting,
            Map<Long, Double> arrivedAt,
            List<Integer> free,
            int knownChargers,
            long arrived,
            long balked,
            long dispatched,
            long served,
            double waitSum,
            double systemSum,
            double queueArea,
            double busyArea,
            double clock,
            boolean observing
    ) {

        int inService() {
            return arrivedAt.size() - waiting.size();
        }
    }

    private final double observationTime;
    private final int maxWaiting;

    /**
     * @param maxWaiting 대기 자리 수. 넘어서 도착한 차량은 돌려보낸다(balking).
     *                   {@link Integer#MAX_VALUE} 면 무한 대기다.
     */
    public ChargingQueue(double observationTime, int maxWaiting) {
        this.observationTime = observationTime;
        this.maxWaiting = maxWaiting;
    }

    @Override
    public String type() {
        return "queue-transducer";
    }

    @Override
    public S initialState() {
        return new S(List.of(), Map.of(), List.of(), 0,
                0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, observationTime > 0);
    }

    /**
     * 지금 상태에서 확정되는 배정 목록. 출력과 내부 전이가 함께 본다.
     *
     * <p>대기 순서와 충전기 번호 순서로 짝지으므로 같은 상태에서는 언제나 같은 답이다.
     */
    private static List<Assignment> assignments(S s) {
        int n = Math.min(s.waiting().size(), s.free().size());
        if (n == 0) {
            return List.of();
        }
        List<Assignment> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Assignment(s.free().get(i), s.waiting().get(i)));
        }
        return out;
    }

    @Override
    public List<Message> output(S state) {
        List<Assignment> pairs = assignments(state);
        if (pairs.isEmpty()) {
            return List.of();
        }
        List<Message> out = new ArrayList<>(pairs.size());
        pairs.forEach(a -> out.add(new Message("assign", a)));
        return out;
    }

    @Override
    public double timeAdvance(S state) {
        if (!assignments(state).isEmpty()) {
            return 0.0;   // 배정은 지체 없이 나간다.
        }
        if (state.observing()) {
            return Math.max(0.0, observationTime - state.clock());
        }
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public S internalTransition(S state) {
        List<Assignment> pairs = assignments(state);
        if (!pairs.isEmpty()) {
            return dispatch(state, pairs);
        }
        return closeObservation(state);
    }

    @Override
    public S externalTransition(S state, double elapsed, List<Message> input) {
        S s = advanceClock(state, elapsed);

        List<Vehicle> waiting = new ArrayList<>(s.waiting());
        Map<Long, Double> arrivedAt = new LinkedHashMap<>(s.arrivedAt());
        List<Integer> free = new ArrayList<>(s.free());
        int knownChargers = s.knownChargers();
        long arrived = s.arrived();
        long balked = s.balked();
        long served = s.served();
        double systemSum = s.systemSum();

        for (Message m : input) {
            switch (m.port()) {
                case "ready" -> {
                    if (m.value() instanceof Integer idx) {
                        knownChargers = Math.max(knownChargers, idx + 1);
                        if (!free.contains(idx)) {
                            free.add(idx);
                        }
                    }
                }
                case "done" -> {
                    if (m.value() instanceof Vehicle v) {
                        Double since = arrivedAt.remove(v.id());
                        if (since != null && s.observing()) {
                            served++;
                            systemSum += s.clock() - since;
                        }
                    }
                }
                case "arrive" -> {
                    if (!(m.value() instanceof Vehicle v) || !s.observing()) {
                        continue;
                    }
                    arrived++;
                    if (waiting.size() >= maxWaiting) {
                        balked++;   // 대기 자리가 없다 — 정책이 돌려보낸다.
                        continue;
                    }
                    waiting.add(v);
                    arrivedAt.put(v.id(), s.clock());
                }
                default -> {
                    // 모르는 포트는 무시한다.
                }
            }
        }
        free.sort(Integer::compareTo);

        return new S(List.copyOf(waiting), Map.copyOf(arrivedAt), List.copyOf(free),
                knownChargers, arrived, balked, s.dispatched(), served,
                s.waitSum(), systemSum, s.queueArea(), s.busyArea(), s.clock(), s.observing());
    }

    /**
     * 배정을 확정한다. 시각은 흐르지 않는다 — 이 전이는 ta = 0 으로 일어난다.
     */
    private S dispatch(S s, List<Assignment> pairs) {
        List<Vehicle> waiting = new ArrayList<>(s.waiting());
        List<Integer> free = new ArrayList<>(s.free());
        double waitSum = s.waitSum();
        long dispatched = s.dispatched();

        for (Assignment a : pairs) {
            waiting.remove(0);
            free.remove(Integer.valueOf(a.charger()));
            if (s.observing()) {
                Double since = s.arrivedAt().get(a.vehicle().id());
                if (since != null) {
                    waitSum += s.clock() - since;
                    dispatched++;
                }
            }
        }
        return new S(List.copyOf(waiting), s.arrivedAt(), List.copyOf(free),
                s.knownChargers(), s.arrived(), s.balked(), dispatched, s.served(),
                waitSum, s.systemSum(), s.queueArea(), s.busyArea(), s.clock(), s.observing());
    }

    /** 관측 창을 닫는다. 이후 들어오는 사건은 세지 않는다 — 어느 구간의 값인지 흐려진다. */
    private S closeObservation(S s) {
        S closed = advanceClock(s, Math.max(0.0, observationTime - s.clock()));
        return new S(closed.waiting(), closed.arrivedAt(), closed.free(),
                closed.knownChargers(), closed.arrived(), closed.balked(),
                closed.dispatched(), closed.served(), closed.waitSum(), closed.systemSum(),
                closed.queueArea(), closed.busyArea(), closed.clock(), false);
    }

    /** 시계를 밀면서 면적 두 개를 함께 적분한다. 관측 창 밖의 시간은 넣지 않는다. */
    private S advanceClock(S s, double elapsed) {
        double usable = s.observing()
                ? Math.max(0.0, Math.min(elapsed, observationTime - s.clock()))
                : 0.0;
        double queueArea = s.queueArea() + s.waiting().size() * usable;
        double busyArea = s.busyArea() + s.inService() * usable;
        return new S(s.waiting(), s.arrivedAt(), s.free(), s.knownChargers(),
                s.arrived(), s.balked(), s.dispatched(), s.served(),
                s.waitSum(), s.systemSum(), queueArea, busyArea,
                s.clock() + elapsed, s.observing());
    }

    @Override
    public Map<String, Object> observe(S s) {
        double t = observationTime;
        int c = Math.max(1, s.knownChargers());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chargers", s.knownChargers());
        m.put("arrived", s.arrived());
        m.put("balked", s.balked());
        m.put("served", s.served());
        m.put("stillWaiting", s.waiting().size());
        m.put("observationTime", t);
        m.put("maxWaiting", maxWaiting == Integer.MAX_VALUE ? -1 : maxWaiting);
        // Wq — 배정된 차량의 평균 대기시간.
        m.put("avgWait", s.dispatched() > 0 ? s.waitSum() / s.dispatched() : 0.0);
        // W — 완료된 차량의 평균 체류시간 (대기 + 충전).
        m.put("avgSystem", s.served() > 0 ? s.systemSum() / s.served() : 0.0);
        // Lq, L — 시간 평균이므로 관측 시간으로 나눈다.
        m.put("avgQueueLength", t > 0 ? s.queueArea() / t : 0.0);
        m.put("avgInSystem", t > 0 ? (s.queueArea() + s.busyArea()) / t : 0.0);
        // ρ — 충전기 한 대가 일하고 있던 시간의 비율.
        m.put("utilization", t > 0 ? s.busyArea() / (c * t) : 0.0);
        m.put("throughput", t > 0 ? s.served() / t : 0.0);
        m.put("balkRate", s.arrived() > 0 ? (double) s.balked() / s.arrived() : 0.0);
        return m;
    }
}
