package org.hanbat.ses.dialogue.session;

public enum TurnOutcome {
    /** 새 질문을 낸다. */
    ASK,
    /** 검증에 걸려 같은 것을 다시 묻는다. */
    REASK,
    /** 모든 결정이 끝났다. 시나리오를 만들 수 있다. */
    COMPLETE,
    /** 턴 예산을 다 쓰고도 채우지 못했다. */
    FAILED,
    /**
     * 어느 서브태스크인지 확정할 근거가 부족하다. 후보를 제시하고 사용자 선택을 기다린다.
     *
     * <p>이때는 세션을 만들지 않는다. 템플릿이 정해지면 도메인 SES 도 정해지고,
     * 몇 턴 진행한 뒤 갈아타려면 트리를 통째로 버려야 한다 — 시작 전에 정하는 편이 싸다.
     */
    CHOOSE_TEMPLATE
}
