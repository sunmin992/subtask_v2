package org.hanbat.ses.persistence.adapter;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.hanbat.ses.persistence.entity.SubtaskTemplateEntity;

import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hanbat.ses.template.registry.TemplateRegistry;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.hanbat.ses.persistence.repository.TemplateRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 템플릿 등록소 JPA 어댑터.
 *
 * <p>템플릿은 매 턴 읽히지만 거의 바뀌지 않는다. 캐시하지 않으면 대화 한 번에
 * 같은 행을 수십 번 읽는다.
 */
@Repository
@ConditionalOnJpaPersistence
public class JpaTemplateRegistry implements TemplateRegistry {

    private final TemplateRepository repository;

    public JpaTemplateRegistry(TemplateRepository repository) {
        this.repository = repository;
    }

    @Override
    @Cacheable(cacheNames = "templates", key = "'active:' + #id")
    @Transactional(readOnly = true)
    public Optional<SubtaskTemplate> findActive(String id) {
        return repository.findByIdAndActiveTrue(id).stream()
                // 같은 id 로 활성 버전이 여럿이면 가장 최근 등록분을 쓴다.
                .max(Comparator.comparing(SubtaskTemplateEntity::getCreatedAt))
                .map(SubtaskTemplateEntity::getSpec);
    }

    @Override
    @Cacheable(cacheNames = "templates", key = "#id + '@' + #version")
    @Transactional(readOnly = true)
    public Optional<SubtaskTemplate> find(String id, String version) {
        if (version == null) {
            return findActive(id);
        }
        return repository.findByIdAndVersion(id, version).map(SubtaskTemplateEntity::getSpec);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubtaskTemplate> findAllActive() {
        return repository.findByActiveTrue().stream()
                .map(SubtaskTemplateEntity::getSpec)
                .sorted(Comparator.comparingInt((SubtaskTemplate t) -> t.routing().priority())
                        .reversed()
                        .thenComparing(SubtaskTemplate::id))
                .toList();
    }

    @Override
    @CacheEvict(cacheNames = "templates", allEntries = true)
    @Transactional
    public SubtaskTemplate register(SubtaskTemplate template) {
        SubtaskTemplateEntity entity = repository
                .findByIdAndVersion(template.id(), template.version())
                .orElseGet(() -> new SubtaskTemplateEntity(template));
        entity.setSpec(template);
        entity.setActive(true);
        repository.save(entity);
        return template;
    }
}
