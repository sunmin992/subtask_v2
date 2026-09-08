package org.hanbat.ses.template.model;

/** 실행이 완주하지 못했을 때. */
public enum FailurePolicy {
    /** 얻은 만큼 돌려주고 왜 잘렸는지 알린다. */
    PARTIAL_RESULT,
    /** 결과를 버리고 실패로 보고한다. */
    FAIL_FAST,
    /** 부분 결과와 함께 문제 파라미터를 다시 묻는다. */
    REASK
}
