package org.hanbat.ses.devs.model;

import java.util.List;

/**
 * 결합 모델. 계층 구조는 실행 직전에 평탄화되므로
 * 엔진은 컴포넌트 목록과 배선만 있으면 된다.
 */
public record CoupledModel(
        String id,
        List<ModelRef> components,
        List<Coupling> couplings
) {

    public CoupledModel {
        components = components == null ? List.of() : List.copyOf(components);
        couplings = couplings == null ? List.of() : List.copyOf(couplings);
    }
}
