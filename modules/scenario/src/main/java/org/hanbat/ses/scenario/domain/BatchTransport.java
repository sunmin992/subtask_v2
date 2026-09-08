package org.hanbat.ses.scenario.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.devs.model.AtomicModel;
import org.hanbat.ses.devs.model.Message;

/**
 * 정해진 간격마다 정원만큼 태워 보내는 이동설비.
 *
 * <p>케이블카, 모노레일, 셔틀버스 한 대가 모두 이 형태다. 다른 것은 파라미터 이름뿐이라
 * 모델을 세 벌 쓰는 대신 하나로 두고 팩토리에서 이름만 바꿔 읽는다.
 *
 * <p>대기 인원과 최대 대기열을 함께 관측한다. "케이블카를 3분 간격으로 돌렸더니
 * 최대 40명이 줄을 섰다"가 이 시뮬레이션에서 사용자가 실제로 알고 싶어 하는 값이다.
 *
 * <p><b>차량이 여러 대일 때</b>: DEVS 에서 결합 모델의 입력을 N 개 컴포넌트에 잇는 EIC 는
 * 브로드캐스트라, 손대지 않으면 승객 한 명이 버스 세 대에 <em>모두</em> 실린다.
 * 그래서 각 차량은 도착 순번을 세어 자기 몫({@code 순번 % 대수 == 내 번호})만 태운다.
 * 모든 차량이 같은 메시지를 같은 순서로 받으므로 이 방식은 스트림을 정확히 분할하고,
 * 난수를 쓰지 않으므로 재현성도 그대로다.
 */
public final class BatchTransport implements AtomicModel<BatchTransport.S> {

    /**
     * @param queue    탑승 대기열. 출발 시각에 앞에서부터 정원만큼 빠져나간다.
     * @param sigma    다음 출발까지 남은 시간.
     * @param maxQueue 관측된 최대 대기열 길이.
     */
    public record S(Deque<Object> queue, double sigma, int carried, int maxQueue,
                    double clock, long departures, long seen) {
    }

    private final double interval;
    private final int capacity;
    private final int shardIndex;
    private final int shardCount;
    private final String inPort;
    private final String outPort;

    public BatchTransport(double interval, int capacity) {
        this(interval, capacity, 0, 1, "in", "out");
    }

    /** 같은 정류장을 나눠 쓰는 차량 편성. shardCount 가 1 이면 모든 승객을 받는다. */
    public BatchTransport(double interval, int capacity, int shardIndex, int shardCount) {
        this(interval, capacity, shardIndex, shardCount, "in", "out");
    }

    public BatchTransport(double interval, int capacity, int shardIndex, int shardCount,
                          String inPort, String outPort) {
        if (interval <= 0) {
            throw new IllegalArgumentException("운행 간격은 0보다 커야 합니다: " + interval);
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("정원은 0보다 커야 합니다: " + capacity);
        }
        if (shardCount <= 0 || shardIndex < 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException(
                    "차량 번호가 편성 범위를 벗어났습니다: " + shardIndex + "/" + shardCount);
        }
        this.interval = interval;
        this.capacity = capacity;
        this.shardIndex = shardIndex;
        this.shardCount = shardCount;
        this.inPort = inPort;
        this.outPort = outPort;
    }

    @Override
    public String type() {
        return "batch-transport";
    }

    @Override
    public S initialState() {
        return new S(new ArrayDeque<>(), interval, 0, 0, 0.0, 0, 0);
    }

    @Override
    public S internalTransition(S state) {
        // 출발 시각. 대기열에서 정원만큼 실어 내보내고 다음 출발을 예약한다.
        Deque<Object> queue = new ArrayDeque<>(state.queue());
        int boarded = 0;
        while (boarded < capacity && !queue.isEmpty()) {
            queue.poll();
            boarded++;
        }
        // 출발했으니 다음 출발까지 다시 한 간격.
        return new S(queue, interval, state.carried() + boarded, state.maxQueue(),
                state.clock() + state.sigma(), state.departures() + 1, state.seen());
    }

    @Override
    public S externalTransition(S state, double elapsed, List<Message> input) {
        Deque<Object> queue = new ArrayDeque<>(state.queue());
        long seen = state.seen();
        for (Message m : input) {
            if (!m.port().equals(inPort)) {
                continue;
            }
            // 편성 전체가 같은 메시지를 같은 순서로 받으므로, 순번으로 나누면 정확히 분할된다.
            if (seen % shardCount == shardIndex) {
                queue.add(m.value());
            }
            seen++;
        }
        // 승객이 왔다고 출발 시각이 미뤄지면 안 된다. 남은 시간에서 경과분만 뺀다.
        return new S(queue, state.sigma() - elapsed, state.carried(),
                Math.max(state.maxQueue(), queue.size()),
                state.clock() + elapsed, state.departures(), seen);
    }

    /**
     * 출발 시각에 도착한 승객은 이번 편에 타지 못한다.
     *
     * <p>기본 합류 전이(내부 후 외부)가 정확히 그 동작이다. 출력은 내부 전이 <em>전</em>
     * 상태로 계산되므로 방금 도착한 승객은 다음 편으로 넘어간다.
     */
    @Override
    public List<Message> output(S state) {
        Deque<Object> queue = state.queue();
        if (queue.isEmpty()) {
            return List.of();
        }
        List<Message> out = new ArrayList<>();
        int n = 0;
        for (Object passenger : queue) {
            if (n++ >= capacity) {
                break;
            }
            out.add(new Message(outPort, passenger));
        }
        return out;
    }

    /**
     * 다음 출발까지 남은 시간.
     *
     * <p>여기서 항상 interval 을 돌려주면 안 된다. 시뮬레이터는 어떤 전이든 그 직후에
     * timeAdvance 를 다시 물어 다음 이벤트 시각을 잡으므로, 승객이 한 명 도착할 때마다
     * 출발이 한 간격씩 미뤄진다. 도착이 간격보다 잦으면 차가 영영 출발하지 못한다.
     * 대기열이 비어 있어도 운행은 계속된다 — 빈 차가 도는 것도 운영 비용이다.
     */
    @Override
    public double timeAdvance(S state) {
        return Math.max(0.0, state.sigma());
    }

    @Override
    public Map<String, Object> observe(S state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("carried", state.carried());
        m.put("waiting", state.queue().size());
        m.put("maxQueue", state.maxQueue());
        m.put("departures", state.departures());
        m.put("capacity", capacity);
        m.put("interval", interval);
        if (shardCount > 1) {
            m.put("fleetPosition", shardIndex + "/" + shardCount);
            m.put("offered", state.seen());
        }
        m.put("clock", state.clock());
        m.put("loadFactor", state.departures() == 0 ? 0.0
                : (double) state.carried() / (state.departures() * (double) capacity));
        return m;
    }
}
