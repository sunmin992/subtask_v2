package org.hanbat.ses.scenario.model;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** DB 없이 도는 시나리오/실행 저장소. standalone 프로파일과 테스트가 쓴다. */
public final class InMemoryStores {

    private InMemoryStores() {
    }

    public static final class Scenarios implements ScenarioStore {

        private final Map<UUID, Scenario> map = new ConcurrentHashMap<>();

        @Override
        public Scenario save(Scenario scenario) {
            map.put(scenario.scenarioId(), scenario);
            return scenario;
        }

        @Override
        public Optional<Scenario> find(UUID scenarioId) {
            return Optional.ofNullable(map.get(scenarioId));
        }
    }

    public static final class Runs implements RunStore {

        private final Map<UUID, SimulationRun> map = new ConcurrentHashMap<>();

        @Override
        public SimulationRun save(SimulationRun run) {
            map.put(run.runId(), run);
            return run;
        }

        @Override
        public Optional<SimulationRun> find(UUID runId) {
            return Optional.ofNullable(map.get(runId));
        }
    }
}
