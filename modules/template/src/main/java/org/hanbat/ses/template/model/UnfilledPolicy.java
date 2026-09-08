package org.hanbat.ses.template.model;

/** 턴 예산이 끝났는데 슬롯이 남았을 때의 처리. */
public enum UnfilledPolicy {
    /** 계속 되묻는다. 턴 상한에 도달하면 실패로 마감한다. */
    ASK_AGAIN,
    /** 템플릿 기본값으로 채운다. */
    USE_DEFAULT,
    /** LLM 추정값을 쓴다. 추정 실패 시 기본값으로 되돌아간다. */
    LLM_INFER,
    /** 미결정 상태로 세션을 남겨 둔다. */
    DEFER
}
