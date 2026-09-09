package org.hanbat.ses.scenario.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hanbat.ses.core.pes.Pes;
import org.hanbat.ses.template.model.OutputSpec;
import org.hanbat.ses.template.model.SimulatorConfig;

/**
 * 확정된 시나리오 — 대화의 산출물이자 실행의 입력.
 *
 * @param params  평탄화된 파라미터. 사람이 읽는 요약과 감사용.
 * @param summary "리조트 1개(호텔 200실 + 케이블카 8인승 3분 간격)를 24시간 시뮬레이션합니다."
 */
public record Scenario(
        UUID scenarioId,
        UUID sessionId,
        String templateId,
        Pes pes,
        Map<String, Object> params,
        SimulatorConfig simConfig,
        OutputSpec output,
        String summary,
        Instant createdAt,
        Map<String, Object> dataEvidence
) {

    public Scenario {
        params = params == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(params));
        dataEvidence = dataEvidence == null ? Map.of() : Map.copyOf(dataEvidence);
    }

    public Scenario(UUID scenarioId, UUID sessionId, String templateId, Pes pes, Map<String, Object> params,
                    SimulatorConfig simConfig, OutputSpec output, String summary, Instant createdAt) {
        this(scenarioId, sessionId, templateId, pes, params, simConfig, output, summary, createdAt, Map.of());
    }

    public Scenario withDataEvidence(Map<String, Object> evidence) {
        return new Scenario(scenarioId, sessionId, templateId, pes, params, simConfig, output, summary, createdAt, evidence);
    }
}
