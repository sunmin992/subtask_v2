package org.hanbat.ses.scenario.factory;

import org.hanbat.ses.devs.model.AtomicModel;

/**
 * PES 리프를 원자 모델로 만든다.
 *
 * <p>새 원자 모델을 추가할 때 고쳐야 할 코드는 이 인터페이스 구현체 하나뿐이다.
 * Spring 이 구현체를 모아 modelId 로 색인하므로 등록 코드도 필요 없다.
 */
public interface AtomicModelFactory {

    /** model_base.model_id 와 같아야 한다. */
    String modelId();

    AtomicModel<?> create(ModelSpec spec);
}
