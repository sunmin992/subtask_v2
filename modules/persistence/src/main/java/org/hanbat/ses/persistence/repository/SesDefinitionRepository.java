package org.hanbat.ses.persistence.repository;

import java.util.List;

import org.hanbat.ses.persistence.entity.SesDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SesDefinitionRepository extends JpaRepository<SesDefinitionEntity, String> {

    List<SesDefinitionEntity> findByDomain(String domain);
}
