package org.hanbat.ses.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;


import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 3 완료 기준 — 정해진 답변 스크립트를 순서대로 POST 하면 시나리오까지 도달한다.
 *
 * <p><b>LLM 도 DB 도 쓰지 않는다.</b> memory 프로파일이 DataSource 와 Flyway 자동설정을
 * 끄고 인메모리 구현을 등록하며, API 키가 없으므로 게이트웨이는 비활성 상태다.
 * 이 테스트가 통과한다는 것은 결정론적 코어가 혼자 힘으로 완결된다는 뜻이고,
 * Phase 4 에서 LLM 을 얹은 뒤에도 이 테스트는 계속 통과해야 한다 — 그것이 폴백 설계의 증거다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("memory")
class ResortDialogueE2eTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("요청 -> 대화 -> 시나리오 -> 실행 -> 결과가 한 흐름으로 이어진다")
    void fullFlow() throws Exception {
        JsonNode created = createSession("리조트 하나 시뮬레이션 돌려줘");
        String sessionId = created.get("sessionId").asText();

        // 첫 턴에는 구조 결정만 나온다. 값 슬롯은 아직 존재하지도 않는다.
        assertThat(slots(created)).containsExactlyInAnyOrder("이동설비", "숙박시설");
        assertThat(created.get("questions")).allMatch(q -> q.get("structural").asBoolean());

        // 구조를 정하면 그 아래 값 슬롯이 파생된다.
        JsonNode t1 = answer(sessionId, Map.of("이동설비", "케이블카", "숙박시설", "호텔"));
        assertThat(slots(t1)).contains("케이블카.정원", "케이블카.운행간격");
        assertThat(t1.get("derivedFrom").asText()).contains("케이블카");
        // 선택되지 않은 가지의 슬롯은 나타나지 않는다.
        assertThat(slots(t1)).doesNotContain("버스대수", "모노레일.정원", "콘도.객실수");

        JsonNode t2 = answer(sessionId, Map.of(
                "케이블카.정원", 8, "케이블카.운행간격", 3.0, "호텔.객실수", 200));

        assertThat(t2.get("outcome").asText()).isEqualTo("COMPLETE");
        assertThat(t2.get("phase").asText()).isEqualTo("BUILDING");
        assertThat(t2.get("progress").get("open").asInt()).isZero();

        // 시나리오 확정
        JsonNode scenario = post("/api/v1/sessions/" + sessionId + "/scenario", null, 201);
        // PES 에는 spec 노드가 남지 않는다 — 선택된 변형이 곧바로 자식이 된다.
        assertThat(scenario.get("summary").asText())
                .contains("리조트").contains("케이블카").contains("1일");
        assertThat(scenario.get("params").get("리조트/케이블카.정원").asInt()).isEqualTo(8);

        // 실행
        String scenarioId = scenario.get("scenarioId").asText();
        JsonNode run = post("/api/v1/scenarios/" + scenarioId + "/runs?wait=true", null, 200);

        assertThat(run.get("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode result = run.get("result");
        assertThat(result.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("summary").asText()).isNotBlank();
        assertThat(result.get("table")).isNotEmpty();
    }

    @Test
    @DisplayName("multi-aspect 를 고르면 개수 질문이, 개수를 정하면 대별 질문이 파생된다")
    void multiAspectDerivesPerInstanceQuestions() throws Exception {
        String sessionId = createSession("리조트 시뮬레이션").get("sessionId").asText();

        JsonNode t1 = answer(sessionId, Map.of("이동설비", "셔틀버스", "숙박시설", "콘도"));
        assertThat(slots(t1)).contains("버스대수");

        JsonNode t2 = answer(sessionId, Map.of("버스대수", 2));
        assertThat(slots(t2)).contains("버스.정원[0]", "버스.정원[1]");
        assertThat(slots(t2)).doesNotContain("버스.정원[2]");

        JsonNode t3 = answer(sessionId, Map.of(
                "버스.정원[0]", 25, "버스.정원[1]", 30, "콘도.객실수", 120));

        assertThat(t3.get("outcome").asText()).isEqualTo("COMPLETE");

        JsonNode scenario = post("/api/v1/sessions/" + sessionId + "/scenario", null, 201);

        // 이름이 같은 형제는 인덱스로 구분되어야 한다. 안 그러면 2호차 값이 1호차를 덮어쓴다.
        JsonNode params = scenario.get("params");
        assertThat(params.get("리조트/셔틀버스/버스[0].정원").asInt()).isEqualTo(25);
        assertThat(params.get("리조트/셔틀버스/버스[1].정원").asInt()).isEqualTo(30);

        String scenarioId = scenario.get("scenarioId").asText();
        JsonNode run = post("/api/v1/scenarios/" + scenarioId + "/runs?wait=true", null, 200);
        JsonNode stats = run.get("result").get("table");

        // 배선이 복제본 전체로 팬아웃되어야 두 대가 모두 승객을 받는다.
        assertThat(stats.toString()).contains("ent-bus#0").contains("ent-bus#1");

        // EIC 는 브로드캐스트다. 두 대가 승객을 나눠 태워야지, 같은 승객을 각각 태우면
        // 방문객 수가 숙박시설에서 두 배로 불어난다.
        int arrived = metric(stats, "ent-transducer", "arrived");
        int carried0 = metric(stats, "ent-bus#0", "carried");
        int carried1 = metric(stats, "ent-bus#1", "carried");
        assertThat(carried0 + carried1).isEqualTo(arrived);
        assertThat(carried0).isPositive();
        assertThat(carried1).isPositive();
    }

    @Test
    @DisplayName("범위를 벗어난 답은 거부하고 같은 질문을 다시 낸다")
    void rejectsOutOfRangeAnswer() throws Exception {
        String sessionId = createSession("리조트 시뮬레이션").get("sessionId").asText();
        answer(sessionId, Map.of("이동설비", "케이블카", "숙박시설", "호텔"));

        JsonNode reask = answer(sessionId, Map.of("케이블카.정원", 500));

        assertThat(reask.get("outcome").asText()).isEqualTo("REASK");
        assertThat(reask.get("issues").toString()).contains("OUT_OF_RANGE");
        assertThat(slots(reask)).contains("케이블카.정원");
    }

    @Test
    @DisplayName("시간 해상도보다 작은 간격은 실행 전에 막는다")
    void rejectsIntervalBelowTimeResolution() throws Exception {
        String sessionId = createSession("리조트 시뮬레이션").get("sessionId").asText();
        answer(sessionId, Map.of("이동설비", "케이블카", "숙박시설", "호텔"));

        // 0.5분은 선언된 범위(0.5~30) 안이지만 시간 해상도 1분보다 작다.
        JsonNode result = answer(sessionId, Map.of(
                "케이블카.정원", 8, "케이블카.운행간격", 0.5, "호텔.객실수", 200));

        assertThat(result.get("outcome").asText()).isEqualTo("REASK");
        assertThat(result.get("issues").toString()).contains("BELOW_TIME_RESOLUTION");
    }

    @Test
    @DisplayName("undo 는 트리를 되돌려 파생된 질문까지 함께 걷어낸다")
    void undoRestoresPreviousTree() throws Exception {
        String sessionId = createSession("리조트 시뮬레이션").get("sessionId").asText();
        JsonNode afterChoice = answer(sessionId, Map.of("이동설비", "케이블카", "숙박시설", "호텔"));
        assertThat(slots(afterChoice)).contains("케이블카.정원");

        JsonNode undone = post("/api/v1/sessions/" + sessionId + "/undo", null, 200);

        // 트리가 되돌아갔으므로 구조 질문이 다시 나오고 파생 값 슬롯은 사라진다.
        assertThat(slots(undone)).contains("이동설비", "숙박시설");
        assertThat(slots(undone)).doesNotContain("케이블카.정원");
    }

    @Test
    @DisplayName("SES 스냅샷은 결정된 것과 남은 것을 함께 보여 준다")
    void sesSnapshotShowsProgress() throws Exception {
        String sessionId = createSession("리조트 시뮬레이션").get("sessionId").asText();
        answer(sessionId, Map.of("이동설비", "케이블카"));

        MvcResult result = mvc.perform(get("/api/v1/sessions/" + sessionId + "/ses"))
                .andReturn();
        JsonNode snapshot = json.readTree(utf8(result));

        assertThat(snapshot.get("name").asText()).isEqualTo("리조트");
        String body = snapshot.toString();
        assertThat(body).contains("\"resolved\":\"케이블카\"");
        // 아직 안 고른 숙박시설은 선택지가 pending 으로 남아 있다.
        assertThat(body).contains("호텔").contains("콘도");
    }

    @Test
    @DisplayName("LLM 없이도 키워드만으로 템플릿이 라우팅된다")
    void routesByKeywordWithoutLlm() throws Exception {
        JsonNode created = post("/api/v1/sessions",
                Map.of("request", "휴양지 하나 만들어서 시뮬레이션 해줘"), 201);

        assertThat(created.get("sessionId")).isNotNull();
        assertThat(slots(created)).contains("이동설비");
    }

    // ------------------------------------------------------------ 헬퍼

    private JsonNode createSession(String request) throws Exception {
        return post("/api/v1/sessions",
                Map.of("request", request, "templateId", "resort-simulation"), 201);
    }

    private JsonNode answer(String sessionId, Map<String, Object> answers) throws Exception {
        return post("/api/v1/sessions/" + sessionId + "/answers", Map.of("answers", answers), 200);
    }

    private JsonNode post(String url, Object body, int expectedStatus) throws Exception {
        var request = MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request = request.content(json.writeValueAsString(body));
        }
        MvcResult result = mvc.perform(request).andReturn();
        String responseBody = utf8(result);
        assertThat(result.getResponse().getStatus())
                .as("응답 본문: %s", responseBody)
                .isEqualTo(expectedStatus);
        return json.readTree(responseBody);
    }

    /**
     * MockHttpServletResponse 의 기본 문자셋은 ISO-8859-1 이다.
     * 컨트롤러는 UTF-8 바이트를 쓰므로 바이트를 직접 읽어야 한글이 깨지지 않는다.
     */
    private String utf8(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** 결과 표에서 컴포넌트 하나의 지표를 꺼낸다. */
    private int metric(JsonNode table, String component, String key) {
        for (JsonNode row : table) {
            if (component.equals(row.path("component").asText())) {
                return row.path(key).asInt();
            }
        }
        throw new AssertionError(component + " 의 " + key + " 를 결과 표에서 찾지 못했습니다.");
    }

    private List<String> slots(JsonNode response) {
        return response.findValues("slot").stream().map(JsonNode::asText).toList();
    }
}
