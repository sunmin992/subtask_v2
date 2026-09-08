package org.hanbat.ses.dialogue.validate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.hanbat.ses.core.validate.ValidationIssue;
import org.springframework.stereotype.Component;

/**
 * Chain of Responsibility — 앞 단계가 오류를 내면 뒤 단계는 실행하지 않는다.
 *
 * <p>타입이 깨진 값으로 교차 제약을 평가하면 원인과 무관한 오류가 쏟아진다.
 * 사용자에게는 "지금 고쳐야 할 것" 하나만 보여야 한다.
 */
@Component
public class ValidationChain {

    private final List<SlotValidator> validators;

    public ValidationChain(List<SlotValidator> validators) {
        this.validators = validators.stream()
                .sorted(Comparator.comparingInt(SlotValidator::order))
                .toList();
    }

    /**
     * 답변을 트리에 적용하기 <b>전</b> 검사 — 타입과 도메인만 본다.
     *
     * <p>여기서 값에 의존하는 검사를 할 수 없다. 트리에 아직 값이 들어가지 않았기 때문이다.
     */
    public List<ValidationIssue> validateAnswers(ValidationContext ctx) {
        return run(ctx, 0, 10);
    }

    /**
     * 답변을 적용한 <b>뒤</b> 검사 — 교차 제약과 단위.
     *
     * <p>트리가 불변이라 "적용해 보고 문제가 있으면 되돌리기"가 공짜다.
     * 값이 실제로 들어간 트리를 보고 검사하는 편이 훨씬 정확하다.
     * 예컨대 "운행 간격 0.5분 vs 시간 해상도 1분" 은 값이 트리에 들어가야만 보인다.
     */
    public List<ValidationIssue> validateApplied(ValidationContext ctx) {
        return run(ctx, 11, 30);
    }

    /** 완료 판정 직전 전 구간 검증. */
    public List<ValidationIssue> validateAll(ValidationContext ctx) {
        return run(ctx, 0, Integer.MAX_VALUE);
    }

    private List<ValidationIssue> run(ValidationContext ctx, int minOrder, int maxOrder) {
        // 같은 값을 두 곳에서 검사할 수 있다 — SES 의 범위와 템플릿이 좁힌 범위가 그렇다.
        // 사용자에게 똑같은 문장을 두 번 보여 줄 이유는 없다.
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        List<ValidationIssue> collected = new ArrayList<>();
        for (SlotValidator v : validators) {
            if (v.order() < minOrder) {
                continue;
            }
            if (v.order() > maxOrder) {
                break;
            }
            List<ValidationIssue> issues = v.validate(ctx).stream()
                    .filter(i -> seen.add(i.slot() + "|" + i.code() + "|" + i.message()))
                    .toList();
            collected.addAll(issues);
            if (issues.stream().anyMatch(ValidationIssue::isError)) {
                // 이 단계에서 막혔다. 뒤 단계의 노이즈를 사용자에게 보이지 않는다.
                break;
            }
        }
        return List.copyOf(collected);
    }

    public List<String> stages() {
        return validators.stream().map(SlotValidator::name).toList();
    }
}
