package org.hanbat.ses.llm.audit;

import java.util.UUID;

/**
 * 현재 스레드가 처리 중인 세션 id.
 *
 * <p>게이트웨이 시그니처에 sessionId 를 끼워 넣으면 호출부마다 그것을 들고 다녀야 한다.
 * 감사 로그 한 줄 때문에 인터페이스를 오염시키지 않으려고 ThreadLocal 로 뺀다.
 */
public final class SessionContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private SessionContext() {
    }

    public static void set(UUID sessionId) {
        CURRENT.set(sessionId);
    }

    public static UUID get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T with(UUID sessionId, java.util.function.Supplier<T> action) {
        UUID previous = CURRENT.get();
        CURRENT.set(sessionId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
