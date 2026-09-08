package org.hanbat.ses.dialogue.session;

import java.util.Optional;
import java.util.UUID;

/** 세션 저장소. JPA 구현과 인메모리 구현을 프로파일로 갈아 끼운다. */
public interface SessionStore {

    SessionState save(SessionState state);

    Optional<SessionState> find(UUID sessionId);

    default SessionState require(UUID sessionId) {
        return find(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    void delete(UUID sessionId);
}
