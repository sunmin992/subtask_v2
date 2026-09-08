package org.hanbat.ses.persistence.adapter;

import java.util.Optional;
import java.util.UUID;

import org.hanbat.ses.core.pes.Pes;
import org.hanbat.ses.persistence.entity.ScenarioEntity;

import org.hanbat.ses.scenario.model.Scenario;
import org.hanbat.ses.scenario.model.ScenarioStore;
import org.hanbat.ses.persistence.repository.ScenarioRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnJpaPersistence
public class JpaScenarioStore implements ScenarioStore {

    private final ScenarioRepository repository;

    public JpaScenarioStore(ScenarioRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Scenario save(Scenario scenario) {
        ScenarioEntity entity = repository.findById(scenario.scenarioId())
                .orElseGet(() -> new ScenarioEntity(scenario.scenarioId()));
        entity.setSessionId(scenario.sessionId());
        entity.setTemplateId(scenario.templateId());
        entity.setPes(scenario.pes().root());
        entity.setParams(scenario.params());
        entity.setSimConfig(scenario.simConfig());
        entity.setOutput(scenario.output());
        entity.setSummary(scenario.summary());
        entity.setCreatedAt(scenario.createdAt());
        repository.save(entity);
        return scenario;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Scenario> find(UUID scenarioId) {
        return repository.findById(scenarioId).map(e -> new Scenario(
                e.getScenarioId(), e.getSessionId(), e.getTemplateId(), new Pes(e.getPes()),
                e.getParams(), e.getSimConfig(), e.getOutput(), e.getSummary(), e.getCreatedAt()));
    }
}
