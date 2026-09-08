package org.hanbat.ses.dialogue.validate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.template.model.CrossFieldRule;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * 2단계 — 필드 간 제약. Spring Expression Language 로 쓴다.
 *
 * <p>표현식에서 값을 참조하는 방법은 두 가지다.
 * <ul>
 *   <li>{@code #객실수} — 슬롯 이름이 식별자로 쓸 수 있을 때</li>
 *   <li>{@code #v['cableCar.capacity']} — 점이 섞인 이름일 때</li>
 * </ul>
 * 값의 출처는 답변 맵이 아니라 <b>SES 트리에 실제로 반영된 변수</b>다.
 * 답변 맵만 보면 기본값으로 채워진 값이 빠져 제약이 헛돈다.
 *
 * <p>필요한 값이 아직 없는 규칙은 통과시킨다. 대화 중간에는 항상 뭔가가 비어 있고,
 * 그때마다 오류를 내면 사용자는 자기가 뭘 잘못했는지 알 수 없다.
 */
@Component
public class CrossFieldValidator implements SlotValidator {

    private final ExpressionParser parser = new SpelExpressionParser();

    @Override
    public int order() {
        return 20;
    }

    @Override
    public String name() {
        return "cross-field";
    }

    @Override
    public List<ValidationIssue> validate(ValidationContext ctx) {
        if (ctx.template() == null || ctx.template().validation().crossFieldRules().isEmpty()) {
            return List.of();
        }
        Map<String, Object> vars = collectVariables(ctx);
        List<ValidationIssue> issues = new ArrayList<>();

        for (CrossFieldRule rule : ctx.template().validation().crossFieldRules()) {
            if (!hasAllOperands(rule, vars)) {
                continue;
            }
            StandardEvaluationContext ec = new StandardEvaluationContext();
            ec.setVariable("v", vars);
            vars.forEach((k, value) -> {
                if (isIdentifier(k)) {
                    ec.setVariable(k, value);
                }
            });
            try {
                Boolean ok = parser.parseExpression(rule.expression()).getValue(ec, Boolean.class);
                if (Boolean.FALSE.equals(ok)) {
                    issues.add(ValidationIssue.error("crossField", "CROSS_FIELD_VIOLATION",
                            rule.message()));
                }
            } catch (SpelParseException e) {
                issues.add(ValidationIssue.error("crossField", "RULE_UNPARSEABLE",
                        "검증 규칙을 해석할 수 없습니다: " + rule.expression()));
            } catch (SpelEvaluationException e) {
                // 아직 값이 없어 평가할 수 없는 규칙은 조용히 넘긴다.
                issues.add(ValidationIssue.warning("crossField", "RULE_SKIPPED",
                        "아직 평가할 수 없는 규칙을 건너뛰었습니다: " + rule.expression()));
            }
        }
        return issues;
    }

    /** 규칙이 involves 를 선언했다면 그 값이 모두 준비된 경우에만 평가한다. */
    private boolean hasAllOperands(CrossFieldRule rule, Map<String, Object> vars) {
        if (rule.involves() == null || rule.involves().length == 0) {
            return true;
        }
        for (String operand : rule.involves()) {
            if (!vars.containsKey(operand)) {
                return false;
            }
        }
        return true;
    }

    /** SES 에 실제로 반영된 변수값을 이름으로 모은다. 같은 이름이 여럿이면 마지막 것이 이긴다. */
    private Map<String, Object> collectVariables(ValidationContext ctx) {
        Map<String, Object> vars = new LinkedHashMap<>(ctx.answers());
        walk(ctx.ses(), vars);
        return vars;
    }

    private void walk(SesNode node, Map<String, Object> out) {
        switch (node) {
            case EntityNode e -> {
                for (VarDef v : e.vars()) {
                    Object value = v.effectiveValue();
                    if (value != null) {
                        out.put(v.name(), value);
                        out.put(e.name() + "." + v.name(), value);
                    }
                }
                e.axes().forEach(a -> walk(a, out));
            }
            case AspectNode a -> a.components().forEach(c -> walk(c, out));
            case SpecNode s -> {
                if (s.selected() != null) {
                    walk(s.selected(), out);
                }
            }
            case MultiAspectNode m -> m.instances().forEach(i -> walk(i, out));
        }
    }

    private static boolean isIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        return s.chars().allMatch(Character::isJavaIdentifierPart);
    }
}
