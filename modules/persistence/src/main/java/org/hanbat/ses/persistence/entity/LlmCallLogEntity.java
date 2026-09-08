package org.hanbat.ses.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hanbat.ses.llm.gateway.Purpose;

/**
 * LLM 호출 감사 로그.
 *
 * <p>요청/응답을 text 로 둔다. jsonb 로 두면 응답이 JSON 이 아닐 때
 * (파싱 실패가 바로 그 경우다) 저장 자체가 실패해서, 정작 조사해야 할 사례가 남지 않는다.
 */
@Entity
@Table(name = "llm_call_log")
public class LlmCallLogEntity {

    @Id
    @Column(name = "call_id")
    private UUID callId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    private String model;

    @Column(columnDefinition = "text")
    private String request;

    @Column(columnDefinition = "text")
    private String response;

    @Column(name = "latency_ms")
    private int latencyMs;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected LlmCallLogEntity() {
    }

    public LlmCallLogEntity(UUID callId) {
        this.callId = callId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public void setPurpose(Purpose purpose) {
        this.purpose = purpose;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setRequest(String request) {
        this.request = request;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public void setLatencyMs(int latencyMs) {
        this.latencyMs = latencyMs;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    public void setError(String error) {
        this.error = error;
    }

    public UUID getCallId() {
        return callId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
