package org.hanbat.ses.dialogue.external;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 요청문 용도 판정.
 *
 * <p>이 판정이 틀리면 값 하나가 어긋나는 정도로 끝나지 않는다. {@link DataUsage#CURRENT_STATUS}
 * 로 판정된 요청은 세션이 열리지 않고 곧바로 실패하므로, 잘못 걸린 사용자는 할 수 있는 일이
 * 없다. 그래서 오탐(예측 요청을 상태 조회로 봄) 쪽을 특히 촘촘히 잠가 둔다.
 */
class DataUsageTest {

    @Nested
    @DisplayName("예측 요청을 상태 조회로 오해하지 않는다")
    class NotMistakenForStatus {

        /**
         * 회귀 방지.
         *
         * <p>낱개 어간을 부분 문자열로 맞추던 때 이 문장들이 전부 상태 조회로 분류됐다.
         * "이용"이 <b>이용객</b>·<b>이용률</b>에, "상태"가 <b>대기 상태</b>에 걸렸기 때문이다.
         * 리조트 도메인 요청까지 걸렸는데, 그 도메인은 충전소 상태와 아무 상관이 없다.
         */
        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "현재 급속충전기 3대 기준으로 대기 상태를 시뮬레이션 해줘",
                "현재 리조트 이용객 흐름을 시뮬레이션 해줘",
                "현재 계획한 구성으로 충전소 이용률을 뽑아줘",
                "실시간 데이터로 충전소 대기 시간을 시뮬레이션 해줘",
                "지금 구성으로 이용객 수를 예측해줘",
                "현재 구성으로 몇 명이 이용할 수 있는지 알려줘",
                "현재 계획 기준으로 충전 가능 대수를 뽑아줘",
        })
        void treatsThemAsSimulation(String request) {
            assertThat(DataUsage.forRequest(request)).isEqualTo(DataUsage.SIMULATION);
        }

        @Test
        @DisplayName("예측을 명시하면 시점 표현이 무엇이든 예측이다")
        void explicitSimulationIntentWins() {
            // 사용자가 "시뮬레이션 해줘"라고 적어 두었는데 거절하는 것은 어떤 해석으로도 옳지 않다.
            assertThat(DataUsage.forRequest("지금 빈자리 있는지 시뮬레이션 해줘"))
                    .isEqualTo(DataUsage.SIMULATION);
        }

        @Test
        @DisplayName("평범한 시뮬레이션 요청")
        void plainSimulationRequests() {
            assertThat(DataUsage.forRequest("전기차 충전소 시뮬레이션 돌려줘"))
                    .isEqualTo(DataUsage.SIMULATION);
            assertThat(DataUsage.forRequest("리조트 하나 시뮬레이션 돌려줘"))
                    .isEqualTo(DataUsage.SIMULATION);
        }

        @Test
        @DisplayName("시점 표현만으로는 상태 조회가 아니다")
        void nowWordAloneIsNotEnough() {
            assertThat(DataUsage.forRequest("현재 계획대로 충전소를 구성해줘"))
                    .isEqualTo(DataUsage.SIMULATION);
        }

        @Test
        @DisplayName("\"현재 구성\"의 현재는 시점이 아니라 논의 중인 안이다")
        void currentConfigurationIsNotAClock() {
            // 뒤에 오는 명사로만 갈라낼 수 있다. 시점으로 읽으면 예측 요청이 상태 조회로 뒤집힌다.
            assertThat(DataUsage.forRequest("현재 구성으로 이용 가능한 대수를 알려줘"))
                    .isEqualTo(DataUsage.SIMULATION);
            assertThat(DataUsage.forRequest("지금 기준으로 빈자리가 몇 개나 되는지 계산해줘"))
                    .isEqualTo(DataUsage.SIMULATION);
        }

        @Test
        @DisplayName("가용 여부만 묻고 시점이 없으면 예측으로 본다")
        void availabilityWithoutNowIsSimulation() {
            // "몇 대면 이용 가능한 상태가 되나" 류의 설계 질문이다.
            assertThat(DataUsage.forRequest("충전기를 몇 대 놓으면 이용 가능한 수준이 되나"))
                    .isEqualTo(DataUsage.SIMULATION);
        }
    }

    @Nested
    @DisplayName("지금의 사실을 묻는 질문은 그대로 알아본다")
    class RecognisedAsStatus {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "지금 충전소 이용 가능해?",
                "현재 이용가능한 충전기 알려줘",
                "지금 빈자리 있어?",
                "지금 충전 가능한 자리 있나요",
                "현재 비어 있는 충전기 있나",
                "실시간으로 지금 혼잡한지 알려줘",
                "지금 이 충전소를 이용할 수 있어?",
                "현재 충전할 수 있나요",
        })
        void treatsThemAsCurrentStatus(String request) {
            assertThat(DataUsage.forRequest(request)).isEqualTo(DataUsage.CURRENT_STATUS);
        }

        @Test
        @DisplayName("띄어쓰기가 달라도 같게 본다")
        void spacingDoesNotMatter() {
            assertThat(DataUsage.forRequest("지금 이용 가능해?"))
                    .isEqualTo(DataUsage.forRequest("지금 이용가능해?"))
                    .isEqualTo(DataUsage.CURRENT_STATUS);
        }
    }

    @Test
    @DisplayName("요청문이 없으면 예측으로 둔다 — 판정 근거가 없을 때 세션을 막지 않는다")
    void blankRequestFallsBackToSimulation() {
        assertThat(DataUsage.forRequest(null)).isEqualTo(DataUsage.SIMULATION);
        assertThat(DataUsage.forRequest("   ")).isEqualTo(DataUsage.SIMULATION);
    }
}
