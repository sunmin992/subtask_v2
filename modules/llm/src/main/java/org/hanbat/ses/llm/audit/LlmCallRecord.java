package org.hanbat.ses.llm.audit;

import java.util.UUID;

import org.hanbat.ses.llm.gateway.Purpose;

/**
 * LLM 호출 감사 기록. 재현성과 비용 추적 둘 다에 쓴다.
 *
 * <p>연구용 시스템에서 "그때 그 결과가 왜 나왔는지"를 나중에 되짚으려면
 * 요청 원문과 응답 원문이 남아 있어야 한다.
 */
public record LlmCallRecord(
        UUID callId,
        UUID sessionId,
        Purpose purpose,
        String model,
        String request,
        String response,
        int latencyMs,
        Integer inputTokens,
        Integer outputTokens,
        String error
) {
}
