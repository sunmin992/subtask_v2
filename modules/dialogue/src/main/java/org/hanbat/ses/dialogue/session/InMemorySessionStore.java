package org.hanbat.ses.dialogue.session;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** DB 없이 도는 세션 저장소. Phase 3 E2E 테스트와 standalone 프로파일이 쓴다. */
public final class InMemorySessionStore implements SessionStore {

    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();

    @Override
    public SessionState save(SessionState state) {
        sessions.put(state.sessionId(), state);
        return state;
    }

    @Override
    public Optional<SessionState> find(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void delete(UUID sessionId) {
        sessions.remove(sessionId);
    }

    public int size() {
        return sessions.size();
    }
}
