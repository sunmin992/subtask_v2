package org.hanbat.ses.persistence.repository;

import java.util.UUID;

import org.hanbat.ses.persistence.entity.SimulationRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunRepository extends JpaRepository<SimulationRunEntity, UUID> {
}
