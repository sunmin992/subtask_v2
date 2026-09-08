package org.hanbat.ses.devs.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hanbat.ses.devs.model.AtomicModel;
import org.hanbat.ses.devs.model.Coupling;
import org.hanbat.ses.devs.model.Message;
import org.hanbat.ses.devs.model.ModelRef;

/**
 * Parallel-DEVS root coordinator.
 *
 * <p>매 반복에서 가장 이른 내부 전이 시각을 찾고, 그 시각의 imminent 컴포넌트가 낸 출력을
 * 배선을 따라 배달한 뒤, 컴포넌트마다 내부/외부/합류 전이 중 하나를 적용한다.
 * 같은 시각에 여러 컴포넌트가 동시에 전이하는 경우를 정식으로 다루므로
 * 실행 순서에 따라 결과가 달라지지 않는다 — 재현성이 필요한 시스템에서는 이 점이 중요하다.
 *
 * <p>루프 안의 세 가지 안전장치(타임아웃, 이벤트 상한, zeno 검출)를 빼면 안 된다.
 * 파라미터를 대화로 받는 이상 "간격 0분" 같은 값이 언제든 들어올 수 있고,
 * 그 한 번이 서버 스레드를 영원히 붙잡는다.
 */
public final class Coordinator {

    public SimulationResult run(SimulationModel model, SimConfig cfg) {
        State[] states = init(model);
        Map<String, Integer> indexOf = indexOf(model);

        List<TraceEntry> trace = new ArrayList<>();
        List<Message> externalOut = new ArrayList<>();
        long startNs = System.nanoTime();

        double t = 0.0;
        double previousT = Double.NEGATIVE_INFINITY;
        int sameTimeStreak = 0;
        long events = 0;
        RunStatus status = RunStatus.COMPLETED;
        String note = null;

        while (true) {
            double tN = nextEventTime(states);
            if (Double.isInfinite(tN)) {
                status = RunStatus.QUIESCENT;
                note = "시각 " + t + " 이후 예정된 이벤트가 없어 조기 종료했습니다.";
                break;
            }
            if (tN > cfg.horizon()) {
                t = cfg.horizon();
                break;
            }
            t = tN;

            if (t == previousT) {
                sameTimeStreak++;
                if (sameTimeStreak >= cfg.zenoLimit()) {
                    status = RunStatus.ZENO;
                    note = new ZenoBehaviorException(t, firstImminent(states, t), cfg.zenoLimit())
                            .getMessage();
                    break;
                }
            } else {
                sameTimeStreak = 0;
                previousT = t;
            }

            if (++events > cfg.maxEvents()) {
                status = RunStatus.EVENT_LIMIT;
                note = "이벤트 수가 상한 " + cfg.maxEvents() + " 을 넘어 중단했습니다.";
                break;
            }
            if (System.nanoTime() - startNs > cfg.timeoutNanos()) {
                status = RunStatus.TIMED_OUT;
                note = new SimulationTimeoutException(t, cfg.timeoutMs()).getMessage();
                break;
            }

            Set<String> imminents = imminentIds(states, t);
            Map<String, List<Message>> inbox = collectAndRoute(
                    model, states, indexOf, t, externalOut);
            applyTransitions(states, inbox, t);

            if (trace.size() < cfg.maxTraceEntries()) {
                trace.add(new TraceEntry(t, List.copyOf(imminents), copyOf(inbox)));
            }
        }

        return new SimulationResult(status, t, events,
                statistics(model, states, events, t), trace, externalOut, note);
    }

    // ---------------------------------------------------------------- 내부

    /** 컴포넌트 하나의 실행 상태. 모델의 타입 파라미터는 여기서 지운다. */
    private static final class State {
        final String id;
        final AtomicModel<Object> model;
        Object value;
        double tL;
        double tN;
        long internalCount;
        long externalCount;

        @SuppressWarnings("unchecked")
        State(ModelRef ref) {
            this.id = ref.id();
            this.model = (AtomicModel<Object>) ref.model();
            this.value = model.initialState();
            this.tL = 0.0;
            this.tN = model.timeAdvance(value);
        }
    }

