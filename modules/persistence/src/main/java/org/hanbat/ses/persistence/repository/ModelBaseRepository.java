package org.hanbat.ses.persistence.repository;

import org.hanbat.ses.persistence.entity.ModelBaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelBaseRepository extends JpaRepository<ModelBaseEntity, String> {
}
