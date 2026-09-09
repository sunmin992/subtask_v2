package org.hanbat.ses.scenario.domain.evcharge;

/**
 * 대기열이 특정 충전기에게 보내는 배정 지시.
 *
 * <p>수신자를 값에 적는 이유는 DEVS 의 EIC 가 <b>브로드캐스트</b>이기 때문이다.
 * 결합 모델의 입력 포트를 복제본 n 개에 이으면 메시지 하나가 n 개 모두에게 배달된다.
 * 충전기가 자기 번호를 보고 걸러내지 않으면 차 한 대가 충전기 다섯 대에 동시에 꽂힌다.
 *
 * @param charger 대상 충전기의 복제본 번호. {@code ModelSpec.instanceIndex()} 와 같다.
 */
public record Assignment(int charger, Vehicle vehicle) {
}
