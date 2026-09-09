package org.hanbat.ses.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 전기차 충전소 도메인 E2E — 두 번째 도메인이 <b>코드 수정 없이</b> 들어왔는지 확인한다.
 *
 * <p>NFR-04("새 도메인 추가에 코드 수정이 없어야 한다")는 아키텍처 문서에 적어 두는 것만으로는
 * 참이 되지 않는다. 실제로 도메인을 하나 더 넣어 보면 대개 어딘가에서 한 줄이 필요해지고,
 * 그 한 줄이 쌓여 확장 축이 닫힌다. 이 도메인이 추가한 자바 코드는 원자 모델 구현과 그
 * 팩토리뿐이고 — 그것은 모델의 거동이라 데이터로 쓸 수 없다 — 나머지는 전부 {@code seed/}
 * 아래 JSON 이다. 라우팅·질문 생성·검증·실행 경로에는 충전소를 아는 코드가 한 줄도 없다.
 *
 * <p>리조트 E2E 와 함께 통과해야 의미가 있다. 하나만 통과하면 그 도메인에 맞춰 굳은 코드일 뿐이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("memory")
class EvChargingDialogueE2eTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void currentStatusCannotFallBackToSimulationOrBeExecuted() throws Exception {
        for (Map<String, Object> body : List.of(
                Map.<String, Object>of("request", "지금 이 충전소를 이용할 수 있어?", "templateId", "ev-charging"),
                Map.<String, Object>of("request", "충전소 확인", "templateId", "ev-charging", "usage", "CURRENT_STATUS"))) {
            JsonNode response = post("/api/v1/sessions", body, 201);
            String id = response.get("sessionId").asText();
            assertThat(response.get("outcome").asText()).isEqualTo("FAILED");
            assertThat(response.get("issues").toString()).contains("CURRENT_STATUS_UNAVAILABLE");
            assertThat(response.get("provenance").isEmpty()).isTrue();
            assertThat(get("/api/v1/sessions/" + id).get("outcome").asText()).isEqualTo("FAILED");
            assertThat(answer(id, Map.of()).get("outcome").asText()).isEqualTo("FAILED");
            assertThat(post("/api/v1/sessions/" + id + "/undo", null, 200).get("outcome").asText()).isEqualTo("FAILED");
            MvcResult blocked = mvc.perform(MockMvcRequestBuilders.post("/api/v1/sessions/" + id + "/scenario")).andReturn();
            assertThat(blocked.getResponse().getStatus()).isBetween(400, 499);
        }
    }

    @Test
    @DisplayName("요청 -> 대화 -> 시나리오 -> 실행이 충전소 도메인에서도 한 흐름으로 이어진다")
    void fullFlow() throws Exception {
        JsonNode created = createSession("전기차 충전소 시뮬레이션 돌려줘");
        String sessionId = created.get("sessionId").asText();

        // 첫 턴은 구조 결정뿐이다. 충전기 종류와 대기 정책이 정해지기 전에는
        // 그 아래 값 슬롯이 트리에 존재하지도 않는다.
        assertThat(slots(created)).containsExactlyInAnyOrder("충전기종류", "대기정책");

        JsonNode t1 = answer(sessionId, Map.of("충전기종류", "급속충전기", "대기정책", "무한대기"));
        assertThat(slots(t1)).contains("급속충전기대수");
        // 고르지 않은 가지는 나타나지 않는다.
        assertThat(slots(t1)).doesNotContain("완속충전기대수", "유한대기.최대대기대수");

        JsonNode t2 = answer(sessionId, Map.of("급속충전기대수", 3));
        assertThat(t2.get("outcome").asText()).isEqualTo("COMPLETE");

        JsonNode scenario = post("/api/v1/sessions/" + sessionId + "/scenario", null, 201);
        assertThat(scenario.get("summary").asText()).contains("충전소");
        assertThat(scenario.get("dataEvidence").get("provenance").isEmpty()).isFalse();

        String scenarioId = scenario.get("scenarioId").asText();
        JsonNode run = post("/api/v1/scenarios/" + scenarioId + "/runs?wait=true", null, 200);

        assertThat(run.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.get("result").get("dataEvidence")).isEqualTo(scenario.get("dataEvidence"));
        assertThat(run.get("result").get("summary").asText()).contains("현재 현황을 나타내지 않습니다");
        JsonNode table = run.get("result").get("table");

        // 공유 대기열이 세 대에 나눠 배정했는가. 한 대만 일하고 있으면 배선이 틀린 것이다.
        int served = metric(table, "ent-unbounded-queue", "served");
        int completed0 = metric(table, "ent-fast-charger#0", "completed");
        int completed1 = metric(table, "ent-fast-charger#1", "completed");
        int completed2 = metric(table, "ent-fast-charger#2", "completed");

        assertThat(served).isPositive();
        assertThat(completed0 + completed1 + completed2).isEqualTo(served);
        assertThat(completed0).isPositive();

        // 브로드캐스트 배정을 걸러내지 못하면 여기가 0 이 아니고 차량 수가 부풀어 오른다.
        for (int i = 0; i < 3; i++) {
            assertThat(metric(table, "ent-fast-charger#" + i, "misroutedAssignments"))
                    .as("charger#%d 오배정", i)
                    .isZero();
        }
    }

    @Test
    @DisplayName("파생 슬롯이 충전기 종류에 따라 갈라진다")
    void derivedSlotsBranchByChargerType() throws Exception {
        String fast = createSession("급속 충전소 시뮬레이션").get("sessionId").asText();
        JsonNode fastTurn = answer(fast, Map.of("충전기종류", "급속충전기", "대기정책", "무한대기"));

        String slow = createSession("완속 충전소 시뮬레이션").get("sessionId").asText();
        JsonNode slowTurn = answer(slow, Map.of("충전기종류", "완속충전기", "대기정책", "무한대기"));

        // 같은 자리에서 갈라진 두 가지가 서로 다른 슬롯을 낳는다.
        assertThat(slots(fastTurn)).contains("급속충전기대수").doesNotContain("완속충전기대수");
        assertThat(slots(slowTurn)).contains("완속충전기대수").doesNotContain("급속충전기대수");

        // 대수를 정하면 복제본마다 값 슬롯이 파생된다 — 다만 급속기의 충전시간은
        // 내장 데이터셋이 알고 있어 묻지 않고, 완속기는 대표값이 없어 복제본별로 묻는다.
        assertThat(answer(fast, Map.of("급속충전기대수", 2)).get("outcome").asText())
                .isEqualTo("COMPLETE");
        assertThat(slots(answer(slow, Map.of("완속충전기대수", 2))))
                .containsExactlyInAnyOrder("완속기.평균충전시간[0]", "완속기.평균충전시간[1]");
    }

    @Test
    @DisplayName("대기 정책을 유한대기로 고르면 대기 자리 수를 되묻는다")
    void boundedQueuePolicyDerivesCapacitySlot() throws Exception {
        String sessionId = createSession("충전소 시뮬레이션").get("sessionId").asText();

        // 구조 우선 정책 때문에 값 슬롯은 구조가 다 정해진 다음 턴에 나온다.
        JsonNode t1 = answer(sessionId, Map.of("충전기종류", "급속충전기", "대기정책", "유한대기"));
        assertThat(slots(t1)).containsExactly("급속충전기대수");

        JsonNode t2 = answer(sessionId, Map.of("급속충전기대수", 2));
        assertThat(slots(t2)).contains("유한대기.최대대기대수");

        JsonNode t3 = answer(sessionId, Map.of("유한대기.최대대기대수", 3));
        assertThat(t3.get("outcome").asText()).isEqualTo("COMPLETE");

        JsonNode scenario = post("/api/v1/sessions/" + sessionId + "/scenario", null, 201);
        String scenarioId = scenario.get("scenarioId").asText();
        JsonNode run = post("/api/v1/scenarios/" + scenarioId + "/runs?wait=true", null, 200);
        JsonNode table = run.get("result").get("table");

        // 정책이 실제로 작동하려면 대기열이 한도를 넘지 않아야 한다.
        assertThat(metric(table, "ent-bounded-queue", "maxWaiting")).isEqualTo(3);
        assertThat(metric(table, "ent-bounded-queue", "stillWaiting")).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("내장 데이터셋이 채운 값은 출처와 함께 응답에 실린다")
    void externalDataFillsValueAndTagsProvenance() throws Exception {
        String sessionId = createSession("급속 충전소 시뮬레이션").get("sessionId").asText();
        answer(sessionId, Map.of("충전기종류", "급속충전기", "대기정책", "무한대기"));
        JsonNode afterCount = answer(sessionId, Map.of("급속충전기대수", 2));

        JsonNode provenance = afterCount.get("provenance");
        JsonNode charging = find(provenance, "급속기.평균충전시간[0]");

        assertThat(charging).as("급속기.평균충전시간 의 출처 기록").isNotNull();
        assertThat(charging.get("source").asText()).isEqualTo("EXTERNAL_DATA");
        // 실시간 조회는 꺼져 있고 캐시는 비어 있으므로 3단까지 내려가야 한다.
        assertThat(charging.get("tier").asText()).isEqualTo("BUNDLED");
        assertThat(charging.get("sourceId").asText()).isEqualTo("evcharge-reference-2026H1");
        assertThat(charging.get("stated").asBoolean()).isFalse();
        assertThat(charging.get("fallback").asBoolean()).isTrue();
        assertThat(charging.get("quality").get("assumed").asBoolean()).isTrue();
        assertThat(charging.get("observedAt").asText()).isNotBlank();

        // 사용자가 말하지 않은 값을 채웠다는 사실은 화면에도 알린다.
        assertThat(afterCount.get("issues").toString()).contains("FILLED_FROM_EXTERNAL_DATA");

        // 사용자가 직접 답한 구조 결정은 사용자 답변으로 찍힌다.
        JsonNode chargerType = find(provenance, "충전기종류");
        assertThat(chargerType).isNotNull();
        assertThat(chargerType.get("source").asText()).isEqualTo("USER_ANSWER");
        assertThat(chargerType.get("stated").asBoolean()).isTrue();
    }

    /**
     * 외부 데이터로 채워진 값은 그 턴에 슬롯이 닫히므로 곧바로 고쳐 넣을 수 없다.
     *
     * <p>바람직한 거동은 아니지만 자동 채움 전반의 성질이고(LLM 추출도 같다), 조용히 넘어가는
     * 것보다 시험으로 붙잡아 두는 편이 낫다. 사용자가 값을 되돌릴 방법은 지금은 되돌리기뿐이다.
     */
    @Test
    @DisplayName("외부 데이터가 채운 슬롯은 닫히고, 그 값이 시나리오까지 간다")
    void externallyFilledSlotClosesAndReachesTheScenario() throws Exception {
        String sessionId = createSession("급속 충전소 시뮬레이션").get("sessionId").asText();
        answer(sessionId, Map.of("충전기종류", "급속충전기", "대기정책", "무한대기"));
        JsonNode done = answer(sessionId, Map.of("급속충전기대수", 1));

        assertThat(done.get("outcome").asText()).isEqualTo("COMPLETE");

        JsonNode late = answer(sessionId, Map.of("급속기.평균충전시간[0]", 20.0));
        assertThat(late.get("issues").toString()).contains("SLOT_NOT_OPEN");

        // 형제가 하나뿐이면 파라미터 경로에 인덱스가 붙지 않는다.
        JsonNode scenario = post("/api/v1/sessions/" + sessionId + "/scenario", null, 201);
        assertThat(scenario.get("params").get("충전소/급속충전기/급속기.평균충전시간").asDouble())
                .isEqualTo(34.0);
    }

    @Test
    @DisplayName("완속기에 급속기의 충전시간 범위를 답하면 거부한다")
    void rejectsOutOfRangeForTheChosenType() throws Exception {
        String sessionId = createSession("완속 충전소 시뮬레이션").get("sessionId").asText();
        answer(sessionId, Map.of("충전기종류", "완속충전기", "대기정책", "무한대기"));
        answer(sessionId, Map.of("완속충전기대수", 2));

        // 30분은 급속기(5~120)에서는 정상이지만 완속기(60~600)에서는 범위 밖이다.
        JsonNode reask = answer(sessionId, Map.of("완속기.평균충전시간[0]", 30.0));

        assertThat(reask.get("outcome").asText()).isEqualTo("REASK");
        assertThat(reask.get("issues").toString()).contains("OUT_OF_RANGE");
    }

    /**
     * 지금의 사실을 묻는 질문에는 시뮬레이션으로 답하지 않는다.
     *
     * <p>이 시스템이 낼 수 있는 것은 가정한 구성의 예측뿐이다. 그것을 현재 상태처럼 돌려주면
     * 사용자는 예측을 사실로 읽는다. 실시간 상태 API 가 붙기 전까지는 "확인할 수 없다"가
     * 유일하게 정직한 답이다.
     */
    @Test
    @DisplayName("지금 이용 가능한지 묻는 질문은 예측으로 대신 답하지 않는다")
    void currentStatusQuestionIsRefusedInsteadOfSimulated() throws Exception {
        JsonNode refused = post("/api/v1/sessions",
                Map.of("request", "지금 충전소 이용 가능해?", "templateId", "ev-charging"), 201);

        assertThat(refused.get("outcome").asText()).isEqualTo("FAILED");
        assertThat(refused.get("issues").toString()).contains("CURRENT_STATUS_UNAVAILABLE");
        // 어느 도메인에서도 참인 문구여야 한다 — 판정은 라우팅 전에 일어난다.
        assertThat(refused.get("issues").toString()).doesNotContain("충전소별");
    }

    @Test
    @DisplayName("충전기 대수만 바꾼 두 시나리오는 같은 SES 에서 나온다")
    void variantsShareOneStructure() throws Exception {
        JsonNode ses = get("/api/v1/ses-definitions/evcharge-ses");

        // 변형 두 가지가 specialization 하나 아래에 함께 있다.
        // 레이아웃마다 별도 서술을 만드는 방식과 갈리는 지점이 여기다.
        String body = ses.toString();
        assertThat(body).contains("spec-charger").contains("급속충전기").contains("완속충전기");
        assertThat(body).contains("spec-queue").contains("무한대기").contains("유한대기");
    }

    // ------------------------------------------------------------ 헬퍼

    private JsonNode createSession(String request) throws Exception {
        return post("/api/v1/sessions",
                Map.of("request", request, "templateId", "ev-charging"), 201);
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

    private JsonNode get(String url) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get(url)).andReturn();
        return json.readTree(utf8(result));
    }

    /** MockHttpServletResponse 의 기본 문자셋이 ISO-8859-1 이라 바이트를 직접 읽는다. */
    private String utf8(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private List<String> slots(JsonNode response) {
        List<String> out = new ArrayList<>();
        response.get("questions").forEach(q -> out.add(q.get("slot").asText()));
        return out;
    }

    private JsonNode find(JsonNode provenance, String slot) {
        for (JsonNode entry : provenance) {
            if (slot.equals(entry.get("slot").asText())) {
                return entry;
            }
        }
        return null;
    }

    /** 결과 표의 행은 component 열 하나에 지표들이 평평하게 붙어 있다. */
    private int metric(JsonNode table, String component, String key) {
        for (JsonNode row : table) {
            if (row.get("component").asText().equals(component)) {
                JsonNode value = row.get(key);
                if (value == null) {
                    throw new AssertionError(component + " 행에 " + key + " 지표가 없습니다: " + row);
                }
                return value.asInt();
            }
        }
        throw new AssertionError("결과 표에 " + component + " 이(가) 없습니다: " + table);
    }
}
