package org.hanbat.ses.dialogue.external;

/**
 * 외부 데이터원에 던지는 질의 — 슬롯 하나를 가리키는 좌표.
 *
 * <p>공급자에게 SES 트리나 세션을 넘기지 않는다. 넘기면 공급자가 대화 내부 구조에
 * 기대게 되고, 그 순간 "외부 데이터원 추가"가 코드 수정 한 곳으로 끝나지 않는다.
 * 여기 있는 것은 어느 도메인의 어떤 이름을 가진 양인가뿐이다.
 *
 * @param domain     도메인 SES 의 domain 값 ("evcharge").
 * @param templateId 요청을 처리 중인 서브태스크 템플릿 id.
 * @param slotName   템플릿이 부르는 슬롯 이름 ("급속기.평균충전시간").
 * @param varName    SES 변수 이름 ("평균충전시간"). 슬롯 이름으로 못 찾을 때의 두 번째 열쇠다.
 * @param unit       기대하는 단위. 공급자가 다른 단위를 주면 채택하지 않는다.
 */
public record ExternalQuery(
        String domain,
        String templateId,
        String slotName,
        String varName,
        String unit
) {

    /** 캐시 열쇠. 도메인이 다르면 같은 슬롯 이름이라도 다른 양이다. */
    public String cacheKey() {
        return domain + "|" + templateId + "|" + slotName + "|" + varName + "|" + unit;
    }
}
