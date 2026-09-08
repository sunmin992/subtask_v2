package org.hanbat.ses.template.validate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.AxisType;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.template.model.ReferenceSlot;
import org.hanbat.ses.template.model.SlotSpec;
import org.hanbat.ses.template.model.StructuralKind;
import org.hanbat.ses.template.model.StructuralSlot;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hanbat.ses.template.model.ValueSlot;

/**
 * 템플릿 - SES 정합성 검사.
 *
 * <p>계획서가 꼽은 최대 리스크가 "템플릿-SES 불일치로 인한 디버깅 지옥"이다.
 * SES 를 단일 진실 원천으로 두는 것만으로는 부족하고, 템플릿이 존재하지 않는 노드를
 * 가리키거나 선택지가 어긋난 상태로 등록되는 것을 <b>등록 시점에</b> 막아야 한다.
 * 런타임까지 흘러가면 "질문이 안 나온다"는 증상만 남고 원인은 보이지 않는다.
 */
public final class TemplateConsistencyChecker {

    public List<ValidationIssue> check(SubtaskTemplate template, SesNode ses) {
        List<ValidationIssue> issues = new ArrayList<>();

        Map<String, SesNode> nodes = new HashMap<>();
        index(ses, nodes);

        Set<String> slotNames = new HashSet<>();
        for (SlotSpec slot : template.slots()) {
            if (!slotNames.add(slot.name())) {
                issues.add(ValidationIssue.error(slot.name(), "SLOT_NAME_DUPLICATE",
                        "슬롯 이름이 중복됩니다: " + slot.name()));
            }
            checkSlot(slot, nodes, issues);
        }

        for (SlotSpec slot : template.slots()) {
            for (String dep : slot.dependsOn()) {
                if (!slotNames.contains(dep)) {
                    issues.add(ValidationIssue.warning(slot.name(), "DEPENDS_ON_UNKNOWN",
                            slot.name() + " 이(가) 정의되지 않은 슬롯에 의존합니다: " + dep));
                }
            }
        }

        if (template.binding() != null && template.binding().rootEntity() != null
                && ses instanceof EntityNode root
                && !root.name().equals(template.binding().rootEntity())) {
            issues.add(ValidationIssue.warning("binding.rootEntity", "ROOT_ENTITY_MISMATCH",
                    "바인딩의 루트 엔티티(" + template.binding().rootEntity()
                            + ")가 SES 루트(" + root.name() + ")와 다릅니다."));
        }
        return issues;
    }

    private void checkSlot(SlotSpec slot, Map<String, SesNode> nodes, List<ValidationIssue> issues) {
        SesAnchor anchor = slot.anchor();
        if (anchor == null) {
            issues.add(ValidationIssue.error(slot.name(), "ANCHOR_MISSING",
                    slot.name() + " 에 SES 앵커가 없습니다. 템플릿과 SES 를 잇는 유일한 고리입니다."));
            return;
        }
        SesNode target = nodes.get(anchor.prototypeNodeId());
        if (target == null) {
            issues.add(ValidationIssue.error(slot.name(), "ANCHOR_DANGLING",
                    slot.name() + " 의 앵커가 SES 에 없는 노드를 가리킵니다: " + anchor.targetNodeId()));
            return;
        }

        switch (slot) {
            case StructuralSlot s -> checkStructural(s, target, issues);
            case ValueSlot v -> checkValue(v, target, issues);
            case ReferenceSlot r -> {
                if (r.sourceTaskId() == null || r.sourceTaskId().isBlank()) {
                    issues.add(ValidationIssue.error(r.name(), "REFERENCE_NO_SOURCE",
                            r.name() + " 참조 슬롯에 선행 태스크 id 가 없습니다."));
                }
            }
        }
    }

    private void checkStructural(StructuralSlot slot, SesNode target, List<ValidationIssue> issues) {
        if (slot.structuralKind() == StructuralKind.SELECT) {
            if (!(target instanceof SpecNode spec)) {
                issues.add(ValidationIssue.error(slot.name(), "ANCHOR_KIND_MISMATCH",
                        slot.name() + " 은 SELECT 인데 앵커가 specialization 노드가 아닙니다."));
                return;
            }
            if (!slot.options().isEmpty()) {
                Set<String> actual = new HashSet<>();
                spec.variants().forEach(v -> {
                    actual.add(v.name());
                    actual.add(v.id());
                });
                slot.options().stream().filter(o -> !actual.contains(o)).forEach(o ->
                        issues.add(ValidationIssue.error(slot.name(), "OPTION_NOT_IN_SES",
                                slot.name() + " 의 선택지 '" + o + "' 가 SES 변형 목록에 없습니다.")));
                if (slot.options().size() != spec.variants().size()) {
                    issues.add(ValidationIssue.warning(slot.name(), "OPTION_COUNT_MISMATCH",
                            "템플릿 선택지 " + slot.options().size() + "개, SES 변형 "
                                    + spec.variants().size() + "개로 다릅니다."));
                }
            }
        } else if (slot.structuralKind() == StructuralKind.MULTIPLICITY
                && !(target instanceof MultiAspectNode)) {
            issues.add(ValidationIssue.error(slot.name(), "ANCHOR_KIND_MISMATCH",
                    slot.name() + " 은 MULTIPLICITY 인데 앵커가 multi-aspect 노드가 아닙니다."));
        }
    }

    private void checkValue(ValueSlot slot, SesNode target, List<ValidationIssue> issues) {
        if (slot.anchor().axis() != AxisType.VARIABLE) {
            issues.add(ValidationIssue.error(slot.name(), "ANCHOR_KIND_MISMATCH",
                    slot.name() + " 은 값 슬롯인데 앵커 축이 VARIABLE 이 아닙니다."));
            return;
        }
        if (!(target instanceof EntityNode entity)) {
            issues.add(ValidationIssue.error(slot.name(), "ANCHOR_KIND_MISMATCH",
                    slot.name() + " 의 앵커가 엔티티가 아닙니다."));
            return;
        }
        VarDef var = entity.var(slot.anchor().varName());
        if (var == null) {
            issues.add(ValidationIssue.error(slot.name(), "VAR_NOT_IN_SES",
                    entity.name() + " 에 변수 " + slot.anchor().varName() + " 이(가) 없습니다."));
            return;
        }
        if (slot.type() != null && slot.type() != var.type()) {
            issues.add(ValidationIssue.error(slot.name(), "TYPE_MISMATCH",
                    slot.name() + " 의 타입이 SES(" + var.type() + ")와 다릅니다: " + slot.type()));
        }
        if (slot.unit() != null && var.unit() != null && !slot.unit().equals(var.unit())) {
            issues.add(ValidationIssue.warning(slot.name(), "UNIT_MISMATCH",
                    slot.name() + " 의 단위가 SES(" + var.unit() + ")와 다릅니다: " + slot.unit()));
        }
    }

    private void index(SesNode node, Map<String, SesNode> out) {
        out.put(node.id(), node);
        switch (node) {
            case EntityNode e -> e.axes().forEach(a -> index(a, out));
            case AspectNode a -> a.components().forEach(c -> index(c, out));
            case SpecNode s -> s.variants().forEach(v -> index(v, out));
            case MultiAspectNode m -> {
                index(m.prototype(), out);
                m.instances().forEach(i -> index(i, out));
            }
        }
    }
}
