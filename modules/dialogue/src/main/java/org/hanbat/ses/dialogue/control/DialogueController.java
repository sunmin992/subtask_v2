package org.hanbat.ses.dialogue.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.prune.PruningEngine;
import org.hanbat.ses.core.prune.PruningException;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.dialogue.external.ExternalDataResolver;
import org.hanbat.ses.dialogue.external.FillSource;
import org.hanbat.ses.dialogue.external.SlotProvenance;
import org.hanbat.ses.dialogue.routing.RequestRouter;
import org.hanbat.ses.dialogue.routing.RoutingDecision;
import org.hanbat.ses.dialogue.session.Phase;
import org.hanbat.ses.dialogue.session.Question;
import org.hanbat.ses.dialogue.session.SessionState;
import org.hanbat.ses.dialogue.session.SessionStore;
import org.hanbat.ses.dialogue.session.TurnResult;
import org.hanbat.ses.dialogue.validate.ValidationChain;
import org.hanbat.ses.dialogue.validate.ValidationContext;
import org.hanbat.ses.llm.audit.SessionContext;
import org.hanbat.ses.template.model.DialogueControl;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hanbat.ses.template.model.UnfilledPolicy;
import org.hanbat.ses.template.registry.SesDefinition;
import org.hanbat.ses.template.registry.SesDefinitionNotFoundException;
import org.hanbat.ses.template.registry.SesRegistry;
import org.hanbat.ses.template.registry.TemplateNotFoundException;
import org.hanbat.ses.template.registry.TemplateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 대화 상태머신.
 *
 * <p>한 턴의 흐름은 언제나 같다.
 * <pre>
 *   답변 검증 -> pruning 적용 -> 슬롯 재스캔 -> 자동 충전 -> 완료 판정 -> 다음 질문
 * </pre>
 * 세 번째 단계인 <b>재스캔</b>이 이 설계의 핵심이다. 구조 답변이 새 슬롯을 파생시키므로
 * 질문 목록을 미리 만들어 둘 수 없다. 매 턴 트리에서 다시 계산해야 한다.
 */
@Service
public class DialogueController {

    private static final Logger log = LoggerFactory.getLogger(DialogueController.class);

    /**
     * 요청문 추출 반복 횟수 상한.
     *
     * <p>구조 -> 값 -> (multi-aspect 라면) 복제본별 값 순으로 최대 세 단계면 바닥에 닿는다.
     * 상한이 없으면 LLM 이 매번 같은 값을 다시 제안할 때 무한히 돈다.
     */
    private static final int MAX_PREFILL_ROUNDS = 3;

    /**
     * 이 값 미만이면 사용자에게 후보를 제시한다 (FR-104).
     *
     * <p>잘못 고른 템플릿은 도메인 SES 자체를 바꾼다. 대화를 몇 턴 진행한 뒤 갈아타려면
     * 트리를 통째로 버려야 하므로, 애매할 때는 시작 전에 묻는 편이 훨씬 싸다.
     */
    private static final double MIN_ROUTING_CONFIDENCE = 0.5;

    private final SessionStore sessions;
    private final TemplateRegistry templates;
    private final SesRegistry sesDefinitions;
    private final RequestRouter router;
    private final SlotJoiner joiner;
    private final QuestionPlanner planner;
    private final CompletionJudge completionJudge;
    private final DerivationExplainer explainer;
    private final SlotExtractor extractor;
    private final ExternalDataResolver externalData;
    private final ValidationChain validationChain;
    private final PruningEngine pruningEngine = new PruningEngine();

    public DialogueController(SessionStore sessions, TemplateRegistry templates,
                              SesRegistry sesDefinitions, RequestRouter router,
                              SlotJoiner joiner, QuestionPlanner planner,
                              CompletionJudge completionJudge, DerivationExplainer explainer,
                              SlotExtractor extractor, ExternalDataResolver externalData,
                              ValidationChain validationChain) {
        this.sessions = sessions;
        this.templates = templates;
        this.sesDefinitions = sesDefinitions;
        this.router = router;
        this.joiner = joiner;
        this.planner = planner;
        this.completionJudge = completionJudge;
        this.explainer = explainer;
        this.extractor = extractor;
        this.externalData = externalData;
        this.validationChain = validationChain;
    }

    // ------------------------------------------------------------ 세션 시작

