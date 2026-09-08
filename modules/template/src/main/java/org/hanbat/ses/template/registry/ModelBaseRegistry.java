package org.hanbat.ses.template.registry;

import java.util.List;
import java.util.Optional;

/** 모델 베이스 등록소. FR-703 이 요구하는 포트·상태변수·파라미터 명세 관리. */
public interface ModelBaseRegistry {

    Optional<ModelBaseEntry> find(String modelId);

    List<ModelBaseEntry> findAll();

    ModelBaseEntry register(ModelBaseEntry entry);
}
