package org.hanbat.ses.devs.engine;

/**
 * 실행 파라미터.
 *
 * @param horizon         시뮬레이션 종료 시각.
 * @param timeResolution  시간 해상도. 스텝 수 상한 계산과 단위 검증에 쓴다.
 * @param seed            난수 시드. null 이면 비결정적 — 재현성이 필요하면 반드시 지정한다.
 * @param timeoutMs       벽시계 타임아웃. LLM 이 만든 시나리오는 종료하지 않을 수 있다.
 * @param maxEvents       이벤트 수 상한. zeno 가 아니어도 폭주하는 모델을 끊는다.
 * @param zenoLimit       같은 시각에서 허용하는 연속 전이 횟수.
 * @param maxTraceEntries 트레이스 보관 상한. 넘어가면 기록만 멈추고 실행은 계속한다.
 */
public record SimConfig(
        double horizon,
        double timeResolution,
        Long seed,
        long timeoutMs,
        long maxEvents,
        int zenoLimit,
        int maxTraceEntries
) {

    public static SimConfig of(double horizon) {
        return new SimConfig(horizon, 1.0, 42L, 30_000L, 5_000_000L, 10_000, 10_000);
    }

    public SimConfig withHorizon(double newHorizon) {
        return new SimConfig(newHorizon, timeResolution, seed, timeoutMs,
                maxEvents, zenoLimit, maxTraceEntries);
    }

    public long timeoutNanos() {
        return timeoutMs * 1_000_000L;
    }
}
