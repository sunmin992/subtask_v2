package org.hanbat.ses.persistence.repository;

import java.util.UUID;

import org.hanbat.ses.persistence.entity.DialogueSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<DialogueSessionEntity, UUID> {
}
