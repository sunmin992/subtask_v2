package org.hanbat.ses.llm.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.hanbat.ses.llm.audit.LlmCallRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 실제 로컬 오픈 모델에 붙여 보는 테스트.
 *
 * <pre>
 *   ollama serve &amp;&amp; ollama pull qwen2.5:7b
 *   ./gradlew :modules:llm:liveTest
 * </pre>
 *
 * <p>{@code live} 태그로 기본 실행에서 제외한다. 모델 응답은 비결정적이고 느리며,
 * 실패해도 대개 우리 코드 문제가 아니다. 그래도 하나는 있어야 한다 — 스텁으로는
 * "이 모델이 정말 스키마대로 JSON 을 주는가"를 영원히 알 수 없기 때문이다.
 *
 * <p>그래서 단정을 느슨하게 잡았다. 확인하려는 것은 모델의 품질이 아니라
 * <b>요청이 오가고 응답이 record 로 들어온다</b>는 사실이다.
 */
@Tag("live")
class OllamaLiveTest {

    private static final String BASE_URL = pick("ollama.url", "OLLAMA_URL",
            "http://localhost:11434");
    private static final String MODEL = pick("ollama.model", "OLLAMA_MODEL", "qwen2.5:7b");

    /** 시스템 프로퍼티 우선, 그다음 환경변수. 모델을 바꿔 시험하기 쉽게 둘 다 받는다. */
    private static String pick(String property, String env, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 라우팅이 채워야 하는 구조. */
    record RoutingAnswer(String templateId, double confidence, String reason) {
    }

    /** 슬롯 추출이 채워야 하는 구조 — 중첩 리스트까지 모델이 맞출 수 있는지 본다. */
    record Extraction(List<Extracted> values) {
    }

    record Extracted(String slot, String value, double confidence, String evidence) {
    }

    private OpenAiCompatibleLlmGateway gateway() {
        LlmProperties props = new LlmProperties(LlmProvider.OPENAI_COMPATIBLE, null,
                BASE_URL, MODEL, java.util.Map.of(), 0.1, 2048, 180_000L, 1, true, 3);
        return new OpenAiCompatibleLlmGateway(
                WebClient.builder().baseUrl(BASE_URL).build(),
                new ObjectMapper(), LlmCallRecorder.noop(), props);
    }

    @org.junit.jupiter.api.BeforeAll
    static void announce() {
        System.out.println("[liveTest] " + BASE_URL + " / model=" + MODEL);
    }

    @Test
    @DisplayName("실제 모델이 라우팅 스키마대로 응답한다")
    void routesAgainstRealModel() {
        RoutingAnswer answer = gateway().complete("""
                당신은 시뮬레이션 요청을 서브태스크 템플릿으로 분류하는 라우터입니다.
                주어진 후보 중에서 요청에 가장 잘 맞는 것 하나를 고르세요.
                어느 것도 맞지 않으면 templateId 를 빈 문자열로 두고 confidence 를 0 으로 하세요.
                """, """
                사용자 요청:
                리조트에 케이블카를 놓고 방문객 흐름을 시뮬레이션 해줘

                후보 템플릿:
                - id: resort-simulation
                  이름: 리조트 운영 시뮬레이션
                  설명: 리조트의 숙박·이동·편의시설 구성을 정하고 방문객 흐름을 시뮬레이션
                - id: factory-simulation
                  이름: 공장 라인 시뮬레이션
                  설명: 생산 라인의 설비 구성을 정하고 처리량을 시뮬레이션
                """, RoutingAnswer.class, Purpose.ROUTING);

        assertThat(answer).isNotNull();
        assertThat(answer.templateId()).isEqualTo("resort-simulation");
        assertThat(answer.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("실제 모델이 요청문에서 슬롯 값을 뽑아 중첩 스키마로 돌려준다")
    void extractsSlotsAgainstRealModel() {
        Extraction result = gateway().complete("""
                당신은 사용자의 시뮬레이션 요청문에서 파라미터를 추출하는 도구입니다.
                주어진 슬롯 목록 각각에 대해, 요청문에 명시적으로 드러난 값만 채우세요.
                문장에 없는 값을 상식으로 지어내지 마세요. 확실하지 않으면 그 슬롯을 결과에서 빼세요.
                숫자는 단위를 제거한 순수한 수로 주세요.
                """, """
                요청문:
                리조트에 8인승 케이블카를 3분 간격으로 놓고, 호텔은 200실로 해줘

                채울 수 있는 슬롯:
                - slot: 이동설비
                  선택지: [케이블카, 셔틀버스, 모노레일]
                - slot: 케이블카.정원
                  타입: INT, 단위: 명, 허용 범위: 1.0 ~ 200.0
                - slot: 케이블카.운행간격
                  타입: DOUBLE, 단위: 분, 허용 범위: 0.5 ~ 30.0
                - slot: 호텔.객실수
                  타입: INT, 단위: 실, 허용 범위: 1.0 ~ 2000.0
                """, Extraction.class, Purpose.EXTRACTION);

        assertThat(result).isNotNull();
        assertThat(result.values()).isNotEmpty();

        // 문장에 또렷하게 드러난 값 하나만 확인한다. 모델 품질을 재는 자리가 아니다.
        assertThat(result.values())
                .anySatisfy(v -> {
                    assertThat(v.slot()).isNotBlank();
                    assertThat(v.value()).isNotBlank();
                });
    }

    @Test
    @DisplayName("자유 텍스트 호출은 JSON 모드 없이 문장을 돌려준다")
    void returnsPlainTextWithoutJsonMode() {
        String text = gateway().text(
                "당신은 시뮬레이션 결과를 한국어로 다듬는 편집자입니다. 한 문장으로만 답하세요.",
                "케이블카 정원 8명, 운행 간격 3분으로 24시간 시뮬레이션했습니다. 자연스럽게 다듬어 주세요.",
                Purpose.PHRASING);

        assertThat(text).isNotBlank();
    }
}
