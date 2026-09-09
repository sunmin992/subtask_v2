package org.hanbat.ses.api.dto;

import java.util.Map;
import java.util.UUID;

import org.hanbat.ses.core.pes.PesNode;

public record ScenarioResponse(
        UUID scenarioId,
        UUID sessionId,
        String templateId,
        String summary,
        PesNode pes,
        Map<String, Object> params,
        Map<String, Object> simConfig,
        Map<String, Object> dataEvidence
) {
}
