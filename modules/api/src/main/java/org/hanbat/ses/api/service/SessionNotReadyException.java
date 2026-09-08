package org.hanbat.ses.api.service;

import java.util.UUID;

/** 아직 시나리오를 만들 수 없는 세션. */
public class SessionNotReadyException extends RuntimeException {

    public SessionNotReadyException(UUID sessionId, String reason) {
        super("세션 " + sessionId + " 은 아직 시나리오를 만들 수 없습니다: " + reason);
    }
}
