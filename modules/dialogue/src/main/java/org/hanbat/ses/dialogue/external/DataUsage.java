package org.hanbat.ses.dialogue.external;

import java.util.List;
import java.util.Locale;

/**
 * 요청이 <b>지금의 사실</b>을 묻는지, <b>가정한 구성의 예측</b>을 묻는지.
 *
 * <p>둘은 데이터의 유효 기간과 대체 허용 범위가 다르다. 현재 상태를 묻는 질문에 몇 달 전
 * 통계나 내장 참고값으로 답하면 그건 틀린 답이지 오래된 답이 아니다.
 *
 * <h2>판정은 좁게 한다</h2>
 * <p>오분류의 대가가 양쪽으로 다르다. 예측 요청을 상태 조회로 잘못 보면 <b>세션이 아예 열리지
 * 않아</b> 사용자가 할 수 있는 일이 없어진다. 반대로 상태 질문을 예측으로 잘못 보면 시뮬레이션이
 * 돌고, 그 결과에는 출처와 "현재 상태가 아니다"라는 표시가 함께 붙는다. 그래서 확실할 때만
 * {@link #CURRENT_STATUS} 로 판정한다.
 *
 * <p>이 판정은 도메인을 모른 채 라우팅 전에 일어난다. 그래서 어느 도메인에서도 참인 근거만
 * 쓴다 — 특정 도메인의 낱말을 넣으면 다른 도메인의 멀쩡한 요청이 걸린다.
 */
public enum DataUsage {

    /** 가정한 구성의 예측. 참고값과 내장 데이터셋을 쓸 수 있다. */
    SIMULATION,

    /** 지금 이 순간의 사실. 실시간 관측만 쓸 수 있고, 없으면 없다고 답해야 한다. */
    CURRENT_STATUS;

    /** 시점을 지금으로 못박는 말. 이것만으로는 부족하다 — 가용 여부를 함께 물어야 한다. */
    private static final List<String> NOW = List.of("지금", "현재", "실시간", "방금");

    /**
     * 시점이 아니라 설계안을 가리키는 "현재".
     *
     * <p>"현재 구성으로 …"의 현재는 관측 시점이 아니라 <b>지금 논의 중인 안</b>을 말한다.
     * 시점으로 읽으면 예측 요청이 상태 조회로 뒤집힌다. 뒤에 오는 명사로만 갈라낼 수 있다.
     */
    private static final java.util.regex.Pattern PLAN_NOT_CLOCK =
            java.util.regex.Pattern.compile("(현재|지금)(구성|계획|설계|조건|기준)");

    /**
     * 실제 가용 여부를 묻는 표현.
     *
     * <p>낱개 어간("이용", "상태", "가능")은 쓰지 않는다. 부분 문자열로 맞추면 <b>이용객</b>,
     * <b>이용률</b>, <b>대기 상태</b> 같은 멀쩡한 시뮬레이션 낱말이 전부 걸린다.
     * 실제로 "현재 리조트 이용객 흐름을 시뮬레이션 해줘"가 상태 조회로 분류되어 세션이 죽었다.
     */
    private static final List<String> AVAILABILITY = List.of(
            "이용가능", "사용가능", "충전가능", "주차가능", "예약가능",
            "이용할수있", "사용할수있", "충전할수있", "주차할수있", "쓸수있",
            "이용할수없", "사용할수없", "충전할수없",
            "빈자리", "비어있", "자리있", "자리가있", "남아있", "대기없", "혼잡");

    /**
     * 예측을 명시적으로 요청하는 말.
     *
     * <p>이 말이 있으면 시점 표현이 무엇이든 예측이다. 사용자가 "시뮬레이션 해줘"라고 적어
     * 두었는데 상태 조회로 읽어 거절하는 것은 어떤 해석으로도 옳지 않다.
     */
    private static final List<String> SIMULATION_INTENT = List.of(
            "시뮬레이션", "시뮬", "모의", "돌려", "예측", "추정", "예상해", "분석해");

    /**
     * 요청문으로 용도를 추정한다.
     *
     * <p>호출부는 {@code CreateSessionRequest.usage} 로 이 추정을 덮을 수 있다. 추정이
     * 애매한 자리에서는 API 를 쓰는 쪽이 명시하는 편이 낫다.
     */
    public static DataUsage forRequest(String request) {
        if (request == null || request.isBlank()) {
            return SIMULATION;
        }
        // 띄어쓰기를 지우고 본다. "이용 가능"과 "이용가능"을 따로 적어 두면 반드시 한쪽이 빠진다.
        String text = request.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");

        if (containsAny(text, SIMULATION_INTENT)) {
            return SIMULATION;
        }
        boolean now = containsAny(PLAN_NOT_CLOCK.matcher(text).replaceAll(""), NOW);
        return now && containsAny(text, AVAILABILITY) ? CURRENT_STATUS : SIMULATION;
    }

    private static boolean containsAny(String text, List<String> needles) {
        return needles.stream().anyMatch(text::contains);
    }
}
