package org.hanbat.ses.template.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.AxisType;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.Range;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.model.VarType;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.template.model.DialogueControl;
import org.hanbat.ses.template.model.ExecutionSpec;
import org.hanbat.ses.template.model.OutputSpec;
import org.hanbat.ses.template.model.QuestionSpec;
import org.hanbat.ses.template.model.Routing;
import org.hanbat.ses.template.model.SlotSpec;
import org.hanbat.ses.template.model.StructuralKind;
import org.hanbat.ses.template.model.StructuralSlot;
import org.hanbat.ses.template.model.StructureBinding;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hanbat.ses.template.model.ValidationSpec;
import org.hanbat.ses.template.model.ValueSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 계획서가 꼽은 최대 리스크가 "템플릿-SES 불일치로 인한 디버깅 지옥"이다.
 * 그 불일치가 등록 시점에 잡히는지 확인한다 — 런타임까지 흘러가면
 * "질문이 안 나온다"는 증상만 남고 원인은 보이지 않는다.
 */
class TemplateConsistencyCheckerTest {

    private final TemplateConsistencyChecker checker = new TemplateConsistencyChecker();

    private static SesNode ses() {
        EntityNode cableCar = EntityNode.leaf("ent-cablecar", "케이블카", "cable-car",
                List.of(VarDef.of("정원", VarType.INT, "명", Range.between(1, 200))), List.of());
        EntityNode shuttle = EntityNode.leaf("ent-shuttle", "셔틀버스", "shuttle-bus",
                List.of(VarDef.of("정원", VarType.INT, "명", Range.between(1, 60))), List.of());
        EntityNode dummy = EntityNode.leaf("ent-dummy", "더미", "processor", List.of(), List.of());

        return new EntityNode("ent-resort", "리조트", List.of(),
                List.of(AspectNode.of("asp", "구성", List.of(dummy)),
                        SpecNode.open("spec-transport", "이동설비", List.of(cableCar, shuttle))),
                null, List.of(), List.of(), List.of());
    }

    private static SubtaskTemplate templateWith(List<SlotSpec> slots) {
        return new SubtaskTemplate("t", "1.0.0", "테스트",
                new Routing(List.of("리조트"), "설명", 1, List.of()),
                slots, DialogueControl.defaults(),
                new StructureBinding("ses", "리조트", Map.of(), List.of()),
                ValidationSpec.empty(), ExecutionSpec.defaults(), OutputSpec.defaults(), null);
    }

    @Test
    @DisplayName("앵커가 SES 에 없는 노드를 가리키면 오류")
    void detectsDanglingAnchor() {
        SubtaskTemplate t = templateWith(List.of(new StructuralSlot("없는것",
                SesAnchor.node("리조트/없음", AxisType.SPECIALIZATION, "spec-nope"),
                StructuralKind.SELECT, List.of(), QuestionSpec.of("?"), List.of())));

        assertThat(codes(checker.check(t, ses()))).contains("ANCHOR_DANGLING");
    }

    @Test
    @DisplayName("템플릿 선택지가 SES 변형 목록에 없으면 오류")
    void detectsOptionNotInSes() {
        SubtaskTemplate t = templateWith(List.of(new StructuralSlot("이동설비",
                SesAnchor.node("리조트/이동설비", AxisType.SPECIALIZATION, "spec-transport"),
                StructuralKind.SELECT, List.of("케이블카", "헬리콥터"),
                QuestionSpec.of("?"), List.of())));

        List<ValidationIssue> issues = checker.check(t, ses());
        assertThat(codes(issues)).contains("OPTION_NOT_IN_SES");
        assertThat(issues).anyMatch(i -> i.message().contains("헬리콥터"));
    }

    @Test
    @DisplayName("MULTIPLICITY 슬롯이 specialization 을 가리키면 오류")
    void detectsAnchorKindMismatch() {
        SubtaskTemplate t = templateWith(List.of(new StructuralSlot("개수",
                SesAnchor.node("리조트/이동설비", AxisType.MULTI_ASPECT, "spec-transport"),
                StructuralKind.MULTIPLICITY, List.of(), QuestionSpec.of("?"), List.of())));

        assertThat(codes(checker.check(t, ses()))).contains("ANCHOR_KIND_MISMATCH");
    }

    @Test
    @DisplayName("SES 에 없는 변수를 가리키는 값 슬롯은 오류")
    void detectsMissingVariable() {
        SubtaskTemplate t = templateWith(List.of(new ValueSlot("케이블카.속도",
                SesAnchor.variable("리조트/이동설비/케이블카", "ent-cablecar", "속도"),
                VarType.DOUBLE, "m/s", Range.none(), null, false,
                QuestionSpec.of("?"), List.of())));

        assertThat(codes(checker.check(t, ses()))).contains("VAR_NOT_IN_SES");
    }

    @Test
    @DisplayName("타입이 SES 와 다르면 오류, 단위만 다르면 경고")
    void detectsTypeAndUnitMismatch() {
        SubtaskTemplate t = templateWith(List.of(new ValueSlot("케이블카.정원",
                SesAnchor.variable("리조트/이동설비/케이블카", "ent-cablecar", "정원"),
                VarType.DOUBLE, "인", Range.none(), null, false,
                QuestionSpec.of("?"), List.of())));

        List<ValidationIssue> issues = checker.check(t, ses());
        assertThat(codes(issues)).contains("TYPE_MISMATCH", "UNIT_MISMATCH");
        assertThat(issues).filteredOn(i -> i.code().equals("UNIT_MISMATCH"))
                .allMatch(i -> !i.isError());
    }

    @Test
    @DisplayName("슬롯 이름 중복과 정의되지 않은 의존을 잡는다")
    void detectsDuplicatesAndUnknownDependencies() {
        SlotSpec a = new ValueSlot("정원",
                SesAnchor.variable("리조트/이동설비/케이블카", "ent-cablecar", "정원"),
                VarType.INT, "명", Range.none(), null, false, QuestionSpec.of("?"),
                List.of("존재하지않는슬롯"));
        SlotSpec b = new ValueSlot("정원",
                SesAnchor.variable("리조트/이동설비/셔틀버스", "ent-shuttle", "정원"),
                VarType.INT, "명", Range.none(), null, false, QuestionSpec.of("?"), List.of());

        List<ValidationIssue> issues = checker.check(templateWith(List.of(a, b)), ses());

        assertThat(codes(issues)).contains("SLOT_NAME_DUPLICATE", "DEPENDS_ON_UNKNOWN");
    }

    @Test
    @DisplayName("정합한 템플릿은 오류 없이 통과한다")
    void validTemplatePasses() {
        SubtaskTemplate t = templateWith(List.of(
                new StructuralSlot("이동설비",
                        SesAnchor.node("리조트/이동설비", AxisType.SPECIALIZATION, "spec-transport"),
                        StructuralKind.SELECT, List.of("케이블카", "셔틀버스"),
                        QuestionSpec.of("무엇으로 할까요?"), List.of()),
                new ValueSlot("케이블카.정원",
                        SesAnchor.variable("리조트/이동설비/케이블카", "ent-cablecar", "정원"),
                        VarType.INT, "명", Range.between(1, 200), 8, true,
                        QuestionSpec.of("정원은?"), List.of("이동설비"))));

        assertThat(checker.check(t, ses())).filteredOn(ValidationIssue::isError).isEmpty();
    }

    private static List<String> codes(List<ValidationIssue> issues) {
        return issues.stream().map(ValidationIssue::code).toList();
    }
}
