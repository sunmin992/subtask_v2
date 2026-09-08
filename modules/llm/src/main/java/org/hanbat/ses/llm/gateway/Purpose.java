package org.hanbat.ses.llm.gateway;

/**
 * LLM 을 부르는 지점 — 딱 세 가지 목적에만 쓴다.
 *
 * <p>pruning, 검증, 완료 판정은 전부 결정론적 Java 코드다. 재현성이 필요한
 * 시뮬레이션 시스템에서 LLM 이 구조를 직접 결정하게 두면 같은 입력에 다른 모델이 나온다.
 */
public enum Purpose {
    /** 요청문 -> 템플릿 분류. 실패하면 키워드 매칭으로 떨어진다. */
    ROUTING,
    /** 요청문 -> 슬롯 값 일괄 추출. 실패하면 전부 질문한다. */
    EXTRACTION,
    /** 기계 생성 질문/결과 -> 자연스러운 한국어. 실패하면 원문 그대로 쓴다. */
    PHRASING,
    /** 결과 요약. 실패하면 통계 표만 보여준다. */
    SUMMARY
}
