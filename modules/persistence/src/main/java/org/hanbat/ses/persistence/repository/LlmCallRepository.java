package org.hanbat.ses.persistence.repository;

import java.util.UUID;

import org.hanbat.ses.persistence.entity.LlmCallLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmCallRepository extends JpaRepository<LlmCallLogEntity, UUID> {
}
