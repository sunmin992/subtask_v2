package org.hanbat.ses.dialogue.control;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.resolve.SlotOption;
import org.hanbat.ses.dialogue.session.Question;
import org.hanbat.ses.dialogue.session.QuestionInputType;
import org.hanbat.ses.dialogue.session.RangeView;
import org.hanbat.ses.template.model.DialogueControl;
import org.hanbat.ses.template.model.QuestionSpec;
import org.hanbat.ses.template.model.SlotSpec;
import org.hanbat.ses.template.model.ValueSlot;
import org.springframework.stereotype.Component;

/**
 * 이번 턴에 무엇을 물을지 고른다.
 *
 * <p>두 가지 정책이 들어 있다.
 * <ol>
 *   <li><b>구조 우선</b> — 구조 결정은 새 슬롯을 파생시키므로 먼저 끝내야 한다.
 *       값부터 물으면 구조가 바뀌면서 방금 받은 답이 버려질 수 있다.</li>
 *   <li><b>배치</b> — 서로 의존하지 않는 슬롯은 한 턴에 묶어 묻는다.
 *       한 턴에 하나씩 물으면 슬롯이 열 개일 때 열 번을 왕복해야 한다.</li>
 * </ol>
 */
@Component
public class QuestionPlanner {

    public List<Question> plan(List<ResolvedSlot> open, Map<String, Object> answers,
                               DialogueControl control) {
        List<ResolvedSlot> ready = open.stream()
                .filter(s -> dependenciesSatisfied(s, answers))
                .toList();
        if (ready.isEmpty()) {
            // 의존성이 순환하거나 잘못 선언된 경우에도 대화가 멈추면 안 된다.
            ready = open;
        }

        boolean hasStructural = ready.stream().anyMatch(s -> s.kind().isStructural());
        List<ResolvedSlot> pool = hasStructural
                ? ready.stream().filter(s -> s.kind().isStructural()).toList()
                : ready;

        List<Question> questions = new ArrayList<>();
        Set<String> picked = new LinkedHashSet<>();
        for (ResolvedSlot slot : pool) {
            if (questions.size() >= control.maxQuestionsPerTurn()) {
                break;
            }
            // 같은 턴에 묶인 슬롯끼리 의존하면 순서가 뒤엉킨다.
            if (slot.dependsOn().stream().anyMatch(picked::contains)) {
                continue;
            }
            picked.add(slot.name());
            questions.add(toQuestion(slot));
        }
        return questions;
    }

    private boolean dependenciesSatisfied(ResolvedSlot slot, Map<String, Object> answers) {
        return slot.dependsOn().stream().allMatch(answers::containsKey);
    }

    public Question toQuestion(ResolvedSlot slot) {
        QuestionSpec spec = questionSpec(slot);
        return switch (slot.kind()) {
            case SELECT -> new Question(
                    slot.name(),
                    spec != null ? spec.text() : slot.name() + " 은(는) 무엇으로 할까요?",
                    aliasHint(slot, spec),
                    QuestionInputType.SELECT, null, new RangeView(null, null,
                    slot.open().options().stream().map(SlotOption::label).toList()),
                    null,
                    slot.open().options().stream().map(SlotOption::label).toList(),
                    slot.entityPath(), true);
            case MULTIPLICITY -> new Question(
                    slot.name(),
                    spec != null ? spec.text() : slot.name() + " 은(는) 몇 개인가요?",
                    spec == null ? null : spec.hint(),
                    QuestionInputType.INTEGER, "개",
                    slot.open().countRange() == null ? new RangeView(null, null, List.of())
                            : new RangeView((double) slot.open().countRange().min(),
                            (double) slot.open().countRange().max(), List.of()),
                    slot.templateDefault(), List.of(), slot.entityPath(), true);
            case VALUE -> valueQuestion(slot, spec);
        };
    }

    private Question valueQuestion(ResolvedSlot slot, QuestionSpec spec) {
        VarDef var = slot.open().varDef();
        String unit = var.unit();
        if (slot.spec() instanceof ValueSlot vs && vs.unit() != null) {
            unit = vs.unit();
        }
        String text = spec != null ? spec.text()
                : "%s 의 %s 은(는) 얼마인가요?".formatted(slot.entityPath(), var.name());
        return new Question(
                slot.name(), text,
                spec == null ? hintFor(var, unit) : spec.hint(),
                inputTypeOf(var),
                unit,
                new RangeView(var.range().min(), var.range().max(), var.range().allowed()),
                slot.templateDefault(),
                var.range().allowed(),
                slot.entityPath(),
                false);
    }

    /** 검증에 걸린 슬롯은 재질문 문구로 다시 묻는다. */
    public Question toReask(ResolvedSlot slot) {
        Question q = toQuestion(slot);
        QuestionSpec spec = questionSpec(slot);
        if (spec == null) {
            return q;
        }
        return new Question(q.slot(), spec.reaskOrText(), q.hint(), q.inputType(), q.unit(),
                q.range(), q.defaultValue(), q.options(), q.entityPath(), q.structural());
    }

    /**
     * 별칭이 있으면 힌트에 덧붙인다.
     *
     * <p>선택지 목록에는 대표 이름만 보이므로, 사용자가 "곤돌라"라고 부르는 것을
     * 고를 수 있다는 사실을 알려 줄 자리가 필요하다.
     */
    private String aliasHint(ResolvedSlot slot, QuestionSpec spec) {
        String base = spec == null ? null : spec.hint();
        String aliases = slot.open().options().stream()
                .filter(o -> !o.aliases().isEmpty())
                .map(SlotOption::describe)
                .collect(java.util.stream.Collectors.joining(", "));
        if (aliases.isEmpty()) {
            return base;
        }
        return base == null || base.isBlank() ? aliases : base + " (" + aliases + ")";
    }

    private QuestionSpec questionSpec(ResolvedSlot slot) {
        SlotSpec spec = slot.spec();
        return spec == null ? null : spec.question();
    }

    private QuestionInputType inputTypeOf(VarDef var) {
        return switch (var.type()) {
            case INT -> QuestionInputType.INTEGER;
            case DOUBLE -> QuestionInputType.DOUBLE;
            case BOOL -> QuestionInputType.BOOLEAN;
            case ENUM -> QuestionInputType.SELECT;
            case STRING -> QuestionInputType.TEXT;
        };
    }

    private String hintFor(VarDef var, String unit) {
        if (var.range() == null || var.range().isEmpty()) {
            return unit == null ? null : "단위: " + unit;
        }
        return "허용 범위 " + var.range().describe() + (unit == null ? "" : " " + unit);
    }
}
