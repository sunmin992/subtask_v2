package org.hanbat.ses.scenario.domain.evcharge;

/**
 * 충전소를 흐르는 차량 한 대.
 *
 * <p>도착 시각을 싣지 않는다. 원자 모델은 절대 시각을 알지 못하므로(전이 함수가 받는 것은
 * 경과 시간뿐이다) 차량이 스스로 도착 시각을 적을 수 없다. 시각은 자체 시계를 굴리는
 * {@link ChargingQueue} 가 id 를 열쇠로 기록한다.
 */
public record Vehicle(long id) {

    @Override
    public String toString() {
        return "ev-" + id;
    }
}
