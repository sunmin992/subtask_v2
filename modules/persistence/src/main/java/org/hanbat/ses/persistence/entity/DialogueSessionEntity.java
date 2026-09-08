package org.hanbat.ses.persistence.entity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.dialogue.session.Phase;
import org.hanbat.ses.dialogue.session.Question;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 대화 세션.
 *
 * <p>SES 트리와 답변은 깊이가 가변인 중첩 구조라 관계형으로 정규화하면 조회할 때마다
 * 재귀 CTE 를 써야 한다. 정체성과 검색 키만 컬럼으로 빼고 본문은 JSONB 에 넣는다.
 *
 * <p>Hibernate 6 은 {@code @JdbcTypeCode(SqlTypes.JSON)} 으로 JSONB 를 바로 지원하므로
 * 별도 컨버터 라이브러리가 필요 없다. sealed interface 의 폴리모픽 직렬화는
 * {@code SesNode} 에 붙은 Jackson {@code @JsonTypeInfo} 가 처리한다.
 */
@Entity
@Table(name = "dialogue_session")
public class DialogueSessionEntity {

    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "template_id", nullable = false)
    private String templateId;

    @Column(name = "template_ver", nullable = false)
    private String templateVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Phase phase;

    @Column(name = "request_text", columnDefinition = "text")
    private String request;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "working_ses", columnDefinition = "jsonb", nullable = false)
    private SesNode workingSes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> answers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<ValidationIssue> issues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_questions", columnDefinition = "jsonb", nullable = false)
    private List<Question> pendingQuestions;

    /** undo 용 이전 트리들. 상한이 있으므로 무한히 커지지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<SesNode> history;

    @Column(name = "derived_from", columnDefinition = "text")
    private String derivedFrom;

    @Column(name = "turn_count", nullable = false)
    private int turnCount;

    @Column(name = "scenario_id")
    private UUID scenarioId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DialogueSessionEntity() {
    }

    public DialogueSessionEntity(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(String templateVersion) {
        this.templateVersion = templateVersion;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public String getRequest() {
        return request;
    }

    public void setRequest(String request) {
        this.request = request;
    }

    public SesNode getWorkingSes() {
        return workingSes;
    }

    public void setWorkingSes(SesNode workingSes) {
        this.workingSes = workingSes;
    }

    public Map<String, Object> getAnswers() {
        return answers;
    }

    public void setAnswers(Map<String, Object> answers) {
        this.answers = answers;
    }

    public List<ValidationIssue> getIssues() {
        return issues;
    }

    public void setIssues(List<ValidationIssue> issues) {
        this.issues = issues;
    }

    public List<Question> getPendingQuestions() {
        return pendingQuestions;
    }

    public void setPendingQuestions(List<Question> pendingQuestions) {
        this.pendingQuestions = pendingQuestions;
    }

    public List<SesNode> getHistory() {
        return history;
    }

    public void setHistory(List<SesNode> history) {
        this.history = history;
    }

    public String getDerivedFrom() {
        return derivedFrom;
    }

    public void setDerivedFrom(String derivedFrom) {
        this.derivedFrom = derivedFrom;
    }

    public int getTurnCount() {
        return turnCount;
    }

    public void setTurnCount(int turnCount) {
        this.turnCount = turnCount;
    }

    public UUID getScenarioId() {
        return scenarioId;
    }

    public void setScenarioId(UUID scenarioId) {
        this.scenarioId = scenarioId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
