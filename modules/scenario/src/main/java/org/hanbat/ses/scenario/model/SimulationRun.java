package org.hanbat.ses.scenario.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 실행 한 건.
 *
 * @param result 형식화된 결과. 부분 결과도 여기 담긴다 — 실패했다고 빈 값을 주면
 *               사용자는 무엇이 잘못됐는지 알 방법이 없다.
 */
public record SimulationRun(
        UUID runId,
        UUID scenarioId,
        RunStatus status,
        double progress,
        Map<String, Object> result,
        String error,
        Instant startedAt,
        Instant finishedAt
) {

    public static SimulationRun queued(UUID runId, UUID scenarioId) {
        return new SimulationRun(runId, scenarioId, RunStatus.QUEUED, 0.0, null, null, null, null);
    }

    public SimulationRun running() {
        return new SimulationRun(runId, scenarioId, RunStatus.RUNNING, 0.1, null, null,
                Instant.now(), null);
    }

    public SimulationRun succeeded(Map<String, Object> payload) {
        return new SimulationRun(runId, scenarioId, RunStatus.SUCCEEDED, 1.0, payload, null,
                startedAt, Instant.now());
    }

    public SimulationRun failed(String message, Map<String, Object> partial) {
        return new SimulationRun(runId, scenarioId, RunStatus.FAILED, 1.0, partial, message,
                startedAt, Instant.now());
    }
}
