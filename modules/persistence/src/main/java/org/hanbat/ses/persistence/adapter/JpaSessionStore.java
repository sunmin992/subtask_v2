package org.hanbat.ses.persistence.adapter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hanbat.ses.dialogue.session.SessionState;
import org.hanbat.ses.dialogue.session.SessionStore;
import org.hanbat.ses.persistence.entity.DialogueSessionEntity;

import org.hanbat.ses.persistence.repository.SessionRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션 저장소 JPA 어댑터.
 *
 * <p>도메인 레코드({@link SessionState})와 엔티티를 분리해 두는 것이 요점이다.
 * 대화 로직이 JPA 의 영속성 컨텍스트나 지연 로딩을 신경 쓰기 시작하면
 * 순수 자바로 테스트할 수 있다는 장점이 사라진다.
 */
@Repository
@ConditionalOnJpaPersistence
public class JpaSessionStore implements SessionStore {

    private final SessionRepository repository;

    public JpaSessionStore(SessionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public SessionState save(SessionState state) {
        DialogueSessionEntity entity = repository.findById(state.sessionId())
                .orElseGet(() -> new DialogueSessionEntity(state.sessionId()));

        entity.setTemplateId(state.templateId());
        entity.setTemplateVersion(state.templateVersion());
        entity.setPhase(state.phase());
        entity.setRequest(state.request());
        entity.setWorkingSes(state.workingSes());
        entity.setAnswers(state.answers());
        entity.setIssues(state.issues());
        entity.setPendingQuestions(state.pendingQuestions());
        entity.setHistory(state.history());
        entity.setDerivedFrom(state.derivedFrom());
        entity.setTurnCount(state.turnCount());
        entity.setScenarioId(state.scenarioId());
        entity.setCreatedAt(state.createdAt());
        entity.setUpdatedAt(state.updatedAt());

        repository.save(entity);
        return state;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionState> find(UUID sessionId) {
        return repository.findById(sessionId).map(JpaSessionStore::toDomain);
    }

    @Override
    @Transactional
    public void delete(UUID sessionId) {
        repository.deleteById(sessionId);
    }

    private static SessionState toDomain(DialogueSessionEntity e) {
        return new SessionState(
                e.getSessionId(), e.getTemplateId(), e.getTemplateVersion(), e.getPhase(),
                e.getRequest(), e.getWorkingSes(),
                e.getAnswers() == null ? java.util.Map.of() : e.getAnswers(),
                e.getTurnCount(),
                e.getIssues() == null ? List.of() : e.getIssues(),
                e.getPendingQuestions() == null ? List.of() : e.getPendingQuestions(),
                e.getDerivedFrom(),
                e.getHistory() == null ? List.of() : e.getHistory(),
                e.getScenarioId(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
