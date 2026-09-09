package org.hanbat.ses.dialogue.external;

/**
 * 슬롯 값이 어디에서 왔는가.
 *
 * <p>완성된 시나리오만 보면 "정원 8명"과 "정원 8명"은 구별되지 않는다. 하나는 사용자가
 * 말한 값이고 다른 하나는 서버가 기본값으로 채운 값이어도 그렇다. 그 차이를 잃으면
 * 결과를 검토하는 사람이 어디를 의심해야 할지 알 수 없고, 재현 실험에서 무엇이 입력이고
 * 무엇이 가정이었는지도 사라진다.
 *
 * <p>열거 순서가 채움 우선순위다. 앞선 것이 채우면 뒤는 그 슬롯을 건드리지 않는다.
 */
public enum FillSource {

    /** 사용자가 직접 답했다. 가장 강한 근거이므로 무엇으로도 덮지 않는다. */
    USER_ANSWER,

    /** 외부 데이터에서 왔다. 어느 단에서 왔는지는 {@link SourceTier} 가 말한다. */
    EXTERNAL_DATA,

    /** LLM 이 요청문에서 뽑았다. 근거 문구가 함께 기록된다. */
    LLM_EXTRACTION,

    /** 아무도 말하지 않아 템플릿 기본값으로 마감했다. 결과 해석에서 가장 조심할 값이다. */
    TEMPLATE_DEFAULT;

    public String label() {
        return switch (this) {
            case USER_ANSWER -> "사용자 답변";
            case EXTERNAL_DATA -> "외부 데이터";
            case LLM_EXTRACTION -> "요청문 추출";
            case TEMPLATE_DEFAULT -> "템플릿 기본값";
        };
    }

    /** 사용자가 직접 정한 값인가. 자동으로 채운 값과 결과 해석에서 구분해야 한다. */
    public boolean stated() {
        return this == USER_ANSWER;
    }
}
