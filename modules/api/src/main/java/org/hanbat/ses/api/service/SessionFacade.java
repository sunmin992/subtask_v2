package org.hanbat.ses.api.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hanbat.ses.api.dto.IssueView;
import org.hanbat.ses.api.dto.ProgressView;
import org.hanbat.ses.api.dto.QuestionView;
import org.hanbat.ses.api.dto.ScenarioResponse;
import org.hanbat.ses.api.dto.SessionResponse;
import org.hanbat.ses.core.resolve.SesProgress;
import org.hanbat.ses.dialogue.control.DialogueController;
import org.hanbat.ses.dialogue.session.Phase;
import org.hanbat.ses.dialogue.session.Question;
import org.hanbat.ses.dialogue.session.SessionState;
import org.hanbat.ses.dialogue.session.SessionStore;
import org.hanbat.ses.dialogue.session.TurnOutcome;
import org.hanbat.ses.dialogue.session.TurnResult;
import org.hanbat.ses.scenario.exec.ScenarioBuilder;
import org.hanbat.ses.scenario.model.Scenario;
import org.hanbat.ses.scenario.model.ScenarioStore;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.springframework.stereotype.Service;

/**
 * 대화 계층과 시나리오 계층을 잇는 응용 서비스.
 *
 * <p>두 모듈은 서로를 모른다 (dialogue 는 scenario 에 의존하지 않는다).
 * 그 둘을 아는 유일한 곳이 여기이고, 그래서 이 클래스는 api 모듈에 있다.
 */
@Service
public class SessionFacade {

    private final DialogueController dialogue;
    private final SessionStore sessions;
    private final ScenarioBuilder scenarioBuilder;
    private final ScenarioStore scenarios;
    private final SesSnapshotAssembler snapshots;
    private final SesProgress progress = new SesProgress();

    public SessionFacade(DialogueController dialogue, SessionStore sessions,
                         ScenarioBuilder scenarioBuilder, ScenarioStore scenarios,
                         SesSnapshotAssembler snapshots) {
        this.dialogue = dialogue;
        this.sessions = sessions;
        this.scenarioBuilder = scenarioBuilder;
        this.scenarios = scenarios;
        this.snapshots = snapshots;
    }

    public SessionResponse create(String request, String templateId) {
        return toResponse(dialogue.createSession(request, templateId));
    }

    public SessionResponse answer(UUID sessionId, Map<String, Object> answers) {
        return toResponse(dialogue.submitAnswers(sessionId, answers));
    }

    public SessionResponse undo(UUID sessionId) {
        return toResponse(dialogue.undo(sessionId));
    }

    public SessionResponse get(UUID sessionId) {
        SessionState state = dialogue.get(sessionId);
        return toResponse(new TurnResult(
                state.phase() == Phase.BUILDING || state.phase() == Phase.DONE
                        ? TurnOutcome.COMPLETE : TurnOutcome.ASK,
                state, state.pendingQuestions(), state.issues(), state.derivedFrom()));
    }

    public org.hanbat.ses.api.dto.SesSnapshotNode snapshot(UUID sessionId) {
        return snapshots.assemble(dialogue.get(sessionId).workingSes());
    }

    /**
     * 시나리오 확정. 완료 조건을 만족하지 않으면 거부한다.
     *
     * <p>이미 만든 시나리오가 있으면 그것을 돌려준다 — 같은 세션에서 두 번 호출해도
     * 서로 다른 시나리오 두 개가 생기지 않아야 한다.
     */
    public ScenarioResponse createScenario(UUID sessionId) {
        SessionState state = dialogue.get(sessionId);
        if (state.scenarioId() != null) {
            return toScenarioResponse(scenarios.require(state.scenarioId()));
        }
        if (!dialogue.openSlots(state).isEmpty()) {
            throw new SessionNotReadyException(sessionId,
                    "아직 결정되지 않은 항목이 " + dialogue.openSlots(state).size() + "개 남아 있습니다.");
        }
        SubtaskTemplate template = dialogue.requireTemplate(state);
        Scenario scenario = scenarios.save(
                scenarioBuilder.build(sessionId, template, state.workingSes()));
        sessions.save(state.withScenario(scenario.scenarioId()).withPhase(Phase.DONE));
        return toScenarioResponse(scenario);
    }

    public ScenarioResponse getScenario(UUID scenarioId) {
        return toScenarioResponse(scenarios.require(scenarioId));
    }

    // ------------------------------------------------------------ 변환

    private ScenarioResponse toScenarioResponse(Scenario s) {
        return new ScenarioResponse(s.scenarioId(), s.sessionId(), s.templateId(), s.summary(),
                s.pes().root(), s.params(),
                Map.of("engine", s.simConfig().engine(),
                        "horizon", s.simConfig().horizon(),
                        "timeResolution", s.simConfig().timeResolution(),
                        "seed", String.valueOf(s.simConfig().seed()),
                        "mode", s.simConfig().mode().name(),
                        "replications", s.simConfig().replications()));
    }

    private SessionResponse toResponse(TurnResult result) {
        // 템플릿을 못 정했으면 세션이 아직 없다. 후보만 실어 보낸다.
        if (result.outcome() == TurnOutcome.CHOOSE_TEMPLATE) {
            return new SessionResponse(null, "ROUTING", result.outcome().name(), 0,
                    List.of(), new ProgressView(0, 0, 0, 0.0), result.derivedFrom(),
                    result.issues().stream().map(IssueView::of).toList(),
                    null, null, false,
                    result.candidates().stream()
                            .map(c -> new SessionResponse.CandidateView(
                                    c.templateId(), c.name(), c.intent(), c.score(), c.why()))
                            .toList());
        }
        SessionState state = result.state();
        SesProgress.Counts counts = progress.count(state.workingSes());
        int perTurn = Math.max(1, dialogue.requireTemplate(state).dialogue().maxQuestionsPerTurn());

        return new SessionResponse(
                state.sessionId(),
                state.phase().name(),
                result.outcome().name(),
                state.turnCount(),
                result.questions().stream().map(QuestionView::of).toList(),
                new ProgressView(counts.resolved(), counts.open(),
                        (int) Math.ceil((double) counts.open() / perTurn), counts.ratio()),
                result.derivedFrom(),
                result.issues().stream().map(IssueView::of).toList(),
                snapshots.assemble(state.workingSes()),
                state.scenarioId(),
                state.canUndo(),
                List.of());
    }

    static List<Question> questions(TurnResult result) {
        return result.questions();
    }
}
