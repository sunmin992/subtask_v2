package org.hanbat.ses.devs.model;

import java.util.List;
import java.util.Map;

/**
 * Zeigler 의 원자 DEVS 정형.
 *
 * <p>상태를 필드로 들고 있지 않고 매번 넘겨받아 새 상태를 돌려주는 함수형 형태다.
 * 이렇게 두면 같은 모델 인스턴스를 여러 복제본이 공유해도 안전하고,
 * 특정 시점의 상태를 스냅샷으로 남기기도 쉽다.
 */
public interface AtomicModel<S> {

    /** 모델 종류 이름. 통계와 트레이스 라벨에 쓴다. */
    String type();

    S initialState();

    S internalTransition(S state);

    S externalTransition(S state, double elapsed, List<Message> input);

    /** 내부 전이와 외부 입력이 같은 시각에 겹칠 때. 기본은 내부 전이 후 외부 입력 처리. */
    default S confluentTransition(S state, List<Message> input) {
        return externalTransition(internalTransition(state), 0.0, input);
    }

    List<Message> output(S state);

    /** 다음 내부 전이까지 남은 시간. POSITIVE_INFINITY 는 수동(passive) 상태. */
    double timeAdvance(S state);

    /** 통계 수집용 관측값. 엔진이 실행 종료 시 모델 id 를 접두사로 붙여 모은다. */
    default Map<String, Object> observe(S state) {
        return Map.of();
    }
}
