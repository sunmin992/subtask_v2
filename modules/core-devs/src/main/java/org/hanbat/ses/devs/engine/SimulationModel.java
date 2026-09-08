package org.hanbat.ses.devs.engine;

import java.util.List;

import org.hanbat.ses.devs.model.Coupling;
import org.hanbat.ses.devs.model.ModelRef;

/**
 * 평탄화된 실행 단위 — 원자 컴포넌트 목록과 그 사이의 배선.
 *
 * <p>계층 결합 모델은 실행 전에 여기로 접힌다. root-coordinator 알고리즘을
 * 계층 그대로 구현하면 코드가 두 배가 되는데, 이 프로토타입에서 얻는 것이 없다.
 * 계층 정보는 PES 에 남아 있으므로 결과를 다시 계층으로 접어 보고할 수도 있다.
 *
 * @param externalOutputs 루트 밖으로 나가는 포트. 여기 실린 메시지는 결과에 모인다.
 */
public record SimulationModel(
        String id,
        List<ModelRef> components,
        List<Coupling> couplings,
        List<Coupling> externalOutputs
) {

    public SimulationModel {
        components = components == null ? List.of() : List.copyOf(components);
        couplings = couplings == null ? List.of() : List.copyOf(couplings);
        externalOutputs = externalOutputs == null ? List.of() : List.copyOf(externalOutputs);
    }

    public static SimulationModel of(String id, List<ModelRef> components, List<Coupling> couplings) {
        return new SimulationModel(id, components, couplings, List.of());
    }
}
