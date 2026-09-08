package org.hanbat.ses.dialogue.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.core.validate.ValueCoercion;
import org.hanbat.ses.template.model.SimulatorConfig;
import org.springframework.stereotype.Component;

/**
 * 3단계 — 단위 호환과 시간 해상도.
 *
 * <p>계획서에 없던 검사를 하나 넣었다. 시간 단위 변수의 값이 시간 해상도보다 작으면
 * 시뮬레이터가 그 값을 표현하지 못한다. "운행 간격 0.5분"과 "해상도 1분"은 각각은
 * 멀쩡한 값이지만 함께 두면 케이블카가 영원히 오지 않는다.
 * 이런 조합은 실행해 봐야 알 수 있는 종류의 오류라 사전 검사가 특히 값지다.
 */
@Component
public class UnitConsistencyValidator implements SlotValidator {

    /** 분 기준 환산표. 시간 단위인지 판별하고 값을 비교하는 데 쓴다. */
    private static final Map<String, Double> TIME_UNITS_IN_MINUTES = Map.of(
            "초", 1.0 / 60, "s", 1.0 / 60, "sec", 1.0 / 60,
            "분", 1.0, "min", 1.0,
            "시간", 60.0, "h", 60.0, "hour", 60.0,
            "일", 1440.0);

    private static final double MAX_STEPS = 1_000_000;

    @Override
    public int order() {
        return 30;
    }

    @Override
    public String name() {
        return "unit";
    }

    @Override
    public List<ValidationIssue> validate(ValidationContext ctx) {
        List<ValidationIssue> issues = new ArrayList<>();
        SimulatorConfig sim = ctx.simConfig();

        double dt = sim.timeResolution();
        double horizon = sim.horizon();
        if (dt > 0 && horizon / dt > MAX_STEPS) {
            issues.add(ValidationIssue.error("simulator.timeResolution", "TOO_MANY_STEPS",
                    "시간 해상도 %.3f 로 %.0f 기간을 돌리면 스텝이 100만 회를 넘습니다."
                            .formatted(dt, horizon)));
        }

        Map<String, String> aliases = ctx.template() == null
                ? Map.of() : ctx.template().validation().unitAliases();
        walk(ctx.ses(), "", dt, horizon, aliases, issues);
        return issues;
    }

    private void walk(SesNode node, String path, double dt, double horizon,
                      Map<String, String> aliases, List<ValidationIssue> issues) {
        switch (node) {
            case EntityNode e -> {
                String p = path.isEmpty() ? e.name() : path + "/" + e.name();
                for (VarDef v : e.vars()) {
                    checkVar(p, e.id(), v, dt, horizon, aliases, issues);
                }
                e.axes().forEach(a -> walk(a, p, dt, horizon, aliases, issues));
            }
            case AspectNode a -> a.components()
                    .forEach(c -> walk(c, path, dt, horizon, aliases, issues));
            case SpecNode s -> {
                if (s.selected() != null) {
                    walk(s.selected(), path, dt, horizon, aliases, issues);
                }
            }
            case MultiAspectNode m -> {
                for (int i = 0; i < m.instances().size(); i++) {
                    walk(m.instances().get(i), path + "/" + m.name() + "[" + i + "]",
                            dt, horizon, aliases, issues);
                }
            }
        }
    }

    private void checkVar(String path, String ownerId, VarDef v, double dt, double horizon,
                          Map<String, String> aliases, List<ValidationIssue> issues) {
        Object value = v.effectiveValue();
        if (value == null || v.unit() == null || v.unit().isBlank()) {
            return;
        }
        String unit = aliases.getOrDefault(v.unit(), v.unit());
        Double factor = TIME_UNITS_IN_MINUTES.get(unit);
        if (factor == null) {
            return;
        }
        Optional<Double> n = ValueCoercion.asNumber(value);
        if (n.isEmpty()) {
            return;
        }
        double minutes = n.get() * factor;
        // 사람이 읽는 경로는 메시지에 싣고, issue 의 slot 에는 재질문 대상을 찾을 수 있는
        // 기계 이름을 넣는다. SlotResolver 가 만드는 이름과 같은 형식이어야 매칭된다.
        String label = path + "." + v.name();
        String slot = ownerId + "." + v.name();

        if (minutes > 0 && minutes < dt) {
            issues.add(ValidationIssue.error(slot, "BELOW_TIME_RESOLUTION",
                    "%s 이(가) %s%s 로 시간 해상도 %.3f 보다 작습니다. 시뮬레이터가 이 간격을 표현할 수 없습니다."
                            .formatted(label, value, v.unit(), dt)));
        } else if (minutes > horizon) {
            issues.add(ValidationIssue.warning(slot, "EXCEEDS_HORIZON",
                    "%s 이(가) %s%s 로 시뮬레이션 기간 %.0f 보다 깁니다. 이 사건은 한 번도 일어나지 않습니다."
                            .formatted(label, value, v.unit(), horizon)));
        }
    }
}
