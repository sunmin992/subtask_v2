package org.hanbat.ses.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.hanbat.ses.core.json.SesJson;
import org.hanbat.ses.core.model.AxisType;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.pes.Pes;
import org.hanbat.ses.core.pes.PesBuilder;
import org.hanbat.ses.core.prune.PruningEngine;
import org.hanbat.ses.core.resolve.SlotResolver;
import org.hanbat.ses.dialogue.session.Phase;
import org.hanbat.ses.dialogue.session.SessionState;
import org.hanbat.ses.dialogue.session.SessionStore;
import org.hanbat.ses.scenario.model.RunStore;
import org.hanbat.ses.scenario.model.Scenario;
import org.hanbat.ses.scenario.model.ScenarioStore;
import org.hanbat.ses.scenario.model.SimulationRun;
import org.hanbat.ses.template.model.SimulatorConfig;
import org.hanbat.ses.template.registry.SesRegistry;
import org.hanbat.ses.template.registry.TemplateRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 0 완료 기준 — 실제 PostgreSQL 에 Flyway 마이그레이션이 적용되고
 * JSONB 폴리모픽 직렬화가 왕복한다.
 *
 * <p>SES 트리는 sealed interface 계층이라 판별자 없이는 되읽을 수 없다.
 * 이 왕복이 깨지면 세션을 다시 읽는 순간 대화가 죽는데, 인메모리 테스트로는
 * 절대 드러나지 않는다 — 그때는 같은 객체를 그대로 돌려받기 때문이다.
 *
 * <p>Docker 가 필요하므로 {@code integration} 태그를 붙여 기본 실행에서 제외한다.
 * {@code ./gradlew :app:integrationTest} 로 돌린다.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
class PostgresPersistenceIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ses_scenario")
                    .withUsername("ses")
                    .withPassword("ses");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.persistence", () -> "jpa");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SessionStore sessions;

    @Autowired
    private ScenarioStore scenarios;

    @Autowired
    private RunStore runs;

    @Autowired
    private SesRegistry sesDefinitions;

    @Autowired
    private TemplateRegistry templates;

    @Test
    @DisplayName("Flyway 마이그레이션이 모든 테이블을 만든다")
    void migrationCreatesSchema() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("subtask_template", "ses_definition", "model_base",
                "dialogue_session", "scenario", "simulation_run", "llm_call_log");

        Integer models = jdbc.queryForObject("SELECT count(*) FROM model_base", Integer.class);
        assertThat(models).isEqualTo(11); // 리조트 7개 + 충전소 4개
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'scenario'", String.class)).contains("data_evidence");
    }

    @Test
    @DisplayName("부분 pruning 된 SES 트리가 JSONB 를 왕복해도 동일하다")
    void sesTreeRoundTripsThroughJsonb() {
        SesNode pruned = new PruningEngine().apply(seededTree(),
                SesAnchor.node("리조트/이동설비", AxisType.SPECIALIZATION, "spec-transport"),
                "셔틀버스");
        pruned = new PruningEngine().apply(pruned,
                SesAnchor.node("리조트/이동설비/셔틀버스/버스", AxisType.MULTI_ASPECT, "multi-bus"), 3);

        UUID sessionId = UUID.randomUUID();
        SessionState saved = SessionState.start(sessionId, TEMPLATE_ID, "1.0.0",
                "리조트 시뮬레이션", pruned);
        var provenance = org.hanbat.ses.dialogue.external.SlotProvenance.userAnswer("이동설비");
        sessions.save(saved.advance(pruned, Phase.ELICITING,
                Map.of("이동설비", "셔틀버스"), Map.of("이동설비", provenance), List.of(), List.of(), "테스트"));

        SessionState loaded = sessions.require(sessionId);

        assertThat(SesJson.write(loaded.workingSes())).isEqualTo(SesJson.write(pruned));
        // 복제본이 살아 있어야 대별 슬롯이 다시 계산된다.
        assertThat(new SlotResolver().scan(loaded.workingSes()))
                .extracting(s -> s.slotName())
                .contains("ent-bus#0.정원", "ent-bus#1.정원", "ent-bus#2.정원");
        assertThat(loaded.answers()).containsEntry("이동설비", "셔틀버스");
        assertThat(loaded.provenance()).containsEntry("이동설비", provenance);
        assertThat(loaded.history()).hasSize(1);
    }

    @Test
    @DisplayName("템플릿과 도메인 SES 는 기동 시 등록되어 다시 읽힌다")
    void seededDomainIsPersisted() {
        assertThat(sesDefinitions.find(SES_ID)).isPresent();
        assertThat(templates.findActive(TEMPLATE_ID)).isPresent();

        var template = templates.findActive(TEMPLATE_ID).orElseThrow();
        // sealed interface 인 SlotSpec 도 판별자를 달고 왕복해야 한다.
        assertThat(template.slots()).hasSize(10);
        assertThat(template.slots().get(0)).isInstanceOf(
                org.hanbat.ses.template.model.StructuralSlot.class);
        assertThat(template.execution().simulator().horizon()).isEqualTo(1440.0);
    }

    @Test
    @DisplayName("시나리오와 실행 기록이 JSONB 로 저장되고 되읽힌다")
    void scenarioAndRunRoundTrip() {
        SesNode resolved = resolvedTree();
        Pes pes = new PesBuilder().build(resolved);

        UUID sessionId = UUID.randomUUID();
        sessions.save(SessionState.start(sessionId, TEMPLATE_ID, "1.0.0",
                "리조트", resolved));

        Scenario scenario = new Scenario(UUID.randomUUID(), sessionId,
                TEMPLATE_ID, pes,
                Map.of("리조트/케이블카.정원", 8), SimulatorConfig.defaults(),
                org.hanbat.ses.template.model.OutputSpec.defaults(), "요약", java.time.Instant.now())
                .withDataEvidence(Map.of("usage", "SIMULATION", "provenance", List.of(Map.of(
                        "slot", "time", "observedAt", "2026-09-08T00:00:00Z",
                        "quality", Map.of("assumed", true, "stale", false)))));
        scenarios.save(scenario);

        Scenario loaded = scenarios.require(scenario.scenarioId());
        assertThat(loaded.pes().leaves()).extracting(n -> n.name())
                .containsExactlyInAnyOrder("방문객생성기", "집계기", "케이블카", "호텔");
        assertThat(loaded.summary()).isEqualTo("요약");
        assertThat(loaded.dataEvidence()).isEqualTo(scenario.dataEvidence());

        SimulationRun run = runs.save(
                SimulationRun.queued(UUID.randomUUID(), scenario.scenarioId())
                        .running().succeeded(Map.of("status", "COMPLETED", "eventCount", 42)));

        assertThat(runs.require(run.runId()).result()).containsEntry("eventCount", 42);
    }

    private static final String SES_ID = "resort-ses";
    private static final String TEMPLATE_ID = "resort-simulation";

    /** 시드 리소스로 등록된 도메인 트리. Java 상수가 아니라 등록된 데이터를 쓴다. */
    private SesNode seededTree() {
        return sesDefinitions.find(SES_ID).orElseThrow().tree();
    }

    private SesNode resolvedTree() {
        PruningEngine engine = new PruningEngine();
        SesNode t = engine.apply(seededTree(),
                SesAnchor.node("", AxisType.SPECIALIZATION, "spec-transport"), "케이블카");
        t = engine.apply(t, SesAnchor.node("", AxisType.SPECIALIZATION, "spec-lodging"), "호텔");
        t = engine.apply(t, SesAnchor.variable("", "ent-cablecar", "정원"), 8);
        t = engine.apply(t, SesAnchor.variable("", "ent-cablecar", "운행간격"), 3.0);
        t = engine.apply(t, SesAnchor.variable("", "ent-hotel", "객실수"), 200);
        return t;
    }
}
