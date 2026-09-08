package org.hanbat.ses.dialogue.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 추출값의 근거를 요청문과 대조하는 규칙.
 *
 * <p>이 검사가 필요한 이유는 모델의 자기보고 신뢰도를 믿을 수 없기 때문이다.
 * gemma2:9b 는 값을 정확히 뽑아 놓고 confidence 를 예시의 0.0 그대로 써 보냈다.
 * 신뢰도로 문턱을 걸면 멀쩡한 추출이 전부 버려지므로, 검증 가능한 신호가 필요하다.
 */
class EvidenceCheckTest {

    private static final String REQUEST =
            "산 위에 8인승 곤돌라를 3분 간격으로 놓고, 숙소는 200실짜리 호텔로 해서 손님 흐름 봐줘";

    @Test
    @DisplayName("요청문을 그대로 인용하면 근거로 인정한다")
    void acceptsExactQuote() {
        assertThat(EvidenceCheck.grounded(REQUEST, "숙소는 200실짜리 호텔로 해서")).isTrue();
        assertThat(EvidenceCheck.grounded(REQUEST, "3분 간격으로")).isTrue();
    }

    @Test
    @DisplayName("띄어쓰기와 문장부호가 어긋나도 인정한다 — 인용은 대개 정확하지 않다")
    void toleratesSpacingAndPunctuation() {
        assertThat(EvidenceCheck.grounded(REQUEST, "8인승곤돌라")).isTrue();
        assertThat(EvidenceCheck.grounded(REQUEST, "200실짜리 호텔.")).isTrue();
    }

    @Test
    @DisplayName("조사를 떼어 인용해도 토큰 다수가 맞으면 인정한다")
    void acceptsPartialTokenOverlap() {
        assertThat(EvidenceCheck.grounded(REQUEST, "숙소는 호텔")).isTrue();
    }

    @Test
    @DisplayName("문장에 없는 근거는 거부한다 — 지어낸 값을 걸러내는 지점")
    void rejectsFabricatedEvidence() {
        assertThat(EvidenceCheck.grounded(REQUEST, "셔틀버스를 5대 운행")).isFalse();
        assertThat(EvidenceCheck.grounded(REQUEST, "일반적으로 리조트는 케이블카를 씁니다")).isFalse();
    }

    @Test
    @DisplayName("근거가 비어 있으면 거부한다")
    void rejectsEmptyEvidence() {
        assertThat(EvidenceCheck.grounded(REQUEST, null)).isFalse();
        assertThat(EvidenceCheck.grounded(REQUEST, "   ")).isFalse();
        assertThat(EvidenceCheck.grounded(REQUEST, "-")).isFalse();
    }

    @Test
    @DisplayName("요청문이 없으면 판단하지 않는다")
    void rejectsWithoutRequest() {
        assertThat(EvidenceCheck.grounded(null, "호텔")).isFalse();
        assertThat(EvidenceCheck.grounded("", "호텔")).isFalse();
    }
}