    /**
     * 요청문으로 세션을 연다.
     *
     * @param explicitTemplateId 라우팅을 건너뛰고 템플릿을 직접 지정할 때. Phase 3 E2E 가 이 경로를 쓴다.
     */
    public TurnResult createSession(String request, String explicitTemplateId) {
        return createSession(request, explicitTemplateId,
                org.hanbat.ses.dialogue.external.DataUsage.forRequest(request));
    }

    public TurnResult createSession(String request, String explicitTemplateId,
                                   org.hanbat.ses.dialogue.external.DataUsage usage) {
        UUID sessionId = UUID.randomUUID();
        return SessionContext.with(sessionId, () -> {
            // 근거가 부족하면 임의로 고르지 않고 후보를 돌려준다 (FR-104).
            if (explicitTemplateId == null || explicitTemplateId.isBlank()) {
                RoutingDecision decision = router.route(request)
                        .orElseThrow(() -> new NoMatchingTemplateException(request));
                if (decision.needsUserChoice(MIN_ROUTING_CONFIDENCE)) {
                    log.debug("라우팅 근거 부족 (confidence={}). 후보 {}개를 제시합니다.",
                            decision.confidence(), decision.candidates().size());
                    if (decision.candidates().isEmpty()) {
                        throw new NoMatchingTemplateException(request);
                    }
                    return TurnResult.chooseTemplate(decision.candidates(),
                            "요청만으로는 어느 서브태스크인지 확정하기 어렵습니다. "
                                    + "아래에서 골라 templateId 와 함께 다시 요청해 주세요.");
                }
                log.debug("라우팅: {} (source={}, confidence={})",
                        decision.templateId(), decision.source(), decision.confidence());
            }
            SubtaskTemplate template = resolveTemplate(request, explicitTemplateId);
            SesNode ses = loadSes(template);

            SessionState state = SessionState.start(sessionId, template.id(), template.version(),
                    request, ses);

            // 지금의 사실을 묻는 질문에 예측으로 답하지 않는다.
            //
            // 어느 도메인도 실시간 상태 슬롯을 갖고 있지 않으므로, 여기서 답할 수 있는 것은
            // 가정한 구성의 예측뿐이다. 그것을 현재 상태처럼 내보내면 사용자는 시뮬레이션 결과를
            // 사실로 읽는다. 문구에 특정 도메인을 적지 않는 이유도 같다 — 이 판정은 라우팅 전에,
            // 도메인을 모른 채 일어난다.
            if (usage == org.hanbat.ses.dialogue.external.DataUsage.CURRENT_STATUS) {
                List<ValidationIssue> unavailable = List.of(ValidationIssue.error("<session>",
                        "CURRENT_STATUS_UNAVAILABLE",
                        "현재 이용 가능 여부는 확인할 수 없습니다. 실시간 상태 API 가 연결되어 있지 않습니다. "
                                + "시뮬레이션 결과로 현재 상태를 대신 판단하지 않습니다. "
                                + "가정한 구성의 예측이 필요하면 '시뮬레이션'으로 다시 요청해 주세요."));
                SessionState failed = sessions.save(state.advance(ses, Phase.FAILED, Map.of(),
                        List.of(), unavailable, unavailable.get(0).message()));
                return TurnResult.failed(failed, unavailable);
            }

            // 요청문에서 뽑을 수 있는 값을 먼저 채운다. 이미 말한 것을 다시 묻지 않기 위해서다.
            Prefill prefill = prefillFromRequest(request, template, state.workingSes());
            state = state.advance(prefill.ses(), Phase.ELICITING, prefill.accepted(),
                    prefill.provenance(), List.of(), prefill.issues(), prefill.note());

            return nextTurn(state, template, List.of(), prefill.note());
        });
    }

    // ------------------------------------------------------------ 턴 처리

