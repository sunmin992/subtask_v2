package org.hanbat.ses.scenario.model;

import java.util.Optional;
import java.util.UUID;

public interface ScenarioStore {

    Scenario save(Scenario scenario);

    Optional<Scenario> find(UUID scenarioId);

    default Scenario require(UUID scenarioId) {
        return find(scenarioId).orElseThrow(() -> new ScenarioNotFoundException(scenarioId));
    }
}
