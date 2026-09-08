package org.hanbat.ses.persistence.repository;

import java.util.UUID;

import org.hanbat.ses.persistence.entity.ScenarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRepository extends JpaRepository<ScenarioEntity, UUID> {
}
