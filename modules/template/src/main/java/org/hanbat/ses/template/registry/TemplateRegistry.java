package org.hanbat.ses.template.registry;

import java.util.List;
import java.util.Optional;

import org.hanbat.ses.template.model.SubtaskTemplate;

public interface TemplateRegistry {

    /** 활성 버전 조회. 버전을 지정하지 않은 모든 경로가 이걸 쓴다. */
    Optional<SubtaskTemplate> findActive(String id);

    Optional<SubtaskTemplate> find(String id, String version);

    /** 라우팅 후보 — 활성 템플릿만, priority 내림차순. */
    List<SubtaskTemplate> findAllActive();

    SubtaskTemplate register(SubtaskTemplate template);
}
