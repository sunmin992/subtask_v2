package org.hanbat.ses.scenario.exec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hanbat.ses.devs.engine.SimConfig;
import org.hanbat.ses.devs.engine.SimulationResult;
import org.hanbat.ses.scenario.model.RunStore;
import org.hanbat.ses.scenario.model.Scenario;
import org.hanbat.ses.scenario.model.ScenarioStore;
import org.hanbat.ses.scenario.model.SimulationRun;
import org.hanbat.ses.scenario.output.ResultFormatter;
import org.hanbat.ses.template.model.FailurePolicy;
import org.hanbat.ses.template.model.RunMode;
import org.hanbat.ses.template.model.SimulatorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 시뮬레이션 실행 오케스트레이션.
 *
 * <p>비동기로 도는 이유는 시뮬레이션이 초 단위로 걸릴 수 있어서다. HTTP 요청 스레드를
 * 붙잡고 있으면 타임아웃 설정을 시뮬레이션 시간에 맞춰야 하고, 그러면 느린 시나리오
 * 하나가 서버 전체의 응답 시간을 결정하게 된다.
 */
@Service
public class ScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);

    private final List<ScenarioExecutor> executors;
    private final ScenarioStore scenarios;
    private final RunStore runs;
    private final ResultFormatter formatter;

    public ScenarioRunner(List<ScenarioExecutor> executors, ScenarioStore scenarios,
                          RunStore runs, ResultFormatter formatter) {
        this.executors = executors;
        this.scenarios = scenarios;
        this.runs = runs;
        this.formatter = formatter;
    }

    /** 실행을 큐에 넣고 즉시 돌아온다. 호출부는 202 를 반환하면 된다. */
    public SimulationRun submit(UUID scenarioId) {
        Scenario scenario = scenarios.require(scenarioId);
        SimulationRun run = runs.save(SimulationRun.queued(UUID.randomUUID(), scenario.scenarioId()));
        executeAsync(run.runId(), scenario);
        return run;
    }

    @Async
    public void executeAsync(UUID runId, Scenario scenario) {
        runExecution(runId, scenario);
    }

    /** 동기 실행 경로 — 테스트와 즉시 실행 요청이 쓴다. */
    public SimulationRun runNow(UUID scenarioId) {
        Scenario scenario = scenarios.require(scenarioId);
        SimulationRun run = runs.save(SimulationRun.queued(UUID.randomUUID(), scenarioId));
        return runExecution(run.runId(), scenario);
    }

    private SimulationRun runExecution(UUID runId, Scenario scenario) {
        SimulationRun run = runs.save(runs.require(runId).running());
        try {
            SimulatorConfig cfg = scenario.simConfig();
            ScenarioExecutor executor = pick(cfg.engine());

            Map<String, Object> payload = cfg.mode() == RunMode.SINGLE
                    ? single(scenario, executor, cfg)
                    : replicated(scenario, executor, cfg);

            boolean partial = Boolean.TRUE.equals(payload.get("partial"));
            if (partial && scenario.output().failurePolicy() == FailurePolicy.FAIL_FAST) {
                return runs.save(run.failed(String.valueOf(payload.get("note")), payload));
            }
            return runs.save(run.succeeded(payload));
        } catch (RuntimeException e) {
            log.warn("시뮬레이션 실행 실패 (run={}): {}", runId, e.toString());
            return runs.save(run.failed(e.getMessage(), Map.of()));
        }
    }

    private Map<String, Object> single(Scenario scenario, ScenarioExecutor executor,
                                       SimulatorConfig cfg) {
        SimulationResult result = executor.execute(scenario.pes(), toSimConfig(cfg, cfg.seed()));
        return formatter.format(scenario, result);
    }

    /**
     * 몬테카를로 — 복제마다 시드를 바꿔 돌리고 대표 실행 + 반복별 요약을 함께 낸다.
     *
     * <p>시드를 파생시키는 방식(base + i)을 고정해 두어야 같은 시나리오를 다시 돌렸을 때
     * 같은 결과가 나온다. 재현성이 없으면 반복 실행의 의미가 사라진다.
     */
    private Map<String, Object> replicated(Scenario scenario, ScenarioExecutor executor,
                                           SimulatorConfig cfg) {
        long base = cfg.seed() == null ? 0L : cfg.seed();
        Map<String, Object> representative = null;
        java.util.List<Map<String, Object>> replications = new java.util.ArrayList<>();

        for (int i = 0; i < cfg.replications(); i++) {
            SimulationResult result = executor.execute(scenario.pes(),
                    toSimConfig(cfg, base + i));
            Map<String, Object> formatted = formatter.format(scenario, result);
            if (i == 0) {
                representative = formatted;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("replication", i);
            row.put("seed", base + i);
            row.put("status", result.status().name());
            row.put("statistics", result.statistics());
            replications.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>(
                representative == null ? Map.of() : representative);
        out.put("mode", cfg.mode().name());
        out.put("replications", replications);
        return out;
    }

    private ScenarioExecutor pick(String engine) {
        return executors.stream().filter(e -> e.supports(engine)).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "지원하지 않는 시뮬레이션 엔진입니다: " + engine));
    }

    private SimConfig toSimConfig(SimulatorConfig cfg, Long seed) {
        return new SimConfig(cfg.horizon(), cfg.timeResolution(), seed, cfg.timeoutMs(),
                5_000_000L, 10_000, 5_000);
    }
}
