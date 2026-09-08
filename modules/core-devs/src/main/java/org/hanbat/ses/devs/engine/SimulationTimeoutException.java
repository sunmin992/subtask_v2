package org.hanbat.ses.devs.engine;

/** 벽시계 타임아웃. 부분 결과를 함께 실어 보낸다. */
public class SimulationTimeoutException extends RuntimeException {

    private final double simulationTime;

    public SimulationTimeoutException(double simulationTime, long timeoutMs) {
        super("시뮬레이션이 " + timeoutMs + "ms 안에 끝나지 않았습니다. (시뮬레이션 시각 "
                + simulationTime + " 에서 중단)");
        this.simulationTime = simulationTime;
    }

    public double simulationTime() {
        return simulationTime;
    }
}
