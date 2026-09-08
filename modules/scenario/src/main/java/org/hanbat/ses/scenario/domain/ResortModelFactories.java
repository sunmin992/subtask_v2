package org.hanbat.ses.scenario.domain;

import org.hanbat.ses.devs.classic.Generator;
import org.hanbat.ses.devs.classic.Processor;
import org.hanbat.ses.devs.classic.Transducer;
import org.hanbat.ses.devs.model.AtomicModel;
import org.hanbat.ses.scenario.factory.AtomicModelFactory;
import org.hanbat.ses.scenario.factory.ModelSpec;
import org.springframework.stereotype.Component;

/**
 * 리조트 도메인의 원자 모델 팩토리 모음.
 *
 * <p>새 도메인을 붙일 때 이 파일을 고칠 필요는 없다. {@link AtomicModelFactory} 구현을
 * 하나 추가하고 model_base 에 행을 넣으면 끝이다 — 확장 축을 코드 수정 없이 열어 두는 것이
 * 이 구조의 목적이다.
 *
 * <p>이동설비 세 종류는 모두 {@link BatchTransport} 로 귀결된다. 케이블카와 모노레일이
 * 실제로 다르게 움직이는 지점(가속, 편성, 회차)은 이 프로토타입의 관심사가 아니고,
 * 다르게 만들어 두면 파라미터 이름만 다른 코드 세 벌을 유지해야 한다.
 */
public final class ResortModelFactories {

    private ResortModelFactories() {
    }

    /** 방문객 도착 — 고전 Generator 를 그대로 쓴다. */
    @Component
    public static class VisitorGeneratorFactory implements AtomicModelFactory {

        @Override
        public String modelId() {
            return "visitor-generator";
        }

        @Override
        public AtomicModel<?> create(ModelSpec spec) {
            return new Generator(spec.requireDouble("도착간격"),
                    spec.intOr("총방문객", Integer.MAX_VALUE), "visitor");
        }
    }

    @Component
    public static class CableCarFactory implements AtomicModelFactory {

        @Override
        public String modelId() {
            return "cable-car";
        }

        @Override
        public AtomicModel<?> create(ModelSpec spec) {
            return new BatchTransport(spec.requireDouble("운행간격"), spec.requireInt("정원"));
        }
    }

    @Component
    public static class ShuttleBusFactory implements AtomicModelFactory {

        @Override
        public String modelId() {
            return "shuttle-bus";
        }

        /** 편성 중 몇 번째인지 알려 줘야 세 대가 같은 승객을 각각 태우지 않는다. */
        @Override
        public AtomicModel<?> create(ModelSpec spec) {
            return new BatchTransport(spec.requireDouble("왕복시간"), spec.requireInt("정원"),
                    spec.instanceIndex(), spec.instanceCount());
        }
    }

    @Component
    public static class MonorailFactory implements AtomicModelFactory {

        @Override
        public String modelId() {
            return "monorail";
        }

        @Override
        public AtomicModel<?> create(ModelSpec spec) {
            return new BatchTransport(spec.requireDouble("배차간격"), spec.requireInt("정원"));
        }
    }

    @Component
    public static class LodgingFactory implements AtomicModelFactory {

        @Override
        public String modelId() {
            return "lodging";
        }

        @Override
        public AtomicModel<?> create(ModelSpec spec) {
            return new Lodging(spec.requireInt("객실수"), spec.requireDouble("평균숙박시간"));
        }
    }

    /** 관측 시간은 시뮬레이션 기간 전체로 잡는다. */
    @Component
    public static class TransducerFactory implements AtomicModelFactory {

        @Override
        public String modelId() {
            return "transducer";
        }

        @Override
        public AtomicModel<?> create(ModelSpec spec) {
            return new Transducer(spec.doubleOr("관측시간", spec.horizon()), "arrive", "done");
        }
    }

    /** 고전 예제용 — 단일 처리기. 도메인 모델을 붙이기 전 배선 확인에 쓴다. */
    @Component
    public static class ProcessorFactory implements AtomicModelFactory {

        @Override
        public String modelId() {
            return "processor";
        }

        @Override
        public AtomicModel<?> create(ModelSpec spec) {
            return new Processor(spec.requireDouble("처리시간"));
        }
    }
}
