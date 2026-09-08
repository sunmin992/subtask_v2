package org.hanbat.ses.template.registry;

import java.util.List;
import java.util.Map;

/**
 * 모델 베이스 항목 — SES 리프가 해석될 원자 모델의 명세.
 *
 * <p>실행 가능한 구현체는 여기 없다. 이 항목은 "cable-car 를 쓰려면 어떤 포트와
 * 어떤 파라미터가 필요한가"를 도메인 저작자에게 알려 주는 계약이다.
 * 구현체는 {@code AtomicModelFactory} 가 modelId 로 붙고, 그 연결이 끊겨 있으면
 * 등록 시점에 경고한다 — 명세만 있고 구현이 없는 모델은 시나리오 실행 때 터진다.
 *
 * @param kind      ATOMIC | COUPLED
 * @param ports     {@code {"in": [...], "out": [...]}}
 * @param stateVars 상태 변수 이름 -> 타입
 * @param params    파라미터 이름 -> {type, unit, required, default}
 */
public record ModelBaseEntry(
        String modelId,
        String kind,
        String displayName,
        Map<String, Object> ports,
        Map<String, Object> stateVars,
        Map<String, Object> params
) {

    public ModelBaseEntry {
        kind = kind == null || kind.isBlank() ? "ATOMIC" : kind;
        ports = ports == null ? Map.of() : Map.copyOf(ports);
        stateVars = stateVars == null ? Map.of() : Map.copyOf(stateVars);
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** 필수 파라미터 이름 목록 — 도메인 SES 의 변수와 대조하는 데 쓴다. */
    @SuppressWarnings("unchecked")
    public List<String> requiredParams() {
        return params.entrySet().stream()
                .filter(e -> e.getValue() instanceof Map<?, ?> m
                        && Boolean.TRUE.equals(((Map<String, Object>) m).get("required")))
                .map(Map.Entry::getKey)
                .toList();
    }
}
