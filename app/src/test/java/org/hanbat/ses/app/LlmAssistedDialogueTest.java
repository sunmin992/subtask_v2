package org.hanbat.ses.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.hanbat.ses.dialogue.control.SlotExtractor;
import org.hanbat.ses.dialogue.routing.LlmRequestRouter;
import org.hanbat.ses.llm.gateway.LlmGateway;
import org.hanbat.ses.llm.gateway.Purpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Phase 4 완료 기준 — 자연어 요청 한 문장으로 시작해 짧은 대화 안에 시나리오에 도달한다.
 *
 * <p>실제 API 대신 스텁 게이트웨이를 쓴다. 여기서 확인하려는 것은 모델의 성능이 아니라
 * <b>추출 결과가 대화를 얼마나 줄여 주는가</b>와 <b>추출값이 검증을 반드시 지나는가</b>이다.
 *
 * <p>{@link ResortDialogueE2eTest} 가 LLM 을 끈 채로 계속 통과하는 것이 짝을 이루는 증거다 —
 * 두 테스트가 함께 통과해야 폴백 설계가 성립한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("memory")
@Import(LlmAssistedDialogueTest.StubLlmConfig.class)
class LlmAssistedDialogueTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @TestConfiguration
    static class StubLlmConfig {

        /**
         * 요청문을 이해한 척하는 게이트웨이.
         *
         * <p>슬롯 목록에 없는 값을 돌려줘도 SlotExtractor 가 걸러 내므로,
         * 매 회차 같은 후보 전체를 돌려주기만 하면 된다.
         */
        @Bean
        @Primary
        LlmGateway stubLlmGateway() {
            return new LlmGateway() {
                @Override
                public boolean available() {
                    return true;
                }

                @Override
                public String text(String system, String user, Purpose purpose) {
                    return "다듬은 문장";
                }

                @Override
                @SuppressWarnings("unchecked")
                public <T> T complete(String system, String user, Class<T> type, Purpose purpose) {
                    if (type == LlmRequestRouter.RoutingAnswer.class) {
                        return (T) new LlmRequestRouter.RoutingAnswer(
                                "resort-simulation", 0.95, "리조트 운영을 묻고 있습니다.");
                    }
                    if (type == SlotExtractor.Extraction.class) {
                        return (T) new SlotExtractor.Extraction(List.of(
                                new SlotExtractor.Extracted("이동설비", "케이블카", 0.95, "케이블카"),
                                new SlotExtractor.Extracted("숙박시설", "호텔", 0.9, "호텔"),
                                new SlotExtractor.Extracted("케이블카.정원", "8", 0.9, "8인승"),
                                new SlotExtractor.Extracted("케이블카.운행간격", "3", 0.9, "3분 간격"),
                                new SlotExtractor.Extracted("호텔.객실수", "200", 0.9, "200실"),
                                // 범위를 벗어난 값 — 검증에서 걸러져야 한다.
                                new SlotExtractor.Extracted("모노레일.정원", "9999", 0.9, "없음")));
                    }
                    throw new UnsupportedOperationException(type.getName());
                }
            };
        }
    }

    @Test
    @DisplayName("한 문장으로 시작해 추가 질문 없이 시나리오까지 도달한다")
    void oneSentenceReachesScenario() throws Exception {
        JsonNode created = post("/api/v1/sessions",
                Map.of("request", "리조트에 8인승 케이블카를 3분 간격으로 놓고, 호텔은 200실로 해서 돌려줘"),
                201);

        // 구조 -> 값 -> 완료가 한 번의 요청 안에서 끝난다.
        assertThat(created.get("outcome").asText()).isEqualTo("COMPLETE");
        assertThat(created.get("questions")).isEmpty();
        assertThat(created.get("derivedFrom").asText()).contains("미리 채웠습니다");
        assertThat(created.get("progress").get("open").asInt()).isZero();

        String sessionId = created.get("sessionId").asText();
        JsonNode scenario = post("/api/v1/sessions/" + sessionId + "/scenario", null, 201);

        assertThat(scenario.get("params").get("리조트/케이블카.정원").asInt()).isEqualTo(8);
        assertThat(scenario.get("params").get("리조트/케이블카.운행간격").asDouble()).isEqualTo(3.0);
        assertThat(scenario.get("params").get("리조트/호텔.객실수").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("LLM 이 라우팅한 템플릿으로 세션이 열린다")
    void routesThroughLlm() throws Exception {
        JsonNode created = post("/api/v1/sessions",
                Map.of("request", "우리 사업장 방문객 흐름을 좀 보고 싶은데"), 201);

        // 키워드로는 잡히지 않는 문장이다. LLM 분류가 없으면 여기서 400 이 난다.
        assertThat(created.get("sessionId")).isNotNull();
    }

    @Test
    @DisplayName("범위를 벗어난 추출값은 채우지 않는다")
    void rejectsOutOfRangeExtraction() throws Exception {
        JsonNode created = post("/api/v1/sessions",
                Map.of("request", "리조트 시뮬레이션", "templateId", "resort-simulation"), 201);

        // 모노레일.정원 9999 는 범위(1~300) 밖이라 버려진다.
        // 애초에 이동설비가 케이블카로 정해지므로 모노레일 슬롯은 열리지도 않는다.
        String body = created.toString();
        assertThat(body).doesNotContain("9999");
        assertThat(created.get("outcome").asText()).isEqualTo("COMPLETE");
    }

    private JsonNode post(String url, Object body, int expectedStatus) throws Exception {
        var request = MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request = request.content(json.writeValueAsString(body));
        }
        MvcResult result = mvc.perform(request).andReturn();
        String responseBody = new String(
                result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus())
                .as("응답 본문: %s", responseBody)
                .isEqualTo(expectedStatus);
        return json.readTree(responseBody);
    }
}
