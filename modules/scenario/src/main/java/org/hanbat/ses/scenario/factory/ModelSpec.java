package org.hanbat.ses.scenario.factory;

import java.util.Map;
import java.util.Optional;

import org.hanbat.ses.core.validate.ValueCoercion;

/**
 * 원자 모델 하나를 만들 때 넘어가는 것.
 *
 * <p>파라미터 조회 헬퍼를 여기 모아 둔다. 모델 구현마다 캐스팅과 기본값 처리를
 * 다시 쓰면 그중 하나는 반드시 다르게 동작한다.
 *
 * <p>instanceIndex/instanceCount 는 multi-aspect 복제본이 자기가 몇 번째인지,
 * 몇 개 중 하나인지 알려 준다. DEVS 에서 결합 모델의 입력 포트를 N 개 컴포넌트에 잇는
 * EIC 는 <b>브로드캐스트</b>다 — 승객 한 명이 버스 세 대에 모두 실린다.
 * 부하를 나눠 가지려면 각 복제본이 자기 몫만 골라내야 하고, 그러려면 이 두 값이 필요하다.
 */
public record ModelSpec(
        String entityId,
        String name,
        Map<String, Object> params,
        double horizon,
        Long seed,
        int instanceIndex,
        int instanceCount
) {

    public static ModelSpec single(String entityId, String name, Map<String, Object> params,
                                   double horizon, Long seed) {
        return new ModelSpec(entityId, name, params, horizon, seed, 0, 1);
    }

    /** 복제본 중 하나인가. multi-aspect 로 늘어난 컴포넌트만 true. */
    public boolean isReplica() {
        return instanceCount > 1;
    }

    public double requireDouble(String key) {
        return doubleValue(key).orElseThrow(() -> missing(key));
    }

    public int requireInt(String key) {
        return intValue(key).orElseThrow(() -> missing(key));
    }

    public Optional<Double> doubleValue(String key) {
        return ValueCoercion.asNumber(params.get(key));
    }

    public Optional<Integer> intValue(String key) {
        return ValueCoercion.asNumber(params.get(key)).map(Double::intValue);
    }

    public double doubleOr(String key, double fallback) {
        return doubleValue(key).orElse(fallback);
    }

    public int intOr(String key, int fallback) {
        return intValue(key).orElse(fallback);
    }

    private IllegalStateException missing(String key) {
        return new IllegalStateException(
                name + "(" + entityId + ") 의 파라미터 " + key + " 가 없습니다.");
    }
}
