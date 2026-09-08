package org.hanbat.ses.template.model;

/**
 * 시뮬레이터 설정.
 *
 * <p>seed 를 비워 두면 같은 시나리오가 매번 다른 결과를 낸다. 연구용으로는
 * 재현성이 결과 자체보다 중요할 때가 많으므로 기본값을 넣어 둔다.
 */
public record SimulatorConfig(
        String engine,
        double timeResolution,
        double horizon,
        Long seed,
        RunMode mode,
        int replications,
        long timeoutMs
) {

    public SimulatorConfig {
        if (engine == null || engine.isBlank()) {
            engine = "devs-internal";
        }
        if (timeResolution <= 0) {
            timeResolution = 1.0;
        }
        if (horizon <= 0) {
            horizon = 1440.0;
        }
        if (mode == null) {
            mode = RunMode.SINGLE;
        }
        if (replications <= 0) {
            replications = 1;
        }
        if (timeoutMs <= 0) {
            timeoutMs = 30_000L;
        }
    }

    public static SimulatorConfig defaults() {
        return new SimulatorConfig("devs-internal", 1.0, 1440.0, 42L, RunMode.SINGLE, 1, 30_000L);
    }
}
