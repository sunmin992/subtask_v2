package org.hanbat.ses.llm.audit;

/** 감사 로그 싱크. 저장 실패가 본 흐름을 막으면 안 된다. */
public interface LlmCallRecorder {

    void record(LlmCallRecord call);

    /** 아무 데도 남기지 않는 기본 구현 — 단위 테스트와 standalone 프로파일용. */
    static LlmCallRecorder noop() {
        return call -> {
        };
    }
}
