package org.hanbat.ses.devs.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.devs.model.Message;

/**
 * 실행 결과.
 *
 * @param status     정상 종료인지, 왜 잘렸는지.
 * @param endTime    마지막으로 처리한 이벤트 시각.
 * @param eventCount 처리한 이벤트(시각) 수.
 * @param statistics 컴포넌트별 관측값 + 엔진 카운터.
 * @param trace      이벤트 기록. maxTraceEntries 를 넘으면 잘린다.
 * @param outputs    루트 밖으로 나간 메시지.
 * @param note       사용자에게 보여줄 부연. 타임아웃/zeno 사유가 여기 담긴다.
 */
public record SimulationResult(
        RunStatus status,
        double endTime,
        long eventCount,
        Map<String, Object> statistics,
        List<TraceEntry> trace,
        List<Message> outputs,
        String note
) {

    public SimulationResult {
        statistics = statistics == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(statistics));
        trace = trace == null ? List.of() : List.copyOf(trace);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }

    public boolean succeeded() {
        return status == RunStatus.COMPLETED;
    }

    /** 부분 결과인가 — 실패 정책이 PARTIAL_RESULT 일 때 이 값으로 분기한다. */
    public boolean partial() {
        return status == RunStatus.TIMED_OUT
                || status == RunStatus.EVENT_LIMIT
                || status == RunStatus.ZENO;
    }
}
