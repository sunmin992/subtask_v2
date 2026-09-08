package org.hanbat.ses.persistence.adapter;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.hanbat.ses.persistence.entity.ModelBaseEntity;
import org.hanbat.ses.persistence.repository.ModelBaseRepository;
import org.hanbat.ses.template.registry.ModelBaseEntry;
import org.hanbat.ses.template.registry.ModelBaseRegistry;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 모델 베이스 등록소 JPA 어댑터. */
@Repository
@ConditionalOnJpaPersistence
public class JpaModelBaseRegistry implements ModelBaseRegistry {

    private final ModelBaseRepository repository;

    public JpaModelBaseRegistry(ModelBaseRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModelBaseEntry> find(String modelId) {
        return repository.findById(modelId).map(JpaModelBaseRegistry::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelBaseEntry> findAll() {
        return repository.findAll().stream()
                .map(JpaModelBaseRegistry::toDomain)
                .sorted(Comparator.comparing(ModelBaseEntry::modelId))
                .toList();
    }

    @Override
    @Transactional
    public ModelBaseEntry register(ModelBaseEntry entry) {
        repository.save(new ModelBaseEntity(entry.modelId(), entry.kind(),
                entry.displayName(), entry.ports(), entry.stateVars(), entry.params()));
        return entry;
    }

    private static ModelBaseEntry toDomain(ModelBaseEntity e) {
        return new ModelBaseEntry(e.getModelId(), e.getKind(), e.getDisplayName(),
                e.getPorts(), e.getStateVars(), e.getParams());
    }
}
