package org.hanbat.ses.persistence.repository;

import java.util.List;
import java.util.Optional;

import org.hanbat.ses.persistence.entity.SubtaskTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateRepository
        extends JpaRepository<SubtaskTemplateEntity, SubtaskTemplateEntity.Key> {

    List<SubtaskTemplateEntity> findByActiveTrue();

    List<SubtaskTemplateEntity> findByIdAndActiveTrue(String id);

    Optional<SubtaskTemplateEntity> findByIdAndVersion(String id, String version);
}
