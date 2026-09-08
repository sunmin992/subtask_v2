package org.hanbat.ses.devs.engine;

/**
 * 같은 시각에서 전이가 끝없이 반복될 때.
 *
 * <p>timeAdvance 가 0 으로 수렴하는 모델은 시뮬레이션 시각을 전혀 진행시키지 못한 채
 * 서버를 붙잡는다. 사람이 만든 모델에서도 나오지만, 파라미터를 대화로 받는 이 시스템에서는
 * 특히 잘 생긴다 — "운행 간격 0분" 같은 답변 하나면 충분하다.
 */
public class ZenoBehaviorException extends RuntimeException {

    private final double simulationTime;

    public ZenoBehaviorException(double simulationTime, String modelId, int limit) {
        super("시각 " + simulationTime + " 에서 " + limit
                + "회 연속 전이가 발생했습니다 (모델 " + modelId
                + "). timeAdvance 가 0 으로 수렴하는지 확인하세요.");
        this.simulationTime = simulationTime;
    }

    public double simulationTime() {
        return simulationTime;
    }
}
