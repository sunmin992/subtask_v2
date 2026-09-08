package org.hanbat.ses.dialogue.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hanbat.ses.core.model.Range;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.resolve.SlotOption;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.core.validate.ValueCoercion;
import org.hanbat.ses.dialogue.control.ResolvedSlot;
import org.hanbat.ses.template.model.ValueSlot;
import org.springframework.stereotype.Component;

/**
 * 1단계 — 자료형, 허용 범위, 열거값.
 *
 * <p>LLM 이 추출한 값도 예외 없이 여기를 지난다. "정원 500명"을 뽑아냈어도
 * 범위가 1~200 이면 거부하고 다시 묻는다. 이 관문이 없으면 LLM 의 착각이
 * 그대로 시뮬레이션 파라미터가 된다.
 */
@Component
public class TypeValidator implements SlotValidator {

    @Override
    public int order() {
        return 10;
    }

    @Override
    public String name() {
        return "type";
    }

    @Override
    public List<ValidationIssue> validate(ValidationContext ctx) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (Map.Entry<String, Object> e : ctx.candidate().entrySet()) {
            ResolvedSlot slot = ctx.slots().get(e.getKey());
            if (slot == null) {
                issues.add(ValidationIssue.error(e.getKey(), "SLOT_NOT_OPEN",
                        "지금 열려 있지 않은 슬롯에 답변이 들어왔습니다: " + e.getKey()));
                continue;
            }
            switch (slot.kind()) {
                case SELECT -> checkSelect(slot, e.getValue(), issues);
                case MULTIPLICITY -> checkMultiplicity(slot, e.getValue(), issues);
                case VALUE -> checkValue(slot, e.getValue(), issues);
            }
        }
        return issues;
    }

    private void checkSelect(ResolvedSlot slot, Object value, List<ValidationIssue> issues) {
        if (value == null) {
            issues.add(required(slot));
            return;
        }
        String token = value.toString().trim();
        // 별칭도 받아들인다 — "곤돌라"라고 답해도 케이블카로 해소된다.
        boolean known = slot.open().options().stream().anyMatch(o -> matches(o, token));
        if (!known) {
            issues.add(ValidationIssue.error(slot.name(), "OPTION_UNKNOWN",
                    slot.name() + " 은(는) 다음 중에서 골라주세요: "
                            + slot.open().options().stream().map(SlotOption::describe).toList()));
        }
    }

    /** id · 이름 · 별칭을 공백과 대소문자를 무시해 비교한다. */
    private boolean matches(SlotOption option, String token) {
        String needle = squash(token);
        if (needle.isEmpty()) {
            return false;
        }
        if (squash(option.id()).equals(needle) || squash(option.label()).equals(needle)) {
            return true;
        }
        return option.aliases().stream().anyMatch(a -> squash(a).equals(needle));
    }

    private static String squash(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private void checkMultiplicity(ResolvedSlot slot, Object value, List<ValidationIssue> issues) {
        Optional<Double> n = ValueCoercion.asNumber(value);
        if (n.isEmpty() || n.get() != Math.rint(n.get())) {
            issues.add(ValidationIssue.error(slot.name(), "NOT_AN_INTEGER",
                    slot.name() + " 은(는) 정수 개수여야 합니다. 입력값: " + value));
            return;
        }
        var range = slot.open().countRange();
        if (range != null && !range.contains(n.get().intValue())) {
            issues.add(ValidationIssue.error(slot.name(), "COUNT_OUT_OF_RANGE",
                    slot.name() + " 은(는) " + range.min() + "~" + range.max()
                            + " 사이여야 합니다. 입력값: " + value));
        }
    }

    private void checkValue(ResolvedSlot slot, Object value, List<ValidationIssue> issues) {
        VarDef var = slot.open().varDef();
        if (value == null) {
            issues.add(required(slot));
            return;
        }
        Optional<Object> coerced = ValueCoercion.coerce(value, var.type());
        if (coerced.isEmpty()) {
            issues.add(ValidationIssue.error(slot.name(), "TYPE_MISMATCH",
                    slot.name() + " 은(는) " + describe(var) + " 형식이어야 합니다. 입력값: " + value));
            return;
        }
        checkRange(slot, var.range(), coerced.get(), var.unit(), issues);
        // 템플릿이 SES 보다 좁은 범위를 선언했다면 그쪽도 만족해야 한다.
        if (slot.spec() instanceof ValueSlot vs && vs.range() != null && !vs.range().isEmpty()) {
            checkRange(slot, vs.range(), coerced.get(), vs.unit(), issues);
        }
    }

    private void checkRange(ResolvedSlot slot, Range range, Object value,
                            String unit, List<ValidationIssue> issues) {
        if (range == null || range.isEmpty()) {
            return;
        }
        if (!range.allowed().isEmpty()) {
            if (!range.containsToken(value.toString())) {
                issues.add(ValidationIssue.error(slot.name(), "ENUM_UNKNOWN",
                        slot.name() + " 은(는) 다음 중 하나여야 합니다: " + range.allowed()));
            }
            return;
        }
        ValueCoercion.asNumber(value).ifPresent(n -> {
            if (!range.containsNumber(n)) {
                issues.add(ValidationIssue.error(slot.name(), "OUT_OF_RANGE",
                        slot.name() + " 은(는) " + range.describe()
                                + (unit == null ? "" : " " + unit)
                                + " 범위여야 합니다. 입력값: " + value));
            }
        });
    }

    private ValidationIssue required(ResolvedSlot slot) {
        return ValidationIssue.error(slot.name(), "VALUE_REQUIRED",
                slot.name() + " 값이 필요합니다.");
    }

    private String describe(VarDef var) {
        return switch (var.type()) {
            case INT -> "정수";
            case DOUBLE -> "실수";
            case BOOL -> "예/아니오";
            case ENUM -> "선택값";
            case STRING -> "문자열";
        };
    }
}
