package org.hanbat.ses.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hanbat.ses.scenario.model.SimulationRun;

public record RunResponse(
        UUID runId,
        UUID scenarioId,
        String status,
        double progress,
        Map<String, Object> result,
        String error,
        Instant startedAt,
        Instant finishedAt
) {

    public static RunResponse of(SimulationRun run) {
        return new RunResponse(run.runId(), run.scenarioId(), run.status().name(),
                run.progress(), run.result(), run.error(), run.startedAt(), run.finishedAt());
    }
}
