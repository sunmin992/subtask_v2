package org.hanbat.ses.persistence.adapter;

import org.hanbat.ses.llm.audit.LlmCallRecord;
import org.hanbat.ses.llm.audit.LlmCallRecorder;
import org.hanbat.ses.persistence.entity.LlmCallLogEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.hanbat.ses.persistence.repository.LlmCallRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * LLM 감사 로그 기록.
 *
 * <p>별도 트랜잭션으로 쓰고 예외를 삼킨다. 감사 로그 저장이 실패했다고
 * 사용자의 요청까지 롤백되면 안 된다 — 로그는 부수 효과지 본 흐름이 아니다.
 */
@Repository
@ConditionalOnJpaPersistence
public class JpaLlmCallRecorder implements LlmCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(JpaLlmCallRecorder.class);

    private final LlmCallRepository repository;

    public JpaLlmCallRecorder(LlmCallRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(LlmCallRecord call) {
        try {
            LlmCallLogEntity entity = new LlmCallLogEntity(call.callId());
            entity.setSessionId(call.sessionId());
            entity.setPurpose(call.purpose());
            entity.setModel(call.model());
            entity.setRequest(call.request());
            entity.setResponse(call.response());
            entity.setLatencyMs(call.latencyMs());
            entity.setInputTokens(call.inputTokens());
            entity.setOutputTokens(call.outputTokens());
            entity.setError(call.error());
            repository.save(entity);
        } catch (RuntimeException e) {
            log.warn("LLM 감사 로그 저장에 실패했습니다: {}", e.getMessage());
        }
    }
}