    private State[] init(SimulationModel model) {
        List<ModelRef> refs = model.components();
        State[] states = new State[refs.size()];
        for (int i = 0; i < refs.size(); i++) {
            states[i] = new State(refs.get(i));
        }
        return states;
    }

    private Map<String, Integer> indexOf(SimulationModel model) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < model.components().size(); i++) {
            map.put(model.components().get(i).id(), i);
        }
        return map;
    }

    private double nextEventTime(State[] states) {
        double min = Double.POSITIVE_INFINITY;
        for (State s : states) {
            if (s.tN < min) {
                min = s.tN;
            }
        }
        return min;
    }

    private Set<String> imminentIds(State[] states, double t) {
        Set<String> out = new LinkedHashSet<>();
        for (State s : states) {
            if (s.tN == t) {
                out.add(s.id);
            }
        }
        return out;
    }

    private String firstImminent(State[] states, double t) {
        for (State s : states) {
            if (s.tN == t) {
                return s.id;
            }
        }
        return "<none>";
    }

    /** imminent 컴포넌트의 출력을 모아 배선을 따라 배달한다. */
    private Map<String, List<Message>> collectAndRoute(SimulationModel model,
                                                       State[] states,
                                                       Map<String, Integer> indexOf,
                                                       double t,
                                                       List<Message> externalOut) {
        Map<String, List<Message>> emitted = new LinkedHashMap<>();
        for (State s : states) {
            if (s.tN == t) {
                List<Message> out = s.model.output(s.value);
                if (out != null && !out.isEmpty()) {
                    emitted.put(s.id, out);
                }
            }
        }

        Map<String, List<Message>> inbox = new LinkedHashMap<>();
        for (Coupling c : model.couplings()) {
            List<Message> src = emitted.get(c.fromModel());
            if (src == null || !indexOf.containsKey(c.toModel())) {
                continue;
            }
            for (Message m : src) {
                if (m.port().equals(c.fromPort())) {
                    inbox.computeIfAbsent(c.toModel(), k -> new ArrayList<>())
                            .add(new Message(c.toPort(), m.value()));
                }
            }
        }
        for (Coupling c : model.externalOutputs()) {
            List<Message> src = emitted.get(c.fromModel());
            if (src == null) {
                continue;
            }
            for (Message m : src) {
                if (m.port().equals(c.fromPort())) {
                    externalOut.add(new Message(c.toPort(), m.value()));
                }
            }
        }
        return inbox;
    }

    private void applyTransitions(State[] states, Map<String, List<Message>> inbox, double t) {
        for (State s : states) {
            boolean imminent = s.tN == t;
            List<Message> input = inbox.get(s.id);
            boolean hasInput = input != null && !input.isEmpty();

            if (imminent && hasInput) {
                s.value = s.model.confluentTransition(s.value, input);
                s.internalCount++;
                s.externalCount++;
            } else if (imminent) {
                s.value = s.model.internalTransition(s.value);
                s.internalCount++;
            } else if (hasInput) {
                s.value = s.model.externalTransition(s.value, t - s.tL, input);
                s.externalCount++;
            } else {
                continue;
            }
            s.tL = t;
            double ta = s.model.timeAdvance(s.value);
            s.tN = Double.isInfinite(ta) ? Double.POSITIVE_INFINITY : t + ta;
        }
    }

    private Map<String, Object> statistics(SimulationModel model, State[] states,
                                           long events, double endTime) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("engine.eventCount", events);
        stats.put("engine.endTime", endTime);
        stats.put("engine.componentCount", states.length);
        for (State s : states) {
            stats.put(s.id + ".internalTransitions", s.internalCount);
            stats.put(s.id + ".externalTransitions", s.externalCount);
            s.model.observe(s.value).forEach((k, v) -> stats.put(s.id + "." + k, v));
        }
        return stats;
    }

    private static Map<String, List<Message>> copyOf(Map<String, List<Message>> inbox) {
        Map<String, List<Message>> out = new LinkedHashMap<>();
        inbox.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }
}
