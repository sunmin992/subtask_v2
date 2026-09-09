package org.hanbat.ses.scenario.domain.evcharge;

import org.hanbat.ses.devs.model.AtomicModel;
import org.hanbat.ses.scenario.factory.AtomicModelFactory;
import org.hanbat.ses.scenario.factory.ModelSpec;
import org.springframework.stereotype.Component;

/**
 * 전기차 충전소 도메인의 원자 모델 팩토리 모음.
 *
 * <p>이 파일이 새 도메인을 붙일 때 추가되는 <b>유일한</b> 자바 코드다. SES 트리도, 모델
 * 명세도, 템플릿도 {@code seed/} 아래 JSON 이다. 원자 모델의 거동만은 데이터로 쓸 수 없어
 * 클래스가 필요하고, 그마저 {@link AtomicModelFactory} 구현이면 Spring 이 모아 주므로
 * 등록 코드는 없다.
 *
 * <p>급속기와 완속기가 같은 {@link Charger} 로 귀결되는 이유는 그 클래스에 적어 두었다.
 * 모델 id 를 둘로 나눈 것은 모델 베이스에서 파라미터 범위와 표시 이름이 다르기 때문이고,
 * 그 차이는 데이터에 있어야 한다.
 */
public final class EvChargeModelFactories {

    private EvChargeModelFactories() {
    }

    /** 포아송 도착. 시드는 시나리오가 정한 값을 그대로 쓴다 — 같은 시드면 같은 도착열이다. */
    @Component
    public static class VehicleArrivalFactory implements AtomicModelFactory {

        @Override
        public String modelId() {
            return "vehicle-arrival";
        }

        @Override
        public AtomicModel<?> create(ModelSpec spec) {
            return new VehicleArrival(
                    spec.requireDouble("평균도착간격"),
                    spec.intOr("총차량수", Integer.MAX_VALUE),
                    spec.seed());
        }
    }

    @Component
    public static class FastChargerFactory implements AtomicModelFactory {

        @Override
        public String modelId() {
            return "charger-fast";
        }

        /** 복제본 번호가 곧 충전기 번호다. 배정 메시지는 이 번호로 자기 몫을 가려낸다. */
        @Override
        public AtomicModel<?> create(ModelSpec spec) {
            return new Charger(spec.requireDouble("평균충전시간"),
                    spec.doubleOr("충전출력", 100.0), spec.instanceIndex(), spec.seed());
        }
    }

    @Component
    public static class SlowChargerFactory implements AtomicModelFactory {

        @Override
        public String modelId() {
            return "charger-slow";
        }

        @Override
        public AtomicModel<?> create(ModelSpec spec) {
            return new Charger(spec.requireDouble("평균충전시간"),
                    spec.doubleOr("충전출력", 7.0), spec.instanceIndex(), spec.seed());
        }
    }

    /**
     * 대기 정책이 이 팩토리에서 갈린다.
     *
     * <p>정책 변형은 SES 의 specialization 으로 표현되고, 여기서는 변형이 남긴 변수를 읽을
     * 뿐이다. 정책이 하나 늘어도 이 코드는 그대로다 — 대기 자리 수가 없으면 무한 대기다.
     */
    @Component
    public static class ChargingQueueFactory implements AtomicModelFactory {

        @Override
        public String modelId() {
            return "queue-transducer";
        }

        @Override
        public AtomicModel<?> create(ModelSpec spec) {
            return new ChargingQueue(
                    spec.doubleOr("관측시간", spec.horizon()),
                    spec.intOr("최대대기대수", Integer.MAX_VALUE));
        }
    }
}
