package org.hanbat.ses.persistence.adapter;

import java.util.Optional;
import java.util.UUID;

import org.hanbat.ses.persistence.entity.SimulationRunEntity;

import org.hanbat.ses.scenario.model.RunStore;
import org.hanbat.ses.scenario.model.SimulationRun;
import org.hanbat.ses.persistence.repository.RunRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnJpaPersistence
public class JpaRunStore implements RunStore {

    private final RunRepository repository;

    public JpaRunStore(RunRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public SimulationRun save(SimulationRun run) {
        SimulationRunEntity entity = repository.findById(run.runId())
                .orElseGet(() -> new SimulationRunEntity(run.runId()));
        entity.setScenarioId(run.scenarioId());
        entity.setStatus(run.status());
        entity.setProgress(run.progress());
        entity.setResult(run.result());
        entity.setError(run.error());
        entity.setStartedAt(run.startedAt());
        entity.setFinishedAt(run.finishedAt());
        repository.save(entity);
        return run;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SimulationRun> find(UUID runId) {
        return repository.findById(runId).map(e -> new SimulationRun(
                e.getRunId(), e.getScenarioId(), e.getStatus(), e.getProgress(),
                e.getResult(), e.getError(), e.getStartedAt(), e.getFinishedAt()));
    }
}
