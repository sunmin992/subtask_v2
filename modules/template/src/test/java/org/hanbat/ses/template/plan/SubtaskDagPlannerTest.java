package org.hanbat.ses.template.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.hanbat.ses.template.model.Dependency;
import org.hanbat.ses.template.model.DialogueControl;
import org.hanbat.ses.template.model.ExecutionSpec;
import org.hanbat.ses.template.model.OutputSpec;
import org.hanbat.ses.template.model.Routing;
import org.hanbat.ses.template.model.StructureBinding;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hanbat.ses.template.model.ValidationSpec;
import org.hanbat.ses.template.registry.InMemoryRegistries;
import org.hanbat.ses.template.registry.TemplateRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-103 — 선행 의존이 있는 템플릿을 위상 정렬해 서브태스크 DAG 를 구성한다.
 *
 * <p>순환 검출을 별도로 확인하는 이유: 위상 정렬은 순환이 있으면 조용히 노드를 빼고
 * 끝난다. 그러면 "왜 이 서브태스크가 계획에 없지"로만 드러나고 원인은 안 보인다.
 */
class SubtaskDagPlannerTest {

    private final TemplateRegistry registry = new InMemoryRegistries.Templates();
    private final SubtaskDagPlanner planner = new SubtaskDagPlanner(registry);

    private void given(String id, String name, Dependency... prerequisites) {
        registry.register(new SubtaskTemplate(id, "1.0.0", name,
                new Routing(List.of(), "설명", 1, List.of(prerequisites)),
                List.of(), DialogueControl.defaults(),
                new StructureBinding("ses", "루트", Map.of(), List.of()),
                ValidationSpec.empty(), ExecutionSpec.defaults(), OutputSpec.defaults(), null));
    }

    private static Dependency needs(String id) {
        return new Dependency(id, "선행 필요", true);
    }

    @Test
    @DisplayName("선행이 없으면 목표 하나만 나온다")
    void singleTemplateNeedsNoDag() {
        given("solo", "단독 작업");

        SubtaskDagPlanner.Plan plan = planner.plan("solo");

        assertThat(plan.executable()).isTrue();
        assertThat(plan.single()).isTrue();
        assertThat(plan.order()).extracting(SubtaskDagPlanner.Step::templateId)
                .containsExactly("solo");
        assertThat(plan.order().get(0).reason()).isEqualTo("목표 서브태스크");
    }

    @Test
    @DisplayName("선행이 목표보다 앞에 온다")
    void prerequisiteComesFirst() {
        given("build", "구성");
        given("review", "검토", needs("build"));

        SubtaskDagPlanner.Plan plan = planner.plan("review");

        assertThat(plan.executable()).isTrue();
        assertThat(plan.order()).extracting(SubtaskDagPlanner.Step::templateId)
                .containsExactly("build", "review");
    }

    @Test
    @DisplayName("선행의 선행까지 닫아 정렬한다")
    void closesTransitivePrerequisites() {
        given("a", "A");
        given("b", "B", needs("a"));
        given("c", "C", needs("b"));

        assertThat(planner.plan("c").order())
                .extracting(SubtaskDagPlanner.Step::templateId)
                .containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("선행이 여럿이면 모두 목표 앞에 온다")
    void handlesMultiplePrerequisites() {
        given("x", "X");
        given("y", "Y");
        given("goal", "목표", needs("x"), needs("y"));

        List<String> order = planner.plan("goal").order().stream()
                .map(SubtaskDagPlanner.Step::templateId).toList();

        assertThat(order).hasSize(3).endsWith("goal").contains("x", "y");
    }

    @Test
    @DisplayName("순환은 이름과 함께 보고한다 — 조용히 빠뜨리지 않는다")
    void reportsCycle() {
        given("p", "P", needs("q"));
        given("q", "Q", needs("p"));

        SubtaskDagPlanner.Plan plan = planner.plan("p");

        assertThat(plan.executable()).isFalse();
        assertThat(plan.cycle()).contains("p", "q");
        assertThat(plan.order()).isEmpty();
    }

    @Test
    @DisplayName("등록되지 않은 선행은 missing 으로 보고하고 실행 불가로 판정한다")
    void reportsMissingPrerequisite() {
        given("goal", "목표", needs("존재하지-않는-태스크"));

        SubtaskDagPlanner.Plan plan = planner.plan("goal");

        assertThat(plan.missing()).containsExactly("존재하지-않는-태스크");
        assertThat(plan.executable()).isFalse();
        // 목표 자체는 정렬에 남는다 — 무엇이 빠졌는지와 무엇을 하려 했는지 둘 다 보여야 한다.
        assertThat(plan.order()).extracting(SubtaskDagPlanner.Step::templateId)
                .containsExactly("goal");
    }

    @Test
    @DisplayName("선택 선행은 계획에 넣되 required=false 로 구분한다")
    void marksOptionalPrerequisite() {
        given("opt", "선택 작업");
        registry.register(new SubtaskTemplate("goal", "1.0.0", "목표",
                new Routing(List.of(), "설명", 1,
                        List.of(new Dependency("opt", "있으면 더 정확하다", false))),
                List.of(), DialogueControl.defaults(),
                new StructureBinding("ses", "루트", Map.of(), List.of()),
                ValidationSpec.empty(), ExecutionSpec.defaults(), OutputSpec.defaults(), null));

        SubtaskDagPlanner.Plan plan = planner.plan("goal");

        assertThat(plan.order()).extracting(SubtaskDagPlanner.Step::templateId)
                .containsExactly("opt", "goal");
        assertThat(plan.order().get(0).required()).isFalse();
        assertThat(plan.order().get(0).reason()).isEqualTo("있으면 더 정확하다");
    }
}
