package org.hanbat.ses.scenario.model;

import java.util.Optional;
import java.util.UUID;

public interface RunStore {

    SimulationRun save(SimulationRun run);

    Optional<SimulationRun> find(UUID runId);

    default SimulationRun require(UUID runId) {
        return find(runId).orElseThrow(() -> new RunNotFoundException(runId));
    }
}