    public TurnResult submitAnswers(UUID sessionId, Map<String, Object> rawAnswers) {
        return SessionContext.with(sessionId, () -> {
            SessionState state = sessions.require(sessionId);
            if (state.phase().terminal()) {
                return state.phase() == Phase.FAILED ? TurnResult.failed(state, state.issues())
                        : TurnResult.complete(state);
            }
            SubtaskTemplate template = requireTemplate(state);

            List<ResolvedSlot> before = joiner.scanAndJoin(state.workingSes(), template);
            Map<String, ResolvedSlot> byName = joiner.byName(before);

            // 1. 답변별 타입/도메인 검증
            ValidationContext ctx = new ValidationContext(state.workingSes(), template,
                    rawAnswers, merge(state.answers(), rawAnswers), byName);
            List<ValidationIssue> issues = validationChain.validateAnswers(ctx);
            if (hasError(issues)) {
                SessionState reasked = state.reask(reaskQuestions(issues, byName, before), issues);
                return TurnResult.reask(sessions.save(reasked), reasked.pendingQuestions(), issues);
            }

            // 2. pruning 적용
            SesNode ses = state.workingSes();
            List<ValidationIssue> applyIssues = new ArrayList<>();
            Map<String, Object> applied = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : rawAnswers.entrySet()) {
                ResolvedSlot slot = byName.get(e.getKey());
                if (slot == null) {
                    continue;
                }
                try {
                    ses = pruningEngine.apply(ses, slot.anchor(), e.getValue());
                    applied.put(e.getKey(), e.getValue());
                } catch (PruningException ex) {
                    applyIssues.add(ValidationIssue.error(e.getKey(), "PRUNING_FAILED",
                            ex.getMessage()));
                }
            }
            if (hasError(applyIssues)) {
                SessionState reasked = state.reask(
                        reaskQuestions(applyIssues, byName, before), applyIssues);
                return TurnResult.reask(sessions.save(reasked),
                        reasked.pendingQuestions(), applyIssues);
            }

            // 3. 적용된 트리를 대상으로 교차 제약과 단위를 검사한다.
            //    값이 실제로 들어간 트리를 봐야만 보이는 문제가 있다 —
            //    트리가 불변이라 문제가 있으면 원래 트리를 그대로 다시 쓰면 된다.
            List<ValidationIssue> appliedIssues = validationChain.validateApplied(
                    new ValidationContext(ses, template, applied,
                            merge(state.answers(), applied), byName));
            if (hasError(appliedIssues)) {
                SessionState reasked = state.reask(
                        reaskQuestions(appliedIssues, byName, before), appliedIssues);
                return TurnResult.reask(sessions.save(reasked),
                        reasked.pendingQuestions(), appliedIssues);
            }

            // 4~7. 재스캔, 자동 충전, 완료 판정, 다음 질문
            List<ResolvedSlot> after = joiner.scanAndJoin(ses, template);
            String derived = explainer.explain(before, after, applied);

            // 구조 답변으로 새 슬롯이 생겼다면 요청문을 다시 훑는다.
            //
            // "8인승 곤돌라를 3분 간격으로" 라고 말해 두었어도, 이동설비가 정해지기 전에는
            // 정원·운행간격 슬롯이 존재하지 않아 추출할 대상이 없었다. 여기서 다시 보지 않으면
            // 사용자가 이미 말한 값을 서버가 다시 묻게 된다 — 추출을 붙인 의미가 사라진다.
            Map<String, Object> allAnswers = merge(state.answers(), applied);

            // 사용자가 직접 말한 값이라는 사실을 여기서 남긴다. 나중에 채워지는 값과
            // 섞이기 전에 찍어 두어야, 같은 슬롯을 자동 채움이 덮었는지 알 수 있다.
            Map<String, SlotProvenance> provenance = new LinkedHashMap<>();
            applied.keySet().forEach(name -> provenance.put(name, SlotProvenance.userAnswer(name)));

            // 자동으로 채운 값은 사용자에게 알려야 한다. 이 목록을 흘리면 사용자가 말한 적 없는
            // 숫자가 조용히 결과에 들어가고, 나중에 결과를 의심할 때 짚을 자리가 없다.
            List<ValidationIssue> autoFillNotes = new ArrayList<>();

            if (derivedNewSlots(before, after) && !after.isEmpty()) {
                Prefill late = prefillFromRequest(state.request(), template, ses);
                if (!late.accepted().isEmpty()) {
                    ses = late.ses();
                    allAnswers = merge(allAnswers, late.accepted());
                    applied = merge(applied, late.accepted());
                    provenance.putAll(late.provenance());
                    autoFillNotes.addAll(late.issues());
                    after = joiner.scanAndJoin(ses, template);
                    derived = (derived == null ? "" : derived + " ") + late.note();
                }
            }

            SessionState advanced = state.advance(ses, Phase.ELICITING, applied, provenance,
                    List.of(), autoFillNotes, derived);
            return nextTurn(advanced, template, after, derived);
        });
    }

    /** 직전 턴 되돌리기. 트리 하나를 복원하면 열린 슬롯도 자동으로 되돌아간다. */
    public TurnResult undo(UUID sessionId) {
        SessionState state = sessions.require(sessionId);
        if (state.issues().stream().anyMatch(i -> i.code().equals("CURRENT_STATUS_UNAVAILABLE"))) {
            return TurnResult.failed(state, state.issues());
        }
        if (!state.canUndo()) {
            return TurnResult.ask(sessions.save(state), state.pendingQuestions(),
                    "되돌릴 이전 턴이 없습니다.");
        }
        SubtaskTemplate template = requireTemplate(state);
        SessionState restored = state.undo();
        return nextTurn(restored, template, List.of(), restored.derivedFrom());
    }

    public SessionState get(UUID sessionId) {
        return sessions.require(sessionId);
    }

    /** 현재 열린 슬롯. 진행률 표시와 디버깅에 쓴다. */
    public List<ResolvedSlot> openSlots(SessionState state) {
        return joiner.scanAndJoin(state.workingSes(), requireTemplate(state));
    }

    public SubtaskTemplate requireTemplate(SessionState state) {
        return templates.find(state.templateId(), state.templateVersion())
                .or(() -> templates.findActive(state.templateId()))
                .orElseThrow(() -> new TemplateNotFoundException(
                        state.templateId(), state.templateVersion()));
    }

    // ------------------------------------------------------------ 내부

    /**
     * 남은 슬롯을 보고 다음 상태를 정한다.
     *
     * <p>createSession, submitAnswers, undo 가 모두 여기로 모인다.
     * 세 곳에 같은 판정을 복사해 두면 반드시 한 곳만 고치는 날이 온다.
     */
    private TurnResult nextTurn(SessionState state, SubtaskTemplate template,
                                List<ResolvedSlot> knownOpen, String derived) {
        List<ResolvedSlot> open = knownOpen.isEmpty()
                ? joiner.scanAndJoin(state.workingSes(), template)
                : knownOpen;

        if (open.isEmpty()) {
            return finish(state, template);
        }

        DialogueControl control = template.dialogue();
        if (state.turnCount() >= control.maxTurns()) {
            return forceComplete(state, template, open);
        }

        List<Question> questions = planner.plan(open, state.answers(), control);
        SessionState asked = sessions.save(state.withQuestions(questions)
                .withPhase(Phase.ELICITING));
        return TurnResult.ask(asked, questions, derived);
    }

    /** 열린 슬롯이 없다 — 전 구간 검증 후 완료 판정. */
    private TurnResult finish(SessionState state, SubtaskTemplate template) {
        ValidationContext ctx = new ValidationContext(state.workingSes(), template,
                Map.of(), state.answers(), Map.of());
        List<ValidationIssue> issues = validationChain.validateAll(ctx);
        CompletionJudge.Verdict verdict = completionJudge.judge(state.workingSes(), issues);

        if (verdict.complete()) {
            SessionState done = sessions.save(state.withQuestions(List.of())
                    .withPhase(Phase.BUILDING));
            return TurnResult.complete(done);
        }
        // 검증에 걸렸는데 물어볼 슬롯이 없다 — 사용자가 고칠 수 있는 항목을 다시 연다.
        List<ResolvedSlot> reopened = reopenForIssues(state, template, verdict.blockers());
        if (reopened.isEmpty()) {
            SessionState failed = sessions.save(state.withPhase(Phase.FAILED));
            return TurnResult.failed(failed, verdict.blockers());
        }
        List<Question> questions = reopened.stream().map(planner::toReask).toList();
        SessionState reasked = sessions.save(state.reask(questions, verdict.blockers()));
        return TurnResult.reask(reasked, questions, verdict.blockers());
    }

    /**
     * 턴 예산이 끝났다. UnfilledPolicy 에 따라 마감한다.
     *
     * <p>기본값으로 채우고 넘어가는 편이 대개 낫다. 대화가 열 턴을 넘어가면
     * 사용자는 이미 창을 닫았고, 부정확한 결과라도 있는 편이 아무것도 없는 것보다 낫다.
     */
    private TurnResult forceComplete(SessionState state, SubtaskTemplate template,
                                     List<ResolvedSlot> open) {
        UnfilledPolicy policy = template.dialogue().unfilledPolicy();
        if (policy == UnfilledPolicy.DEFER) {
            SessionState deferred = sessions.save(state.withPhase(Phase.ELICITING));
            return TurnResult.failed(deferred, List.of(ValidationIssue.warning("<session>",
                    "DEFERRED", "턴 예산이 끝나 미결정 상태로 세션을 보류합니다.")));
        }
        if (policy == UnfilledPolicy.ASK_AGAIN) {
            SessionState failed = sessions.save(state.withPhase(Phase.FAILED));
            return TurnResult.failed(failed, List.of(ValidationIssue.error("<session>",
                    "TURN_BUDGET_EXHAUSTED",
                    "최대 " + template.dialogue().maxTurns() + "턴 안에 필요한 정보를 다 받지 못했습니다.")));
        }

        SesNode ses = state.workingSes();
        Map<String, Object> filled = new LinkedHashMap<>();
        Map<String, SlotProvenance> provenance = new LinkedHashMap<>();
        List<ValidationIssue> notes = new ArrayList<>();
        for (ResolvedSlot slot : open) {
            Object value = defaultFor(slot);
            if (value == null) {
                notes.add(ValidationIssue.error(slot.name(), "NO_DEFAULT",
                        slot.name() + " 에 쓸 기본값이 없어 자동으로 마감할 수 없습니다."));
                continue;
            }
            try {
                ses = pruningEngine.apply(ses, slot.anchor(), value);
                filled.put(slot.name(), value);
                provenance.put(slot.name(), SlotProvenance.templateDefault(slot.name(), value));
                notes.add(ValidationIssue.warning(slot.name(), "FILLED_WITH_DEFAULT",
                        slot.name() + " 을(를) 기본값 " + value + " 로 채웠습니다."));
            } catch (PruningException e) {
                notes.add(ValidationIssue.error(slot.name(), "DEFAULT_REJECTED", e.getMessage()));
            }
        }
        SessionState advanced = state.advance(ses, Phase.VALIDATING, filled, provenance,
                List.of(), notes, "턴 예산이 끝나 남은 항목을 기본값으로 채웠습니다.");

        // 기본값을 채운 뒤에도 남은 슬롯이 있으면 (파생 슬롯) 한 번 더 돈다.
        List<ResolvedSlot> remaining = joiner.scanAndJoin(ses, template);
        if (remaining.isEmpty()) {
            TurnResult result = finish(advanced, template);
            return new TurnResult(result.outcome(), result.state(), result.questions(),
                    concat(notes, result.issues()), advanced.derivedFrom());
        }
        if (remaining.size() >= open.size()) {
            // 진전이 없다 — 무한 루프를 막는다.
            SessionState failed = sessions.save(advanced.withPhase(Phase.FAILED));
            return TurnResult.failed(failed, notes);
        }
        return forceComplete(advanced, template, remaining);
    }

    private Object defaultFor(ResolvedSlot slot) {
        Object templateDefault = slot.templateDefault();
        if (templateDefault != null) {
            return templateDefault;
        }
        return switch (slot.kind()) {
            // 선택지가 있으면 첫 번째를 쓴다. 도메인 SES 는 대표 변형을 맨 앞에 둔다.
            case SELECT -> slot.open().options().isEmpty()
                    ? null : slot.open().options().get(0).id();
            case MULTIPLICITY -> slot.open().countRange() == null
                    ? null : slot.open().countRange().min();
            case VALUE -> slot.open().varDef().defaultValue();
        };
    }

    private record Prefill(SesNode ses, Map<String, Object> accepted,
                           Map<String, SlotProvenance> provenance,
                           List<ValidationIssue> issues, String note) {
    }

    /** 채움 후보 하나 — 값, 그 값의 출처, 사용자 확인이 필요한지. */
    private record Candidate(Object value, SlotProvenance provenance, boolean needsConfirmation) {
    }

    /**
     * 질문하지 않고도 알 수 있는 값을 먼저 채운다 — 외부 데이터, 그다음 요청문 추출.
     *
     * <p>채움 경로가 셋이고 순서가 정해져 있다.
     * <pre>
     *   1. 외부 데이터  — 도메인이 아는 참고값. 그 안에서 다시 실시간/캐시/내장 3단으로 내려간다.
     *   2. 요청문 추출  — 사용자가 이미 말한 값. LLM 이 없으면 이 단은 통째로 건너뛴다.
     *   3. 질문        — 위 둘이 답하지 못한 것만 남는다.
     * </pre>
     * 순서를 이렇게 둔 이유는 뒤로 갈수록 비싸기 때문이다. 사용자에게 묻는 것이 가장 비싸고,
     * 그 비용은 대화 턴 수로 곧장 나타난다. 사용자가 직접 답한 값은 이 경로들보다 언제나
     * 강하다 — {@code submitAnswers} 가 나중에 덮어쓰고, 출처도 사용자 답변으로 다시 찍는다.
     *
     * <p>여러 번 돈다. 첫 회차에는 구조 슬롯만 열려 있으므로 "급속충전기"까지만 채울 수 있고,
     * 그것을 반영해야 비로소 "평균 충전시간" 슬롯이 생긴다. 한 문장을 한 번에 소화하려면
     * 재스캔이 필요하다.
     *
     * <p>어느 경로로 들어온 값이든 <b>예외 없이</b> {@code ValidationChain} 을 지난다.
     * 외부 데이터라고 봐주지 않는다 — 공개 통계의 평균이 이 템플릿의 허용 범위 안이라는
     * 보장은 어디에도 없고, 범위를 벗어난 값은 그냥 질문으로 돌린다.
     */
    private Prefill prefillFromRequest(String request, SubtaskTemplate template,
                                       SesNode startingSes) {
        SesNode original = startingSes;
        SesNode ses = original;
        String domain = domainOf(template);
        Map<String, Object> accepted = new LinkedHashMap<>();
        Map<String, SlotProvenance> provenance = new LinkedHashMap<>();
        List<ValidationIssue> notes = new ArrayList<>();

        Map<String, ExternalDataResolver.Resolution> pendingCache = new LinkedHashMap<>();

        for (int round = 0; round < MAX_PREFILL_ROUNDS; round++) {
            List<ResolvedSlot> open = joiner.scanAndJoin(ses, template);
            Map<String, Candidate> candidates =
                    gatherCandidates(request, template, domain, open, accepted.keySet(), ses, pendingCache);
            if (candidates.isEmpty()) {
                break;
            }

            Map<String, ResolvedSlot> byName = joiner.byName(open);
            Map<String, Object> values = new LinkedHashMap<>();
            candidates.forEach((k, v) -> values.put(k, v.value()));

            List<ValidationIssue> issues = validationChain.validateAnswers(
                    new ValidationContext(ses, template, values, values, byName));
            java.util.Set<String> rejected = issues.stream()
                    .filter(ValidationIssue::isError)
                    .map(ValidationIssue::slot)
                    .collect(java.util.stream.Collectors.toSet());

            int appliedThisRound = 0;
            for (Map.Entry<String, Candidate> e : candidates.entrySet()) {
                if (rejected.contains(e.getKey())) {
                    log.debug("채움 후보가 검증을 통과하지 못해 질문으로 돌립니다: {}", e.getKey());
                    continue;
                }
                ResolvedSlot slot = byName.get(e.getKey());
                if (slot == null) {
                    continue;
                }
                Candidate candidate = e.getValue();
                try {
                    ses = pruningEngine.apply(ses, slot.anchor(), candidate.value());
                    accepted.put(e.getKey(), candidate.value());
                    provenance.put(e.getKey(), candidate.provenance());
                    appliedThisRound++;
                    notes.addAll(confirmationNotes(e.getKey(), slot, candidate));
                } catch (PruningException ex) {
                    log.debug("채움 후보 적용 실패: {}", ex.getMessage());
                }
            }
            if (appliedThisRound == 0) {
                break;
            }
        }

        if (accepted.isEmpty()) {
            return new Prefill(original, Map.of(), Map.of(), List.of(), null);
        }
        // 적용 후 검사에서 걸리면 미리 채운 값을 전부 버린다.
        // 요청문 해석이 어긋난 것이므로, 일부만 남기면 사용자가 말하지 않은 조합이 만들어진다.
        List<ValidationIssue> applied = validationChain.validateApplied(
                new ValidationContext(ses, template, accepted, accepted, Map.of()));
        if (applied.stream().anyMatch(ValidationIssue::isError)) {
            log.debug("미리 채운 값의 조합이 검증을 통과하지 못해 전부 버립니다.");
            return new Prefill(original, Map.of(), Map.of(), List.of(), null);
        }
        pendingCache.keySet().retainAll(accepted.keySet());
        externalData.commit(pendingCache);
        return new Prefill(ses, accepted, provenance, notes, prefillNote(provenance));
    }

    /**
     * 이번 라운드에 채울 수 있는 후보를 모은다 — 외부 데이터 먼저, 남은 슬롯만 LLM 에게.
     *
     * <p>이미 외부 데이터가 답한 슬롯을 추출 프롬프트에서 빼는 것이 중요하다. 넣어 두면
     * 모델이 같은 값을 다시 제안하고, 그 제안이 뒤에 들어와 이기면서 출처가 조용히 바뀐다.
     */
    private Map<String, Candidate> gatherCandidates(String request, SubtaskTemplate template,
                                                    String domain, List<ResolvedSlot> open,
                                                    java.util.Set<String> alreadyAccepted, SesNode startingSes,
                                                    Map<String, ExternalDataResolver.Resolution> pendingCache) {
        Map<String, Candidate> out = new LinkedHashMap<>();

        Map<String, ResolvedSlot> slots = joiner.byName(open);
        externalData.resolveAll(domain, template.id(), open,
                org.hanbat.ses.dialogue.external.DataUsage.SIMULATION,
                template.validation().unitAliases(), trial -> {
                    SesNode trialSes = startingSes;
                    Map<String, Object> values = new LinkedHashMap<>();
                    try {
                        for (var entry : trial.entrySet()) {
                            values.put(entry.getKey(), entry.getValue().value());
                            trialSes = pruningEngine.apply(trialSes, slots.get(entry.getKey()).anchor(),
                                    entry.getValue().value());
                        }
                        return !hasError(validationChain.validateApplied(
                                new ValidationContext(trialSes, template, values, values, slots)));
                    } catch (PruningException ex) {
                        return false;
                    }
                }).forEach((name, resolution) -> {
            if (!alreadyAccepted.contains(name)) {
                out.put(name, new Candidate(resolution.value(), resolution.provenance(), false));
                pendingCache.put(name, resolution);
            }
        });

        List<ResolvedSlot> remaining = open.stream()
                .filter(s -> !out.containsKey(s.name()) && !alreadyAccepted.contains(s.name()))
                .toList();
        if (remaining.isEmpty()) {
            return out;
        }
        extractor.extract(request, remaining).forEach((name, suggestion) -> {
            if (!alreadyAccepted.contains(name)) {
                out.put(name, new Candidate(suggestion.value(),
                        SlotProvenance.extracted(name, suggestion.evidence()),
                        suggestion.needsConfirmation()));
            }
        });
        return out;
    }

    /**
     * 채운 값을 사용자에게 알릴지 정한다.
     *
     * <p>구조 결정은 신뢰도와 무관하게 확인받는다. 확신에 찬 오해를 막을 방법이 그것뿐이다 —
     * llama3:latest 는 "8인승 곤돌라"를 셔틀버스 8대로 읽고 높은 신뢰도를 붙였다. 값 하나하나는
     * 범위 안이고 근거 문구도 문장에 있으니 검증은 전부 통과한다. 구조가 바뀌면 시뮬레이션
     * 전체가 다른 이야기가 되므로, 값 슬롯과 달리 자동 채움을 조용히 넘겨서는 안 된다.
     *
     * <p>외부 데이터로 채운 값도 알린다. 사용자가 말한 적 없는 숫자가 결과에 들어가는데
     * 그 사실이 화면 어디에도 없으면, 나중에 결과를 의심할 때 짚을 자리가 없다.
     */
    private List<ValidationIssue> confirmationNotes(String name, ResolvedSlot slot,
                                                    Candidate candidate) {
        if (slot.kind().isStructural()) {
            return List.of(ValidationIssue.warning(name, "STRUCTURE_INFERRED",
                    name + " 을(를) " + candidate.value() + " 로 이해했습니다. 맞나요? ("
                            + candidate.provenance().describe() + ")"));
        }
        if (candidate.provenance().source() == FillSource.EXTERNAL_DATA) {
            return List.of(ValidationIssue.warning(name, "FILLED_FROM_EXTERNAL_DATA",
                    name + " 을(를) 묻지 않고 " + candidate.value() + " 로 채웠습니다. ("
                            + candidate.provenance().describe() + ")"));
        }
        if (candidate.needsConfirmation()) {
            return List.of(ValidationIssue.warning(name, "LOW_CONFIDENCE_EXTRACTION",
                    name + " 을(를) " + candidate.value() + " 로 이해했습니다. 맞나요?"));
        }
        return List.of();
    }

    /** 무엇을 어느 경로로 채웠는지 한 줄로 알린다. */
    private static String prefillNote(Map<String, SlotProvenance> provenance) {
        long external = provenance.values().stream()
                .filter(p -> p.source() == FillSource.EXTERNAL_DATA).count();
        long extracted = provenance.size() - external;
        StringBuilder sb = new StringBuilder();
        if (extracted > 0) {
            sb.append("요청문에서 ").append(extracted).append("개 항목을 읽어 미리 채웠습니다.");
        }
        if (external > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("외부 데이터로 ").append(external).append("개 항목을 채웠습니다.");
        }
        return sb.toString();
    }

    /** 외부 데이터원은 도메인 이름으로 자료를 찾는다. 템플릿 id 로는 도메인을 알 수 없다. */
    private String domainOf(SubtaskTemplate template) {
        return sesDefinitions.find(template.binding().sesDefinitionId())
                .map(SesDefinition::domain)
                .orElse(null);
    }

    private List<ResolvedSlot> reopenForIssues(SessionState state, SubtaskTemplate template,
                                               List<ValidationIssue> issues) {
        // 값 슬롯은 이미 채워져 있어 재스캔에 잡히지 않는다. 문제가 지목한 슬롯을 이름으로 되살린다.
        List<ResolvedSlot> all = joiner.scanAndJoin(state.workingSes(), template);
        List<String> names = issues.stream().map(ValidationIssue::slot).toList();
        return all.stream().filter(s -> names.contains(s.name())).toList();
    }

    private SubtaskTemplate resolveTemplate(String request, String explicitTemplateId) {
        if (explicitTemplateId != null && !explicitTemplateId.isBlank()) {
            return templates.findActive(explicitTemplateId)
                    .orElseThrow(() -> new TemplateNotFoundException(explicitTemplateId));
        }
        RoutingDecision decision = router.route(request)
                .orElseThrow(() -> new NoMatchingTemplateException(request));
        return templates.findActive(decision.templateId())
                .orElseThrow(() -> new TemplateNotFoundException(decision.templateId()));
    }

    private SesNode loadSes(SubtaskTemplate template) {
        String id = template.binding().sesDefinitionId();
        return sesDefinitions.find(id)
                .map(SesDefinition::tree)
                .orElseThrow(() -> new SesDefinitionNotFoundException(id));
    }

    /**
     * 문제가 지목한 슬롯을 다시 묻는다.
     *
     * <p>검증기마다 issue 의 slot 을 다르게 부른다. 타입 검증기는 사용자에게 보인 슬롯
     * 이름을, 단위 검증기는 SES 가 만든 기계 이름을, 교차 제약은 변수 이름을 쓴다.
     * 검증기가 슬롯 이름 체계를 알아야 한다면 검증기 추가가 매번 번거로워지므로,
     * 대신 여기서 세 가지 이름을 모두 대조한다.
     */
    private List<Question> reaskQuestions(List<ValidationIssue> issues,
                                          Map<String, ResolvedSlot> byName,
                                          List<ResolvedSlot> open) {
        List<Question> questions = new ArrayList<>();
        for (ValidationIssue issue : issues) {
            if (!issue.isError()) {
                continue;
            }
            for (ResolvedSlot slot : byName.values()) {
                if (matchesIssue(slot, issue.slot())
                        && questions.stream().noneMatch(q -> q.slot().equals(slot.name()))) {
                    questions.add(planner.toReask(slot));
                }
            }
        }
        if (questions.isEmpty() && !open.isEmpty()) {
            questions.add(planner.toReask(open.get(0)));
        }
        return questions;
    }

    private static boolean matchesIssue(ResolvedSlot slot, String issueSlot) {
        if (issueSlot == null) {
            return false;
        }
        if (issueSlot.equals(slot.name()) || issueSlot.equals(slot.open().slotName())) {
            return true;
        }
        // 교차 제약은 변수 이름으로 지목한다 (예: "정원").
        return slot.open().varDef() != null && issueSlot.equals(slot.open().varDef().name());
    }

    /** 이번 턴에 없던 슬롯이 생겼는가. */
    private static boolean derivedNewSlots(List<ResolvedSlot> before, List<ResolvedSlot> after) {
        java.util.Set<String> old = before.stream().map(ResolvedSlot::name)
                .collect(java.util.stream.Collectors.toSet());
        return after.stream().anyMatch(s -> !old.contains(s.name()));
    }

    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        if (extra != null) {
            out.putAll(extra);
        }
        return out;
    }

    private static List<ValidationIssue> concat(List<ValidationIssue> a, List<ValidationIssue> b) {
        List<ValidationIssue> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static boolean hasError(List<ValidationIssue> issues) {
        return issues.stream().anyMatch(ValidationIssue::isError);
    }
}
