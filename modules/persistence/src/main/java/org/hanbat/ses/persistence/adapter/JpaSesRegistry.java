package org.hanbat.ses.persistence.adapter;

import java.util.List;
import java.util.Optional;

import org.hanbat.ses.persistence.entity.SesDefinitionEntity;

import org.hanbat.ses.template.registry.SesDefinition;
import org.hanbat.ses.template.registry.SesRegistry;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.hanbat.ses.persistence.repository.SesDefinitionRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnJpaPersistence
public class JpaSesRegistry implements SesRegistry {

    private final SesDefinitionRepository repository;

    public JpaSesRegistry(SesDefinitionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Cacheable(cacheNames = "sesDefinitions", key = "#id")
    @Transactional(readOnly = true)
    public Optional<SesDefinition> find(String id) {
        return repository.findById(id).map(JpaSesRegistry::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SesDefinition> findAll() {
        return repository.findAll().stream().map(JpaSesRegistry::toDomain).toList();
    }

    @Override
    @CacheEvict(cacheNames = "sesDefinitions", allEntries = true)
    @Transactional
    public SesDefinition register(SesDefinition definition) {
        repository.save(new SesDefinitionEntity(definition.id(), definition.domain(),
                definition.version(), definition.tree()));
        return definition;
    }

    private static SesDefinition toDomain(SesDefinitionEntity e) {
        return new SesDefinition(e.getId(), e.getDomain(), e.getVersion(), e.getTree());
    }
}
