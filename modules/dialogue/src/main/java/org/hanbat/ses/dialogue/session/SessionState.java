package org.hanbat.ses.dialogue.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.validate.ValidationIssue;

/**
 * 세션 상태.
 *
 * <p>workingSes 가 세션의 유일한 진짜 상태다. answers 는 이력으로 남기지만,
 * 실제 진행 상황은 트리가 얼마나 가지치기되었는지로 판단한다. 두 곳에서 진행을
 * 추적하면 반드시 어긋나고, 그때 믿을 것은 트리 쪽이다.
 *
 * @param history 직전 트리들. undo 한 번이 트리 하나 되돌리기로 끝나게 해 준다.
 */
public record SessionState(
        UUID sessionId,
        String templateId,
        String templateVersion,
        Phase phase,
        String request,
        SesNode workingSes,
        Map<String, Object> answers,
        int turnCount,
        List<ValidationIssue> issues,
        List<Question> pendingQuestions,
        String derivedFrom,
        List<SesNode> history,
        UUID scenarioId,
        Instant createdAt,
        Instant updatedAt
) {

    /** undo 를 위해 보관하는 트리 개수. 무한히 쌓으면 세션 행이 비대해진다. */
    public static final int MAX_HISTORY = 10;

    public SessionState {
        answers = answers == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        issues = issues == null ? List.of() : List.copyOf(issues);
        pendingQuestions = pendingQuestions == null ? List.of() : List.copyOf(pendingQuestions);
        history = history == null ? List.of() : List.copyOf(history);
    }

    public static SessionState start(UUID id, String templateId, String templateVersion,
                                     String request, SesNode ses) {
        Instant now = Instant.now();
        return new SessionState(id, templateId, templateVersion, Phase.ASSEMBLING, request,
                ses, Map.of(), 0, List.of(), List.of(), null, List.of(), null, now, now);
    }

    public SessionState advance(SesNode newSes, Phase newPhase,
                                Map<String, Object> newAnswers,
                                List<Question> questions,
                                List<ValidationIssue> newIssues,
                                String derivedExplanation) {
        List<SesNode> newHistory = new ArrayList<>(history);
        newHistory.add(workingSes);
        while (newHistory.size() > MAX_HISTORY) {
            newHistory.remove(0);
        }
        Map<String, Object> merged = new LinkedHashMap<>(answers);
        if (newAnswers != null) {
            merged.putAll(newAnswers);
        }
        return new SessionState(sessionId, templateId, templateVersion, newPhase, request,
                newSes, merged, turnCount + 1, newIssues, questions, derivedExplanation,
                newHistory, scenarioId, createdAt, Instant.now());
    }

    /** 트리를 바꾸지 않고 같은 질문을 다시 낸다 — 턴 수는 늘지만 이력은 남기지 않는다. */
    public SessionState reask(List<Question> questions, List<ValidationIssue> newIssues) {
        return new SessionState(sessionId, templateId, templateVersion, Phase.ELICITING, request,
                workingSes, answers, turnCount + 1, newIssues, questions, derivedFrom,
                history, scenarioId, createdAt, Instant.now());
    }

    public SessionState withPhase(Phase newPhase) {
        return new SessionState(sessionId, templateId, templateVersion, newPhase, request,
                workingSes, answers, turnCount, issues, pendingQuestions, derivedFrom,
                history, scenarioId, createdAt, Instant.now());
    }

    public SessionState withScenario(UUID newScenarioId) {
        return new SessionState(sessionId, templateId, templateVersion, phase, request,
                workingSes, answers, turnCount, issues, pendingQuestions, derivedFrom,
                history, newScenarioId, createdAt, Instant.now());
    }

    public SessionState withQuestions(List<Question> questions) {
        return new SessionState(sessionId, templateId, templateVersion, phase, request,
                workingSes, answers, turnCount, issues, questions, derivedFrom,
                history, scenarioId, createdAt, Instant.now());
    }

    /** 직전 턴으로 되돌린다. 되돌릴 이력이 없으면 그대로 둔다. */
    public SessionState undo() {
        if (history.isEmpty()) {
            return this;
        }
        List<SesNode> newHistory = new ArrayList<>(history);
        SesNode previous = newHistory.remove(newHistory.size() - 1);
        return new SessionState(sessionId, templateId, templateVersion, Phase.ELICITING, request,
                previous, answers, Math.max(0, turnCount - 1), List.of(), List.of(),
                "직전 턴으로 되돌렸습니다.", newHistory, null, createdAt, Instant.now());
    }

    public boolean canUndo() {
        return !history.isEmpty();
    }
}
