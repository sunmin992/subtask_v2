package org.hanbat.ses.dialogue.external;

/**
 * 외부 데이터를 어느 단계에서 얻었는가.
 *
 * <p>열거 순서가 곧 폴백 순서다. 앞선 단이 답하면 뒤는 묻지 않는다.
 */
public enum SourceTier {

    /** 1단 — 외부 시스템에 지금 물어본 값. 가장 새롭고 가장 자주 실패한다. */
    LIVE,

    /** 2단 — 지난번 조회를 갈무리해 둔 값. 유효 기간이 지나면 없는 것으로 친다. */
    CACHED,

    /** 3단 — 서버에 함께 실려 있는 통계 자료. 늘 있지만 갱신되지 않는다. */
    BUNDLED;

    /** 사람에게 보여줄 이름. 결과 화면과 감사 로그에 그대로 쓴다. */
    public String label() {
        return switch (this) {
            case LIVE -> "실시간 조회";
            case CACHED -> "캐시 스냅샷";
            case BUNDLED -> "내장 데이터셋";
        };
    }
}
