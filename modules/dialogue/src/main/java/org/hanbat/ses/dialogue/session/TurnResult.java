package org.hanbat.ses.dialogue.session;

import java.util.List;

import org.hanbat.ses.core.validate.ValidationIssue;

/**
 * 한 턴의 결과.
 *
 * @param derivedFrom 새 질문이 왜 생겼는지. 파생 슬롯 구조에서는 이 설명이 없으면
 *                    질문이 끝없이 늘어나는 것처럼 느껴진다.
 */
public record TurnResult(
        TurnOutcome outcome,
        SessionState state,
        List<Question> questions,
        List<ValidationIssue> issues,
        String derivedFrom,
        List<org.hanbat.ses.dialogue.routing.TemplateCandidate> candidates
) {

    public TurnResult(TurnOutcome outcome, SessionState state, List<Question> questions,
                      List<ValidationIssue> issues, String derivedFrom) {
        this(outcome, state, questions, issues, derivedFrom, List.of());
    }

    public TurnResult {
        questions = questions == null ? List.of() : List.copyOf(questions);
        issues = issues == null ? List.of() : List.copyOf(issues);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /**
     * 템플릿을 확정할 수 없어 사용자에게 고르게 한다 (FR-104).
     *
     * <p>세션은 아직 없다 — 클라이언트가 templateId 를 실어 다시 요청하면 그때 만들어진다.
     */
    public static TurnResult chooseTemplate(
            List<org.hanbat.ses.dialogue.routing.TemplateCandidate> candidates, String reason) {
        return new TurnResult(TurnOutcome.CHOOSE_TEMPLATE, null, List.of(),
                List.of(ValidationIssue.warning("<routing>", "ROUTING_AMBIGUOUS", reason)),
                reason, candidates);
    }

    public static TurnResult ask(SessionState state, List<Question> questions, String derivedFrom) {
        return new TurnResult(TurnOutcome.ASK, state, questions, state.issues(), derivedFrom);
    }

    public static TurnResult reask(SessionState state, List<Question> questions,
                                   List<ValidationIssue> issues) {
        return new TurnResult(TurnOutcome.REASK, state, questions, issues, null);
    }

    /**
     * 완료.
     *
     * <p>derivedFrom 을 그대로 실어 보낸다. 마지막 턴에서 무엇이 자동으로 채워졌는지는
     * 완료 응답에서도 알려 줘야 한다 — 사용자가 말하지 않은 값이 들어갔을 수 있다.
     */
    public static TurnResult complete(SessionState state) {
        return new TurnResult(TurnOutcome.COMPLETE, state, List.of(), state.issues(),
                state.derivedFrom());
    }

    public static TurnResult failed(SessionState state, List<ValidationIssue> issues) {
        return new TurnResult(TurnOutcome.FAILED, state, List.of(), issues, null);
    }
}
