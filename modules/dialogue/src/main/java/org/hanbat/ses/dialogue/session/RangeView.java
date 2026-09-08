package org.hanbat.ses.dialogue.session;

import java.util.List;

/** 질문에 딸려 나가는 값 도메인. 클라이언트가 미리 막아 주면 왕복이 줄어든다. */
public record RangeView(Double min, Double max, List<String> allowed) {

    public RangeView {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }

    public boolean isEmpty() {
        return min == null && max == null && allowed.isEmpty();
    }
}
