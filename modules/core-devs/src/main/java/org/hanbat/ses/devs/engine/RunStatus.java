package org.hanbat.ses.devs.engine;

/** 실행 종료 사유. COMPLETED 외에는 전부 부분 결과다. */
public enum RunStatus {
    /** horizon 까지 정상 진행. */
    COMPLETED,
    /** 더 이상 예정된 이벤트가 없어 조기 종료 (모든 컴포넌트가 passive). */
    QUIESCENT,
    /** 벽시계 타임아웃. */
    TIMED_OUT,
    /** 이벤트 수 상한 초과. */
    EVENT_LIMIT,
    /** 같은 시각에서 전이가 반복됨. */
    ZENO
}
