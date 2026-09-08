package org.hanbat.ses.scenario.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.devs.model.AtomicModel;
import org.hanbat.ses.devs.model.Message;

/**
 * 객실이 정해진 수만큼 있는 숙박시설.
 *
 * <p>빈 객실이 있으면 투숙하고 평균 숙박시간 뒤에 퇴실한다. 만실이면 거절한다.
 * 거절 수가 이 모델의 존재 이유다 — "객실 200실로 충분한가"가 곧 질문이기 때문이다.
 *
 * <p>퇴실 예정을 정렬된 목록으로 들고 timeAdvance 를 가장 이른 퇴실까지로 잡는다.
 * 투숙객마다 타이머를 두는 대신 이렇게 하면 컴포넌트 하나로 끝난다.
 *
 * <p>퇴실할 때 <b>누가</b> 나가는지를 그대로 실어 보낸다. 상수 문자열을 내보내면
 * 집계기가 도착과 완료를 짝지을 수 없어 평균 체류시간이 0 으로 나오는데,
 * 그건 "체류시간이 0"이 아니라 "측정하지 못했다"는 뜻이라 더 나쁘다.
 */
public final class Lodging implements AtomicModel<Lodging.S> {

    /** 투숙 한 건 — 누가, 언제 나가는지. */
    public record Stay(Object guest, double checkoutAt) {
    }

    /**
     * @param stays         퇴실 예정(시각 오름차순).
     * @param occupancyArea 재실 인원을 시간으로 적분한 값. 평균 재실률 계산에 쓴다.
     */
    public record S(List<Stay> stays, int admitted, int rejected,
                    int maxOccupancy, double clock, double occupancyArea) {
    }

    private final int rooms;
    private final double stayTime;
    private final String inPort;
    private final String outPort;

    public Lodging(int rooms, double stayTime) {
        this(rooms, stayTime, "in", "out");
    }

    public Lodging(int rooms, double stayTime, String inPort, String outPort) {
        if (rooms <= 0) {
            throw new IllegalArgumentException("객실 수는 0보다 커야 합니다: " + rooms);
        }
        if (stayTime <= 0) {
            throw new IllegalArgumentException("숙박 시간은 0보다 커야 합니다: " + stayTime);
        }
        this.rooms = rooms;
        this.stayTime = stayTime;
        this.inPort = inPort;
        this.outPort = outPort;
    }

    @Override
    public String type() {
        return "lodging";
    }

    @Override
    public S initialState() {
        return new S(List.of(), 0, 0, 0, 0.0, 0.0);
    }

    @Override
    public S internalTransition(S state) {
        double next = state.stays().isEmpty() ? state.clock() : state.stays().get(0).checkoutAt();
        double elapsed = next - state.clock();

        // 같은 시각에 퇴실하는 인원을 한꺼번에 내보낸다.
        List<Stay> remaining = new ArrayList<>(state.stays());
        while (!remaining.isEmpty() && remaining.get(0).checkoutAt() <= next) {
            remaining.remove(0);
        }
        return new S(remaining, state.admitted(), state.rejected(),
                state.maxOccupancy(), next,
                state.occupancyArea() + state.stays().size() * elapsed);
    }

    @Override
    public S externalTransition(S state, double elapsed, List<Message> input) {
        double clock = state.clock() + elapsed;
        List<Stay> stays = new ArrayList<>(state.stays());
        int admitted = state.admitted();
        int rejected = state.rejected();

        for (Message m : input) {
            if (!m.port().equals(inPort)) {
                continue;
            }
            if (stays.size() < rooms) {
                stays.add(new Stay(m.value(), clock + stayTime));
                admitted++;
            } else {
                rejected++;
            }
        }
        stays.sort(Comparator.comparingDouble(Stay::checkoutAt));
        return new S(stays, admitted, rejected,
                Math.max(state.maxOccupancy(), stays.size()), clock,
                state.occupancyArea() + state.stays().size() * elapsed);
    }

    @Override
    public List<Message> output(S state) {
        if (state.stays().isEmpty()) {
            return List.of();
        }
        double next = state.stays().get(0).checkoutAt();
        List<Message> out = new ArrayList<>();
        for (Stay stay : state.stays()) {
            if (stay.checkoutAt() > next) {
                break;
            }
            out.add(new Message(outPort, stay.guest()));
        }
        return out;
    }

    @Override
    public double timeAdvance(S state) {
        if (state.stays().isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0.0, state.stays().get(0).checkoutAt() - state.clock());
    }

    @Override
    public Map<String, Object> observe(S state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rooms", rooms);
        m.put("admitted", state.admitted());
        m.put("rejected", state.rejected());
        m.put("occupied", state.stays().size());
        m.put("maxOccupancy", state.maxOccupancy());
        int total = state.admitted() + state.rejected();
        m.put("rejectionRate", total == 0 ? 0.0 : (double) state.rejected() / total);
        m.put("avgOccupancy", state.clock() > 0 ? state.occupancyArea() / state.clock() : 0.0);
        return m;
    }
}
